package com.screentimetracker.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.screentimetracker.app.data.db.entity.DailySummaryEntity
import com.screentimetracker.app.data.db.entity.DailyUsageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for daily usage data.
 * Provides methods to persist and query usage data from the local Room database.
 */
@Dao
interface DailyUsageDao {

    // ========== Daily Usage (per-app) Operations ==========

    /**
     * Insert or update a single daily usage entry.
     * Uses REPLACE strategy to overwrite existing data for the same date/package.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyUsage(usage: DailyUsageEntity)

    /**
     * Insert or update multiple daily usage entries.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllDailyUsage(usages: List<DailyUsageEntity>)

    /**
     * Get all usage entries for a specific date.
     */
    @Query("SELECT * FROM daily_usage WHERE date = :date ORDER BY totalMillis DESC")
    suspend fun getUsageForDate(date: String): List<DailyUsageEntity>

    /**
     * Get usage entries for a date range (inclusive).
     * Returns entries sorted by date descending, then by usage descending.
     */
    @Query("SELECT * FROM daily_usage WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC, totalMillis DESC")
    suspend fun getUsageForDateRange(startDate: String, endDate: String): List<DailyUsageEntity>

    /**
     * Get usage entries for a specific package within a date range.
     */
    @Query("SELECT * FROM daily_usage WHERE packageName = :packageName AND date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getUsageForPackageInRange(
        packageName: String,
        startDate: String,
        endDate: String
    ): List<DailyUsageEntity>

    /**
     * Check if we have data for a specific date.
     */
    @Query("SELECT COUNT(*) FROM daily_usage WHERE date = :date")
    suspend fun hasDataForDate(date: String): Int

    /**
     * Get the oldest date we have data for.
     */
    @Query("SELECT MIN(date) FROM daily_usage")
    suspend fun getOldestDate(): String?

    /**
     * Get the most recent date we have data for.
     */
    @Query("SELECT MAX(date) FROM daily_usage")
    suspend fun getMostRecentDate(): String?

    /**
     * Delete data older than a specific date.
     * Useful for cleanup to prevent unbounded storage growth.
     */
    @Query("DELETE FROM daily_usage WHERE date < :beforeDate")
    suspend fun deleteDataBefore(beforeDate: String)

    // ========== Daily Summary Operations ==========

    /**
     * Insert or update a daily summary.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailySummary(summary: DailySummaryEntity)

    /**
     * Insert or update multiple daily summaries.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllDailySummaries(summaries: List<DailySummaryEntity>)

    /**
     * Get summary for a specific date.
     */
    @Query("SELECT * FROM daily_summary WHERE date = :date")
    suspend fun getSummaryForDate(date: String): DailySummaryEntity?

    /**
     * Get summaries for a date range, ordered by date ascending (for charts).
     */
    @Query("SELECT * FROM daily_summary WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC")
    suspend fun getSummariesForDateRange(startDate: String, endDate: String): List<DailySummaryEntity>

    /**
     * Get summaries as a Flow for reactive updates.
     */
    @Query("SELECT * FROM daily_summary WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC")
    fun observeSummariesForDateRange(startDate: String, endDate: String): Flow<List<DailySummaryEntity>>

    /**
     * Delete summary data older than a specific date.
     */
    @Query("DELETE FROM daily_summary WHERE date < :beforeDate")
    suspend fun deleteSummariesBefore(beforeDate: String)

    // ========== Utility Queries ==========

    /**
     * Get total usage for a date from per-app data.
     * Useful as a fallback if summary is missing.
     */
    @Query("SELECT COALESCE(SUM(totalMillis), 0) FROM daily_usage WHERE date = :date")
    suspend fun getTotalUsageForDate(date: String): Long

    /**
     * Get dates that are missing summaries but have usage data.
     * Useful for sync operations.
     */
    @Query("""
        SELECT DISTINCT du.date
        FROM daily_usage du
        LEFT JOIN daily_summary ds ON du.date = ds.date
        WHERE ds.date IS NULL
        ORDER BY du.date DESC
    """)
    suspend fun getDatesWithMissingSummaries(): List<String>
}
