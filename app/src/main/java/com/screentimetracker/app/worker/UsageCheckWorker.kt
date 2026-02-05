package com.screentimetracker.app.worker

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.work.*
import com.screentimetracker.app.data.model.SessionInfo
import com.screentimetracker.app.data.prefs.UserPrefs
import com.screentimetracker.app.data.repository.UsageRepository
import com.screentimetracker.app.data.usage.UsageSessionRepository
import com.screentimetracker.app.notifications.NotificationHelper
import com.screentimetracker.app.ui.main.InsightGenerator
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that periodically checks usage against limits
 * and sends notifications when thresholds are crossed.
 */
class UsageCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "UsageCheckWorker"
        private const val WORK_NAME = "usage_check_work"
        private const val WORK_NAME_IMMEDIATE = "usage_check_now"

        // Fast check interval in minutes (5 min is more battery-friendly than 2 min)
        private const val FAST_CHECK_DELAY_MINUTES = 5L

        // Limit threshold is always 100%
        private const val LIMIT_THRESHOLD = 1.00

        /**
         * Schedules the periodic usage check worker.
         * Runs every 15 minutes.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<UsageCheckWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.MINUTES) // Small delay on first run
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already scheduled
                workRequest
            )

            Log.d(TAG, "Scheduled periodic usage check worker")
        }

        /**
         * Cancels the periodic usage check worker.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic usage check worker")
        }

        /**
         * Runs an immediate one-time check (e.g., when app opens).
         * Uses unique work to avoid duplicate checks.
         */
        fun runImmediateCheck(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<UsageCheckWorker>()
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.d(TAG, "Enqueued immediate usage check")
        }

        /**
         * Schedules a fast check to run after the specified delay.
         * Uses AlarmManager with exact alarms to ensure reliable execution during Doze mode.
         */
        fun scheduleFastCheck(context: Context, delayMinutes: Long = FAST_CHECK_DELAY_MINUTES) {
            FastCheckAlarmScheduler.schedule(context, delayMinutes)
        }

        /**
         * Cancels any pending fast check alarm.
         */
        fun cancelFastChecks(context: Context) {
            FastCheckAlarmScheduler.cancel(context)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Running usage check...")

        val userPrefs = UserPrefs(applicationContext)

        // Reset notification state if it's a new day
        userPrefs.resetNotificationStateIfNewDay()

        // Batch read all preferences in ONE DataStore call for efficiency
        val prefs = userPrefs.prefsSnapshot.first()

        // Sync yesterday's usage data to local database if missing
        // This ensures we have persistent historical data
        syncYesterdayUsageIfNeeded(prefs.trackedPackages)

        // Check if notifications are enabled at all
        val warningEnabled = prefs.warningNotificationsEnabled
        val limitEnabled = prefs.limitNotificationsEnabled

        if (!warningEnabled && !limitEnabled) {
            Log.d(TAG, "All notifications disabled, skipping check")
            // Exit fast mode and cancel fast checks when notifications disabled
            userPrefs.setFastCheckModeActive(false)
            cancelFastChecks(applicationContext)
            return Result.success()
        }

        // Check if currently snoozed (using snapshot data)
        if (prefs.isSnoozed()) {
            Log.d(TAG, "Notifications snoozed, skipping check")
            // Don't schedule fast checks during snooze
            return Result.success()
        }

        // Get current usage
        val usageStatsManager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return Result.failure()

        // Use values from the batch read
        val trackedPackages = prefs.trackedPackages
        val dailyLimitMillis = prefs.dailyLimitMillis
        val warningPercent = prefs.warningPercent
        val warningThreshold = warningPercent / 100.0

        // Calculate today's usage
        val startTime = UsageSessionRepository.getStartOfTodayMillis()
        val endTime = UsageSessionRepository.getCurrentTimeMillis()

        // Query sessions ONCE for both usage calculation and insight generation
        // This avoids expensive O(n*m) session reconstruction happening twice
        val cachedSessions = UsageSessionRepository.getSessionsForWindow(
            usageStatsManager = usageStatsManager,
            windowStart = startTime,
            windowEnd = endTime,
            trackedPackages = trackedPackages,
            appNameResolver = { packageName ->
                UserPrefs.ALL_TRACKABLE_APPS[packageName] ?: packageName
            }
        )

        val currentUsageMillis = calculateTodayUsage(
            usageStatsManager,
            trackedPackages,
            startTime,
            endTime,
            cachedSessions
        )

        val usagePercentage = currentUsageMillis.toDouble() / dailyLimitMillis

        Log.d(TAG, "Usage: ${currentUsageMillis}ms / ${dailyLimitMillis}ms = ${(usagePercentage * 100).toInt()}%")

        // Get optional insight text for notification using cached sessions
        val insightText = generateInsightForNotification(
            cachedSessions,
            startTime,
            endTime,
            currentUsageMillis
        )

        // Check thresholds and send notifications
        val today = LocalDate.now(ZoneId.systemDefault())
        var limitNotificationSent = false

        when {
            usagePercentage >= LIMIT_THRESHOLD && limitEnabled -> {
                if (userPrefs.shouldNotify(UserPrefs.NotificationLevel.LIMIT)) {
                    NotificationHelper.showLimitNotification(
                        context = applicationContext,
                        currentUsageMillis = currentUsageMillis,
                        limitMillis = dailyLimitMillis,
                        insightText = insightText
                    )
                    userPrefs.setNotificationState(today, UserPrefs.NotificationLevel.LIMIT)
                    Log.d(TAG, "Sent limit notification")
                    limitNotificationSent = true
                }
            }
            usagePercentage >= warningThreshold && warningEnabled -> {
                if (userPrefs.shouldNotify(UserPrefs.NotificationLevel.WARN)) {
                    NotificationHelper.showWarningNotification(
                        context = applicationContext,
                        currentUsageMillis = currentUsageMillis,
                        limitMillis = dailyLimitMillis,
                        insightText = insightText
                    )
                    userPrefs.setNotificationState(today, UserPrefs.NotificationLevel.WARN)
                    Log.d(TAG, "Sent warning notification")
                }
            }
        }

        // Handle fast check mode entry/exit
        handleFastCheckMode(userPrefs, usagePercentage, limitNotificationSent, dailyLimitMillis, warningThreshold)

        return Result.success()
    }

    /**
     * Handles fast check mode entry and exit based on current usage percentage.
     *
     * Entry: 15 minutes before warning threshold and limit notification not yet sent
     * Exit: 20 minutes before warning threshold (5 min hysteresis) OR limit notification sent
     *       OR day change (handled by isFastCheckModeActive)
     */
    private suspend fun handleFastCheckMode(
        userPrefs: UserPrefs,
        usagePercentage: Double,
        limitNotificationSent: Boolean,
        dailyLimitMillis: Long,
        warningThreshold: Double
    ) {
        val currentlyInFastMode = userPrefs.isFastCheckModeActive()

        // Calculate time-based thresholds
        val leadTimeMillis = UserPrefs.FAST_CHECK_LEAD_TIME_MINUTES * 60 * 1000L
        val exitBufferMillis = UserPrefs.FAST_CHECK_EXIT_BUFFER_MINUTES * 60 * 1000L

        // Entry: 15 minutes before warning threshold
        val entryThreshold = maxOf(0.0, warningThreshold - (leadTimeMillis.toDouble() / dailyLimitMillis))
        // Exit: 20 minutes before warning threshold (5 min hysteresis)
        val exitThreshold = maxOf(0.0, warningThreshold - (exitBufferMillis.toDouble() / dailyLimitMillis))

        // Check if limit notification was already sent today
        val lastLevel = userPrefs.getLastNotifiedLevel()
        val lastDate = userPrefs.getLastNotifiedDate()
        val today = LocalDate.now(ZoneId.systemDefault())
        val limitAlreadySentToday = lastDate == today && lastLevel == UserPrefs.NotificationLevel.LIMIT

        Log.d(TAG, "FastCheck eval: usage=${(usagePercentage * 100).toInt()}%, " +
                "entryThreshold=${(entryThreshold * 100).toInt()}%, exitThreshold=${(exitThreshold * 100).toInt()}%, " +
                "currentlyInFast=$currentlyInFastMode, limitSentNow=$limitNotificationSent, " +
                "limitSentToday=$limitAlreadySentToday, lastLevel=$lastLevel, lastDate=$lastDate")

        // Determine if we should be in fast mode
        val shouldBeInFastMode = when {
            limitNotificationSent || limitAlreadySentToday -> false  // Exit: limit reached
            usagePercentage < exitThreshold -> false                  // Exit: below exit threshold
            usagePercentage >= entryThreshold -> true                 // Enter: at/above entry threshold
            else -> currentlyInFastMode                               // Hysteresis: stay in current state
        }

        Log.d(TAG, "FastCheck decision: shouldBeInFastMode=$shouldBeInFastMode")

        // Handle state transitions
        when {
            shouldBeInFastMode && !currentlyInFastMode -> {
                Log.d(TAG, "Entering fast check mode (usage=${(usagePercentage * 100).toInt()}%)")
                userPrefs.setFastCheckModeActive(true)
                scheduleFastCheck(applicationContext)
            }
            shouldBeInFastMode && currentlyInFastMode -> {
                // Continue fast mode - schedule next check
                scheduleFastCheck(applicationContext)
            }
            !shouldBeInFastMode && currentlyInFastMode -> {
                Log.d(TAG, "Exiting fast check mode (usage=${(usagePercentage * 100).toInt()}%)")
                userPrefs.setFastCheckModeActive(false)
                cancelFastChecks(applicationContext)
            }
            // !shouldBeInFastMode && !currentlyInFastMode -> no action needed
        }
    }

    /**
     * Calculates total usage for today using both UsageStats and pre-computed session data.
     *
     * @param cachedSessions Pre-computed sessions to avoid duplicate O(n*m) reconstruction
     */
    private fun calculateTodayUsage(
        usageStatsManager: UsageStatsManager,
        trackedPackages: Set<String>,
        startTime: Long,
        endTime: Long,
        cachedSessions: List<SessionInfo>
    ): Long {
        // Query UsageStats for per-app breakdown
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        val usageStatsTotal = usageStatsList
            ?.filter { stats ->
                trackedPackages.contains(stats.packageName) && stats.totalTimeInForeground > 0
            }
            ?.sumOf { it.totalTimeInForeground }
            ?: 0L

        // Use pre-computed sessions for session-derived total
        val sessionTotal = UsageSessionRepository.computeTotalOverlapFromSessions(
            cachedSessions, startTime, endTime
        )

        // Use the larger of the two for accuracy
        return maxOf(usageStatsTotal, sessionTotal)
    }

    /**
     * Generates a brief insight text for the notification body.
     * Returns null if no meaningful insight is available.
     *
     * @param cachedSessions Pre-computed sessions to avoid duplicate O(n*m) reconstruction
     */
    private fun generateInsightForNotification(
        cachedSessions: List<SessionInfo>,
        startTime: Long,
        endTime: Long,
        totalUsageMillis: Long
    ): String? {
        if (cachedSessions.size < 2) return null

        val top3Sessions = cachedSessions
            .sortedByDescending { it.overlapDuration(startTime, endTime) }
            .take(3)

        val fullInsight = InsightGenerator.generateInsight(
            allSessions = cachedSessions,
            top3Sessions = top3Sessions,
            totalUsageMillis = totalUsageMillis,
            windowStart = startTime,
            windowEnd = endTime
        )

        // Extract just the first sentence for the notification
        return fullInsight?.split(".")?.firstOrNull()?.trim()?.let { "$it." }
    }

    /**
     * Syncs yesterday's usage data to local database if not already persisted.
     * This runs as part of the periodic check to ensure data integrity.
     *
     * The sync is lightweight: it only runs if data is missing and uses
     * the existing UsageRepository.syncYesterdayIfMissing() method.
     */
    private suspend fun syncYesterdayUsageIfNeeded(trackedPackages: Set<String>) {
        try {
            val usageStatsManager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return

            val repository = UsageRepository(
                context = applicationContext,
                usageStatsManager = usageStatsManager,
                trackedPackages = trackedPackages
            )

            repository.syncYesterdayIfMissing()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync yesterday's usage data", e)
            // Non-fatal: continue with notification check
        }
    }
}
