package com.screentimetracker.app.data.repository

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.screentimetracker.app.data.db.AppDatabase
import com.screentimetracker.app.data.db.DailyUsageDao
import com.screentimetracker.app.data.db.entity.DailySummaryEntity
import com.screentimetracker.app.data.db.entity.DailyUsageEntity
import com.screentimetracker.app.data.model.DailyUsageEntry
import com.screentimetracker.app.data.model.SessionInfo
import com.screentimetracker.app.data.prefs.UserPrefs
import com.screentimetracker.app.data.usage.UsageSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Unified repository for usage data that combines:
 * - UsageStatsManager for recent data (reliable for ~7 days)
 * - Local Room database for historical data (persisted indefinitely)
 *
 * Query Strategy:
 * - Days 0-7: Query UsageStatsManager directly (most accurate)
 * - Days 8+: Query local database (fallback when system data may be purged)
 *
 * Sync Strategy:
 * - Write-through on app open: Persist yesterday's data if missing
 * - Background sync: End-of-day persistence via WorkManager
 */
class UsageRepository(
    private val context: Context,
    private val usageStatsManager: UsageStatsManager,
    private val trackedPackages: Set<String>
) {
    private val dao: DailyUsageDao = AppDatabase.getInstance(context).dailyUsageDao()
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    companion object {
        private const val TAG = "UsageRepository"

        /** Days to prefer UsageStatsManager over local database */
        private const val SYSTEM_DATA_THRESHOLD_DAYS = 7

        /** Maximum days to keep in local database (1 year) */
        private const val MAX_RETENTION_DAYS = 365
    }

    /**
     * Get daily usage entries for the past N days.
     * Uses hybrid approach: system data for recent days, local DB for older days.
     *
     * Also performs write-through sync of yesterday's data if missing.
     *
     * @param days Number of days to query (e.g., 7, 14, 30)
     * @return List of DailyUsageEntry sorted by date ascending (oldest first)
     */
    suspend fun getDailyUsageForDays(days: Int): List<DailyUsageEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<DailyUsageEntry>()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(ZoneId.systemDefault())

        // Sync yesterday's data if missing (write-through on access)
        syncYesterdayIfMissing()

        for (daysAgo in (days - 1) downTo 0) {
            val targetDate = today.minusDays(daysAgo.toLong())
            val dayStart = targetDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val totalMillis = if (daysAgo <= SYSTEM_DATA_THRESHOLD_DAYS) {
                // Recent days: query UsageStatsManager
                querySystemUsageForDate(targetDate)
            } else {
                // Older days: query local database
                queryLocalUsageForDate(targetDate)
            }

            entries.add(
                DailyUsageEntry(
                    dateMillis = dayStart,
                    totalMillis = totalMillis
                )
            )
        }

        entries
    }

    /**
     * Query usage from UsageStatsManager for a specific date.
     */
    private fun querySystemUsageForDate(date: LocalDate): Long {
        val dayStart = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            dayStart,
            dayEnd
        )

        return usageStatsList
            ?.filter { stats ->
                trackedPackages.contains(stats.packageName) && stats.totalTimeInForeground > 0
            }
            ?.sumOf { it.totalTimeInForeground }
            ?: 0L
    }

    /**
     * Query usage from local database for a specific date.
     */
    private suspend fun queryLocalUsageForDate(date: LocalDate): Long {
        val dateStr = date.format(dateFormatter)

        // Try summary first (faster)
        val summary = dao.getSummaryForDate(dateStr)
        if (summary != null) {
            return summary.totalMillis
        }

        // Fall back to aggregating per-app data
        return dao.getTotalUsageForDate(dateStr)
    }

    /**
     * Sync yesterday's data to local database if not already present.
     * Called on app open as write-through persistence.
     */
    suspend fun syncYesterdayIfMissing() = withContext(Dispatchers.IO) {
        val yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1)
        val dateStr = yesterday.format(dateFormatter)

        // Check if we already have data
        if (dao.hasDataForDate(dateStr) > 0) {
            Log.d(TAG, "Yesterday's data already synced: $dateStr")
            return@withContext
        }

        Log.d(TAG, "Syncing yesterday's data: $dateStr")
        syncDateFromSystem(yesterday)
    }

    /**
     * Sync a specific date's data from UsageStatsManager to local database.
     */
    suspend fun syncDateFromSystem(date: LocalDate) = withContext(Dispatchers.IO) {
        val dateStr = date.format(dateFormatter)
        val dayStart = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        val syncTime = System.currentTimeMillis()

        // Query per-app usage from system
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            dayStart,
            dayEnd
        )

        if (usageStatsList.isNullOrEmpty()) {
            Log.d(TAG, "No system data available for $dateStr")
            return@withContext
        }

        // Filter to tracked apps and build entities
        val usageEntities = mutableListOf<DailyUsageEntity>()
        var totalMillis = 0L
        var totalSessions = 0
        var appCount = 0

        for (stats in usageStatsList) {
            if (!trackedPackages.contains(stats.packageName)) continue
            if (stats.totalTimeInForeground <= 0) continue

            appCount++
            totalMillis += stats.totalTimeInForeground

            // Get session data for this app
            val sessions = UsageSessionRepository.getSessionsForAppOverlappingWindow(
                usageStatsManager = usageStatsManager,
                packageName = stats.packageName,
                appName = stats.packageName, // Name not needed for counting
                windowStart = dayStart,
                windowEnd = dayEnd
            )

            val sessionCount = sessions.size
            val longestSession = sessions.maxOfOrNull { it.overlapDuration(dayStart, dayEnd) } ?: 0L
            totalSessions += sessionCount

            usageEntities.add(
                DailyUsageEntity(
                    date = dateStr,
                    packageName = stats.packageName,
                    totalMillis = stats.totalTimeInForeground,
                    sessionCount = sessionCount,
                    longestSessionMillis = longestSession,
                    syncedAt = syncTime
                )
            )
        }

        // Insert per-app data
        if (usageEntities.isNotEmpty()) {
            dao.insertAllDailyUsage(usageEntities)
            Log.d(TAG, "Synced ${usageEntities.size} app entries for $dateStr")
        }

        // Insert summary
        dao.insertDailySummary(
            DailySummaryEntity(
                date = dateStr,
                totalMillis = totalMillis,
                sessionCount = totalSessions,
                appCount = appCount,
                syncedAt = syncTime
            )
        )
        Log.d(TAG, "Synced summary for $dateStr: ${totalMillis}ms across $appCount apps")
    }

    /**
     * Sync multiple past days to local database.
     * Useful for initial backfill or recovery.
     *
     * @param days Number of days to sync (starting from yesterday going back)
     */
    suspend fun syncPastDays(days: Int) = withContext(Dispatchers.IO) {
        val today = LocalDate.now(ZoneId.systemDefault())

        for (daysAgo in 1..days) {
            val targetDate = today.minusDays(daysAgo.toLong())
            val dateStr = targetDate.format(dateFormatter)

            // Skip if already synced
            if (dao.hasDataForDate(dateStr) > 0) {
                continue
            }

            syncDateFromSystem(targetDate)
        }
    }

    /**
     * Get per-app usage breakdown for a specific date from local database.
     */
    suspend fun getLocalUsageForDate(date: LocalDate): List<DailyUsageEntity> = withContext(Dispatchers.IO) {
        val dateStr = date.format(dateFormatter)
        dao.getUsageForDate(dateStr)
    }

    /**
     * Get summaries for a date range from local database.
     */
    suspend fun getLocalSummariesForRange(
        startDate: LocalDate,
        endDate: LocalDate
    ): List<DailySummaryEntity> = withContext(Dispatchers.IO) {
        val startStr = startDate.format(dateFormatter)
        val endStr = endDate.format(dateFormatter)
        dao.getSummariesForDateRange(startStr, endStr)
    }

    /**
     * Clean up old data beyond retention period.
     * Should be called periodically (e.g., weekly) to prevent unbounded growth.
     */
    suspend fun cleanupOldData() = withContext(Dispatchers.IO) {
        val cutoffDate = LocalDate.now(ZoneId.systemDefault()).minusDays(MAX_RETENTION_DAYS.toLong())
        val cutoffStr = cutoffDate.format(dateFormatter)

        dao.deleteDataBefore(cutoffStr)
        dao.deleteSummariesBefore(cutoffStr)
        Log.d(TAG, "Cleaned up data before $cutoffStr")
    }

    /**
     * Check if local database has data for a specific number of days.
     * Useful for determining if we have enough historical data.
     */
    suspend fun hasHistoricalData(days: Int): Boolean = withContext(Dispatchers.IO) {
        val oldestDate = dao.getOldestDate() ?: return@withContext false
        val oldest = LocalDate.parse(oldestDate, dateFormatter)
        val threshold = LocalDate.now(ZoneId.systemDefault()).minusDays(days.toLong())
        oldest <= threshold
    }
}
