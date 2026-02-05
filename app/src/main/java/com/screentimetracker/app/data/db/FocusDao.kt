package com.screentimetracker.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.screentimetracker.app.data.db.entity.FocusBlockLogEntity
import com.screentimetracker.app.data.db.entity.FocusBlockedAppEntity
import com.screentimetracker.app.data.db.entity.FocusOverrideEntity
import com.screentimetracker.app.data.db.entity.FocusScheduleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Focus Mode data.
 * Provides methods to manage schedules, blocked apps, overrides, and block logs.
 */
@Dao
interface FocusDao {

    // ========== Schedule Operations ==========

    /**
     * Insert a new schedule. Returns the generated ID.
     */
    @Insert
    suspend fun insertSchedule(schedule: FocusScheduleEntity): Long

    /**
     * Update an existing schedule.
     */
    @Update
    suspend fun updateSchedule(schedule: FocusScheduleEntity)

    /**
     * Delete a schedule by ID. Cascades to blocked apps.
     */
    @Query("DELETE FROM focus_schedule WHERE id = :scheduleId")
    suspend fun deleteSchedule(scheduleId: Long)

    /**
     * Get a schedule by ID.
     */
    @Query("SELECT * FROM focus_schedule WHERE id = :scheduleId")
    suspend fun getScheduleById(scheduleId: Long): FocusScheduleEntity?

    /**
     * Get all schedules.
     */
    @Query("SELECT * FROM focus_schedule ORDER BY name ASC")
    suspend fun getAllSchedules(): List<FocusScheduleEntity>

    /**
     * Observe all schedules as a Flow for reactive UI updates.
     */
    @Query("SELECT * FROM focus_schedule ORDER BY name ASC")
    fun observeAllSchedules(): Flow<List<FocusScheduleEntity>>

    /**
     * Get all enabled schedules.
     */
    @Query("SELECT * FROM focus_schedule WHERE isEnabled = 1 ORDER BY name ASC")
    suspend fun getEnabledSchedules(): List<FocusScheduleEntity>

    /**
     * Toggle a schedule's enabled state.
     */
    @Query("UPDATE focus_schedule SET isEnabled = :enabled, updatedAt = :updatedAt WHERE id = :scheduleId")
    suspend fun setScheduleEnabled(scheduleId: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    // ========== Blocked App Operations ==========

    /**
     * Insert a single blocked app entry.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedApp(blockedApp: FocusBlockedAppEntity)

    /**
     * Insert multiple blocked apps at once.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedApps(blockedApps: List<FocusBlockedAppEntity>)

    /**
     * Get all blocked apps for a schedule.
     */
    @Query("SELECT * FROM focus_blocked_app WHERE scheduleId = :scheduleId ORDER BY packageName ASC")
    suspend fun getBlockedAppsForSchedule(scheduleId: Long): List<FocusBlockedAppEntity>

    /**
     * Observe blocked apps for a schedule.
     */
    @Query("SELECT * FROM focus_blocked_app WHERE scheduleId = :scheduleId ORDER BY packageName ASC")
    fun observeBlockedAppsForSchedule(scheduleId: Long): Flow<List<FocusBlockedAppEntity>>

    /**
     * Delete a specific blocked app from a schedule.
     */
    @Query("DELETE FROM focus_blocked_app WHERE scheduleId = :scheduleId AND packageName = :packageName")
    suspend fun deleteBlockedApp(scheduleId: Long, packageName: String)

    /**
     * Delete all blocked apps for a schedule.
     */
    @Query("DELETE FROM focus_blocked_app WHERE scheduleId = :scheduleId")
    suspend fun deleteBlockedAppsForSchedule(scheduleId: Long)

    /**
     * Check if a package is blocked by any enabled schedule.
     */
    @Query("""
        SELECT COUNT(*) > 0 FROM focus_blocked_app ba
        INNER JOIN focus_schedule s ON ba.scheduleId = s.id
        WHERE ba.packageName = :packageName AND s.isEnabled = 1
    """)
    suspend fun isPackageBlockedByAnySchedule(packageName: String): Boolean

    /**
     * Get all schedule IDs that block a specific package.
     */
    @Query("""
        SELECT s.* FROM focus_schedule s
        INNER JOIN focus_blocked_app ba ON s.id = ba.scheduleId
        WHERE ba.packageName = :packageName AND s.isEnabled = 1
    """)
    suspend fun getSchedulesBlockingPackage(packageName: String): List<FocusScheduleEntity>

    // ========== Override Operations ==========

    /**
     * Insert a new override.
     */
    @Insert
    suspend fun insertOverride(override: FocusOverrideEntity): Long

    /**
     * Get active (non-expired) override for a package.
     */
    @Query("""
        SELECT * FROM focus_override
        WHERE packageName = :packageName
        AND expiresAt > :currentTime
        ORDER BY expiresAt DESC
        LIMIT 1
    """)
    suspend fun getActiveOverrideForPackage(packageName: String, currentTime: Long = System.currentTimeMillis()): FocusOverrideEntity?

    /**
     * Get all active overrides.
     */
    @Query("SELECT * FROM focus_override WHERE expiresAt > :currentTime")
    suspend fun getActiveOverrides(currentTime: Long = System.currentTimeMillis()): List<FocusOverrideEntity>

    /**
     * Delete expired overrides.
     */
    @Query("DELETE FROM focus_override WHERE expiresAt <= :currentTime")
    suspend fun deleteExpiredOverrides(currentTime: Long = System.currentTimeMillis())

    /**
     * Delete a specific override by ID.
     */
    @Query("DELETE FROM focus_override WHERE id = :overrideId")
    suspend fun deleteOverride(overrideId: Long)

    /**
     * Delete all overrides for a package.
     */
    @Query("DELETE FROM focus_override WHERE packageName = :packageName")
    suspend fun deleteOverridesForPackage(packageName: String)

    /**
     * Consume an "allow once" override by setting its expiry to now.
     * This effectively uses up the one-time allowance.
     */
    @Query("UPDATE focus_override SET expiresAt = :currentTime WHERE id = :overrideId AND overrideType = 'ALLOW_ONCE'")
    suspend fun consumeAllowOnceOverride(overrideId: Long, currentTime: Long = System.currentTimeMillis())

    // ========== Block Log Operations ==========

    /**
     * Insert a block log entry.
     */
    @Insert
    suspend fun insertBlockLog(log: FocusBlockLogEntity): Long

    /**
     * Get block count for a specific date.
     */
    @Query("SELECT COUNT(*) FROM focus_block_log WHERE date = :date")
    suspend fun getBlockCountForDate(date: String): Int

    /**
     * Get block count grouped by action for a date.
     */
    @Query("SELECT userAction, COUNT(*) as count FROM focus_block_log WHERE date = :date GROUP BY userAction")
    suspend fun getBlockCountsByActionForDate(date: String): List<ActionCount>

    /**
     * Get recent block logs for a package.
     */
    @Query("SELECT * FROM focus_block_log WHERE packageName = :packageName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentBlockLogsForPackage(packageName: String, limit: Int = 50): List<FocusBlockLogEntity>

    /**
     * Get all block logs for a date.
     */
    @Query("SELECT * FROM focus_block_log WHERE date = :date ORDER BY timestamp DESC")
    suspend fun getBlockLogsForDate(date: String): List<FocusBlockLogEntity>

    /**
     * Delete block logs older than a specific timestamp.
     */
    @Query("DELETE FROM focus_block_log WHERE timestamp < :beforeTimestamp")
    suspend fun deleteBlockLogsBefore(beforeTimestamp: Long)

    /**
     * Get total block count (for stats).
     */
    @Query("SELECT COUNT(*) FROM focus_block_log")
    suspend fun getTotalBlockCount(): Int

    /**
     * Get count of "GO_BACK" actions (successful blocks) for a date range.
     */
    @Query("""
        SELECT COUNT(*) FROM focus_block_log
        WHERE date >= :startDate AND date <= :endDate
        AND userAction = 'GO_BACK'
    """)
    suspend fun getSuccessfulBlockCount(startDate: String, endDate: String): Int

    // ========== Helper Data Classes ==========

    data class ActionCount(
        val userAction: String,
        val count: Int
    )
}
