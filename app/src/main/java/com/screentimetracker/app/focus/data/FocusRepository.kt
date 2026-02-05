package com.screentimetracker.app.focus.data

import com.screentimetracker.app.data.db.FocusDao
import com.screentimetracker.app.data.db.entity.FocusBlockLogEntity
import com.screentimetracker.app.data.db.entity.FocusBlockedAppEntity
import com.screentimetracker.app.data.db.entity.FocusOverrideEntity
import com.screentimetracker.app.data.db.entity.FocusScheduleEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

/**
 * Repository for Focus Mode data.
 * Wraps FocusDao with business logic for schedule management, overrides, and logging.
 */
class FocusRepository(private val focusDao: FocusDao) {

    // ========== Schedule Operations ==========

    /**
     * Create a new schedule with blocked apps atomically.
     * @return The ID of the created schedule
     */
    suspend fun createSchedule(
        name: String,
        daysOfWeekMask: Int,
        startTimeMinutes: Int,
        endTimeMinutes: Int,
        blockedPackages: List<String>,
        timezoneId: String = ZoneId.systemDefault().id
    ): Long {
        val now = System.currentTimeMillis()
        val schedule = FocusScheduleEntity(
            name = name,
            isEnabled = true,
            daysOfWeekMask = daysOfWeekMask,
            startTimeMinutes = startTimeMinutes,
            endTimeMinutes = endTimeMinutes,
            timezoneId = timezoneId,
            createdAt = now,
            updatedAt = now
        )

        val scheduleId = focusDao.insertSchedule(schedule)

        if (blockedPackages.isNotEmpty()) {
            val blockedApps = blockedPackages.map { packageName ->
                FocusBlockedAppEntity(
                    scheduleId = scheduleId,
                    packageName = packageName,
                    addedAt = now
                )
            }
            focusDao.insertBlockedApps(blockedApps)
        }

        return scheduleId
    }

    /**
     * Update an existing schedule.
     */
    suspend fun updateSchedule(schedule: FocusScheduleEntity) {
        focusDao.updateSchedule(schedule.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * Update schedule with new blocked apps list.
     * Replaces all existing blocked apps.
     */
    suspend fun updateScheduleWithBlockedApps(
        schedule: FocusScheduleEntity,
        blockedPackages: List<String>
    ) {
        val now = System.currentTimeMillis()

        // Update schedule
        focusDao.updateSchedule(schedule.copy(updatedAt = now))

        // Replace blocked apps
        focusDao.deleteBlockedAppsForSchedule(schedule.id)
        if (blockedPackages.isNotEmpty()) {
            val blockedApps = blockedPackages.map { packageName ->
                FocusBlockedAppEntity(
                    scheduleId = schedule.id,
                    packageName = packageName,
                    addedAt = now
                )
            }
            focusDao.insertBlockedApps(blockedApps)
        }
    }

    /**
     * Delete a schedule by ID.
     */
    suspend fun deleteSchedule(scheduleId: Long) {
        focusDao.deleteSchedule(scheduleId)
    }

    /**
     * Get a schedule by ID.
     */
    suspend fun getScheduleById(scheduleId: Long): FocusScheduleEntity? {
        return focusDao.getScheduleById(scheduleId)
    }

    /**
     * Get all schedules.
     */
    suspend fun getAllSchedules(): List<FocusScheduleEntity> {
        return focusDao.getAllSchedules()
    }

    /**
     * Observe all schedules as a Flow.
     */
    fun observeAllSchedules(): Flow<List<FocusScheduleEntity>> {
        return focusDao.observeAllSchedules()
    }

    /**
     * Get all enabled schedules.
     */
    suspend fun getEnabledSchedules(): List<FocusScheduleEntity> {
        return focusDao.getEnabledSchedules()
    }

    /**
     * Toggle schedule enabled state.
     */
    suspend fun setScheduleEnabled(scheduleId: Long, enabled: Boolean) {
        focusDao.setScheduleEnabled(scheduleId, enabled)
    }

    // ========== Blocked App Operations ==========

    /**
     * Get blocked apps for a schedule.
     */
    suspend fun getBlockedAppsForSchedule(scheduleId: Long): List<FocusBlockedAppEntity> {
        return focusDao.getBlockedAppsForSchedule(scheduleId)
    }

    /**
     * Get package names blocked by a schedule.
     */
    suspend fun getBlockedPackagesForSchedule(scheduleId: Long): List<String> {
        return focusDao.getBlockedAppsForSchedule(scheduleId).map { it.packageName }
    }

    /**
     * Observe blocked apps for a schedule.
     */
    fun observeBlockedAppsForSchedule(scheduleId: Long): Flow<List<FocusBlockedAppEntity>> {
        return focusDao.observeBlockedAppsForSchedule(scheduleId)
    }

    /**
     * Add a blocked app to a schedule.
     */
    suspend fun addBlockedApp(scheduleId: Long, packageName: String) {
        focusDao.insertBlockedApp(
            FocusBlockedAppEntity(
                scheduleId = scheduleId,
                packageName = packageName
            )
        )
    }

    /**
     * Remove a blocked app from a schedule.
     */
    suspend fun removeBlockedApp(scheduleId: Long, packageName: String) {
        focusDao.deleteBlockedApp(scheduleId, packageName)
    }

    /**
     * Check if a package is blocked by any enabled schedule.
     */
    suspend fun isPackageBlockedByAnySchedule(packageName: String): Boolean {
        return focusDao.isPackageBlockedByAnySchedule(packageName)
    }

    /**
     * Get all enabled schedules that block a package.
     */
    suspend fun getSchedulesBlockingPackage(packageName: String): List<FocusScheduleEntity> {
        return focusDao.getSchedulesBlockingPackage(packageName)
    }

    // ========== Override Operations ==========

    /**
     * Grant a temporary override for a package.
     * @param overrideType One of: TYPE_ALLOW_5_MIN, TYPE_ALLOW_15_MIN, TYPE_ALLOW_ONCE
     * @param scheduleId Optional specific schedule to apply override to (null = all schedules)
     * @return The ID of the created override
     */
    suspend fun grantOverride(
        packageName: String,
        overrideType: String,
        scheduleId: Long? = null
    ): Long {
        val now = System.currentTimeMillis()
        val expiresAt = when (overrideType) {
            FocusOverrideEntity.TYPE_ALLOW_5_MIN -> now + FocusOverrideEntity.DURATION_5_MIN_MS
            FocusOverrideEntity.TYPE_ALLOW_15_MIN -> now + FocusOverrideEntity.DURATION_15_MIN_MS
            FocusOverrideEntity.TYPE_ALLOW_ONCE -> now + FocusOverrideEntity.DURATION_ALLOW_ONCE_MS
            else -> throw IllegalArgumentException("Invalid override type: $overrideType")
        }

        val override = FocusOverrideEntity(
            packageName = packageName,
            scheduleId = scheduleId,
            overrideType = overrideType,
            expiresAt = expiresAt,
            createdAt = now
        )

        return focusDao.insertOverride(override)
    }

    /**
     * Check if a package has an active override.
     */
    suspend fun hasActiveOverride(packageName: String): Boolean {
        return focusDao.getActiveOverrideForPackage(packageName) != null
    }

    /**
     * Get active override for a package.
     */
    suspend fun getActiveOverrideForPackage(packageName: String): FocusOverrideEntity? {
        return focusDao.getActiveOverrideForPackage(packageName)
    }

    /**
     * Consume an "allow once" override (marks it as used).
     */
    suspend fun consumeAllowOnceOverride(overrideId: Long) {
        focusDao.consumeAllowOnceOverride(overrideId)
    }

    /**
     * Clean up expired overrides.
     */
    suspend fun cleanupExpiredOverrides() {
        focusDao.deleteExpiredOverrides()
    }

    /**
     * Delete all overrides for a package.
     */
    suspend fun deleteOverridesForPackage(packageName: String) {
        focusDao.deleteOverridesForPackage(packageName)
    }

    /**
     * Pause a schedule for the rest of the day by creating overrides for all its blocked apps.
     * @param scheduleId The schedule to pause
     * @return The number of apps that were given overrides
     */
    suspend fun pauseScheduleForToday(scheduleId: Long): Int {
        val blockedPackages = getBlockedPackagesForSchedule(scheduleId)
        val now = System.currentTimeMillis()
        val midnight = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        blockedPackages.forEach { packageName ->
            focusDao.insertOverride(FocusOverrideEntity(
                packageName = packageName,
                scheduleId = scheduleId,
                overrideType = FocusOverrideEntity.TYPE_END_TODAY,
                expiresAt = midnight,
                createdAt = now
            ))
        }
        return blockedPackages.size
    }

    // ========== Block Log Operations ==========

    /**
     * Record a block event.
     * @param userAction One of: ACTION_GO_BACK, ACTION_ALLOW_5_MIN, ACTION_ALLOW_15_MIN, ACTION_ALLOW_ONCE
     */
    suspend fun logBlock(
        packageName: String,
        scheduleId: Long,
        userAction: String
    ): Long {
        val now = System.currentTimeMillis()
        val date = LocalDate.now(ZoneId.systemDefault()).toString()

        val log = FocusBlockLogEntity(
            packageName = packageName,
            scheduleId = scheduleId,
            userAction = userAction,
            timestamp = now,
            date = date
        )

        return focusDao.insertBlockLog(log)
    }

    /**
     * Get block count for today.
     */
    suspend fun getTodayBlockCount(): Int {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        return focusDao.getBlockCountForDate(today)
    }

    /**
     * Get block counts by action for today.
     */
    suspend fun getTodayBlockCountsByAction(): Map<String, Int> {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        return focusDao.getBlockCountsByActionForDate(today)
            .associate { it.userAction to it.count }
    }

    /**
     * Get successful block count (GO_BACK actions) for a date range.
     */
    suspend fun getSuccessfulBlockCount(startDate: String, endDate: String): Int {
        return focusDao.getSuccessfulBlockCount(startDate, endDate)
    }

    /**
     * Get block logs for today.
     */
    suspend fun getTodayBlockLogs(): List<FocusBlockLogEntity> {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        return focusDao.getBlockLogsForDate(today)
    }

    /**
     * Delete old block logs (for storage cleanup).
     * @param daysToKeep Number of days of logs to retain
     */
    suspend fun cleanupOldBlockLogs(daysToKeep: Int = 30) {
        val cutoffDate = LocalDate.now(ZoneId.systemDefault()).minusDays(daysToKeep.toLong())
        val cutoffTimestamp = cutoffDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        focusDao.deleteBlockLogsBefore(cutoffTimestamp)
    }

    /**
     * Get total block count across all time.
     */
    suspend fun getTotalBlockCount(): Int {
        return focusDao.getTotalBlockCount()
    }

    // ========== Helper Constants ==========

    companion object {
        // Days of week bitmask values
        const val DAY_SUNDAY = 1
        const val DAY_MONDAY = 2
        const val DAY_TUESDAY = 4
        const val DAY_WEDNESDAY = 8
        const val DAY_THURSDAY = 16
        const val DAY_FRIDAY = 32
        const val DAY_SATURDAY = 64

        const val WEEKDAYS = DAY_MONDAY or DAY_TUESDAY or DAY_WEDNESDAY or DAY_THURSDAY or DAY_FRIDAY // 62
        const val WEEKENDS = DAY_SATURDAY or DAY_SUNDAY // 65
        const val ALL_DAYS = WEEKDAYS or WEEKENDS // 127

        /**
         * Check if a day is set in the mask.
         * @param dayOfWeek 1 = Sunday, 7 = Saturday (Calendar.DAY_OF_WEEK)
         */
        fun isDayInMask(daysOfWeekMask: Int, dayOfWeek: Int): Boolean {
            val dayBit = 1 shl (dayOfWeek - 1)
            return (daysOfWeekMask and dayBit) != 0
        }

        /**
         * Convert time to minutes from midnight.
         */
        fun toMinutesFromMidnight(hour: Int, minute: Int): Int {
            return hour * 60 + minute
        }

        /**
         * Convert minutes from midnight to hour:minute pair.
         */
        fun fromMinutesFromMidnight(minutes: Int): Pair<Int, Int> {
            return Pair(minutes / 60, minutes % 60)
        }
    }
}
