package com.screentimetracker.app.focus

import com.screentimetracker.app.data.db.entity.FocusScheduleEntity
import com.screentimetracker.app.focus.data.FocusPrefs
import com.screentimetracker.app.focus.data.FocusRepository
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Result of evaluating whether a package should be blocked.
 */
sealed class EvaluationResult {
    /**
     * Package is allowed to open.
     */
    data class Allowed(val reason: AllowReason) : EvaluationResult()

    /**
     * Package should be blocked.
     * @param packageName Package name that is being blocked
     * @param scheduleId ID of the schedule that triggered the block
     * @param scheduleName Name of the schedule for display
     * @param scheduleEndTime Epoch millis when the schedule window ends (for "X minutes remaining")
     */
    data class Blocked(
        val packageName: String,
        val scheduleId: Long,
        val scheduleName: String,
        val scheduleEndTime: Long?
    ) : EvaluationResult()
}

/**
 * Reasons why a package was allowed.
 */
enum class AllowReason {
    /** Global focus mode toggle is off */
    FOCUS_MODE_DISABLED,
    /** Focus mode is temporarily paused (Take a Break) */
    FOCUS_MODE_PAUSED,
    /** No schedule blocks this package */
    NO_MATCHING_SCHEDULE,
    /** Schedule exists but current day/time is outside its window */
    SCHEDULE_INACTIVE_TIME,
    /** An override is active for this package */
    OVERRIDE_ACTIVE
}

/**
 * Evaluates whether a package should be blocked at the current time.
 * Called by AccessibilityService on every app launch.
 *
 * Decision flow:
 * 1. Check global toggle → if off, allow
 * 2. Get schedules blocking this package
 * 3. Check if current day/time falls within any schedule's window
 * 4. Check for active override → if exists, allow
 * 5. Return block decision with context for UI
 */
class FocusScheduleEvaluator(
    private val focusPrefs: FocusPrefs,
    private val focusRepository: FocusRepository
) {
    companion object {
        private const val GLOBAL_STATE_CACHE_MS = 5_000L
        private const val SCHEDULE_CACHE_MS = 2_000L
    }

    // Cache for global enabled state
    @Volatile
    private var cachedGlobalEnabled: Boolean? = null
    @Volatile
    private var globalCacheTimestamp: Long = 0L

    // Per-package schedule cache
    private data class CachedSchedules(
        val schedules: List<FocusScheduleEntity>,
        val timestamp: Long
    )
    private val scheduleCache = mutableMapOf<String, CachedSchedules>()
    private val cacheLock = Any()

    /**
     * Evaluate whether a package should be blocked right now.
     *
     * @param packageName Package name to check
     * @return EvaluationResult indicating whether to block or allow
     */
    suspend fun evaluate(packageName: String): EvaluationResult {
        val now = System.currentTimeMillis()

        // Step 0: Check break state first - auto-clear if expired
        val breakEndTime = focusPrefs.getBreakEndTimeMillis()
        if (breakEndTime > 0) {
            if (now >= breakEndTime) {
                focusPrefs.clearBreak()
                invalidateCache()
            } else {
                return EvaluationResult.Allowed(AllowReason.FOCUS_MODE_PAUSED)
            }
        }

        // Step 1: Check global toggle
        val globalEnabled = getCachedGlobalEnabled(now)
        if (!globalEnabled) {
            return EvaluationResult.Allowed(AllowReason.FOCUS_MODE_DISABLED)
        }

        // Step 2: Get schedules blocking this package
        val schedules = getCachedSchedules(packageName, now)
        if (schedules.isEmpty()) {
            return EvaluationResult.Allowed(AllowReason.NO_MATCHING_SCHEDULE)
        }

        // Step 3: Find first active schedule
        val activeSchedule = schedules.firstOrNull { schedule ->
            isScheduleActiveNow(schedule, now)
        }

        if (activeSchedule == null) {
            return EvaluationResult.Allowed(AllowReason.SCHEDULE_INACTIVE_TIME)
        }

        // Step 4: Check for active override
        val override = focusRepository.getActiveOverrideForPackage(packageName)
        if (override != null) {
            // Override applies to all schedules (scheduleId == null) or this specific schedule
            if (override.scheduleId == null || override.scheduleId == activeSchedule.id) {
                return EvaluationResult.Allowed(AllowReason.OVERRIDE_ACTIVE)
            }
        }

        // Step 5: Block!
        return EvaluationResult.Blocked(
            packageName = packageName,
            scheduleId = activeSchedule.id,
            scheduleName = activeSchedule.name,
            scheduleEndTime = calculateScheduleEndTime(activeSchedule, now)
        )
    }

    /**
     * Check if a schedule is active at the given time.
     * Handles cross-midnight schedules correctly.
     */
    fun isScheduleActiveNow(schedule: FocusScheduleEntity, now: Long): Boolean {
        if (!schedule.isEnabled) {
            return false
        }

        val zone = try {
            ZoneId.of(schedule.timezoneId)
        } catch (e: Exception) {
            ZoneId.systemDefault()
        }

        val zonedNow = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
        val currentMinutes = zonedNow.hour * 60 + zonedNow.minute

        val startMinutes = schedule.startTimeMinutes
        val endMinutes = schedule.endTimeMinutes
        val isCrossMidnight = startMinutes > endMinutes

        if (!isCrossMidnight) {
            // Normal schedule (e.g., 9am-5pm)
            // Check if current day is active and time is within window
            val calendarDayOfWeek = convertToCalendarDayOfWeek(zonedNow.dayOfWeek.value)
            if (!FocusRepository.isDayInMask(schedule.daysOfWeekMask, calendarDayOfWeek)) {
                return false
            }
            return isTimeInWindow(currentMinutes, startMinutes, endMinutes)
        } else {
            // Cross-midnight schedule (e.g., 11pm-2am)
            // Two possible cases:
            // 1. We're in the "before midnight" portion (e.g., 11pm-midnight) - check today
            // 2. We're in the "after midnight" portion (e.g., midnight-2am) - check yesterday

            val calendarDayOfWeek = convertToCalendarDayOfWeek(zonedNow.dayOfWeek.value)
            val yesterdayDayOfWeek = convertToCalendarDayOfWeek(zonedNow.minusDays(1).dayOfWeek.value)

            // Case 1: Before midnight portion (current >= start)
            if (currentMinutes >= startMinutes) {
                return FocusRepository.isDayInMask(schedule.daysOfWeekMask, calendarDayOfWeek)
            }

            // Case 2: After midnight portion (current < end)
            if (currentMinutes < endMinutes) {
                // Check if YESTERDAY was active (the schedule started yesterday)
                return FocusRepository.isDayInMask(schedule.daysOfWeekMask, yesterdayDayOfWeek)
            }

            return false
        }
    }

    /**
     * Check if current time (in minutes from midnight) is within the schedule window.
     */
    private fun isTimeInWindow(currentMinutes: Int, startMinutes: Int, endMinutes: Int): Boolean {
        return if (startMinutes <= endMinutes) {
            // Normal window: e.g., 9am (540) to 5pm (1020)
            // Current must be >= start AND < end
            currentMinutes >= startMinutes && currentMinutes < endMinutes
        } else {
            // Cross-midnight window: e.g., 11pm (1380) to 2am (120)
            // Current must be >= start OR < end
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    /**
     * Convert Java DayOfWeek value (1=Monday, 7=Sunday) to Calendar format (1=Sunday, 7=Saturday).
     */
    private fun convertToCalendarDayOfWeek(javaDayOfWeek: Int): Int {
        // Java DayOfWeek: 1=Monday, 2=Tuesday, ..., 7=Sunday
        // Calendar: 1=Sunday, 2=Monday, ..., 7=Saturday
        return if (javaDayOfWeek == 7) 1 else javaDayOfWeek + 1
    }

    /**
     * Calculate when the current schedule window ends.
     * Returns epoch millis for UI display.
     */
    private fun calculateScheduleEndTime(schedule: FocusScheduleEntity, now: Long): Long? {
        val zone = try {
            ZoneId.of(schedule.timezoneId)
        } catch (e: Exception) {
            ZoneId.systemDefault()
        }

        val zonedNow = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
        val currentMinutes = zonedNow.hour * 60 + zonedNow.minute
        val endMinutes = schedule.endTimeMinutes

        val endHour = endMinutes / 60
        val endMinute = endMinutes % 60

        return if (schedule.startTimeMinutes <= schedule.endTimeMinutes) {
            // Normal schedule - ends today
            zonedNow
                .withHour(endHour)
                .withMinute(endMinute)
                .withSecond(0)
                .withNano(0)
                .toInstant()
                .toEpochMilli()
        } else {
            // Cross-midnight schedule
            if (currentMinutes >= schedule.startTimeMinutes) {
                // We're before midnight, end time is tomorrow
                zonedNow
                    .plusDays(1)
                    .withHour(endHour)
                    .withMinute(endMinute)
                    .withSecond(0)
                    .withNano(0)
                    .toInstant()
                    .toEpochMilli()
            } else {
                // We're after midnight, end time is today
                zonedNow
                    .withHour(endHour)
                    .withMinute(endMinute)
                    .withSecond(0)
                    .withNano(0)
                    .toInstant()
                    .toEpochMilli()
            }
        }
    }

    /**
     * Get cached global enabled state, refreshing if stale.
     */
    private suspend fun getCachedGlobalEnabled(now: Long): Boolean {
        val cached = cachedGlobalEnabled
        if (cached != null && (now - globalCacheTimestamp) < GLOBAL_STATE_CACHE_MS) {
            return cached
        }

        val enabled = focusPrefs.isFocusModeGloballyEnabled()
        cachedGlobalEnabled = enabled
        globalCacheTimestamp = now
        return enabled
    }

    /**
     * Get cached schedules for a package, refreshing if stale.
     */
    private suspend fun getCachedSchedules(packageName: String, now: Long): List<FocusScheduleEntity> {
        synchronized(cacheLock) {
            val cached = scheduleCache[packageName]
            if (cached != null && (now - cached.timestamp) < SCHEDULE_CACHE_MS) {
                return cached.schedules
            }
        }

        val schedules = focusRepository.getSchedulesBlockingPackage(packageName)

        synchronized(cacheLock) {
            scheduleCache[packageName] = CachedSchedules(schedules, now)
        }

        return schedules
    }

    /**
     * Invalidate all caches. Call when schedules or toggles change.
     */
    fun invalidateCache() {
        cachedGlobalEnabled = null
        globalCacheTimestamp = 0L
        synchronized(cacheLock) {
            scheduleCache.clear()
        }
    }
}
