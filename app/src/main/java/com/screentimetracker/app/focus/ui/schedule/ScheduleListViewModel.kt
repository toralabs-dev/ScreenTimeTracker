package com.screentimetracker.app.focus.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screentimetracker.app.data.db.AppDatabase
import com.screentimetracker.app.data.db.entity.FocusScheduleEntity
import com.screentimetracker.app.focus.accessibility.AppLaunchInterceptorService
import com.screentimetracker.app.focus.data.FocusPrefs
import com.screentimetracker.app.focus.data.FocusRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the Schedule List screen.
 * Exposes list of schedules with blocked app counts and global focus mode state.
 */
class ScheduleListViewModel(application: Application) : AndroidViewModel(application) {

    private val focusRepository: FocusRepository
    private val focusPrefs: FocusPrefs

    // Break state
    private val _remainingMillis = MutableStateFlow(0L)
    val remainingMillis: StateFlow<Long> = _remainingMillis.asStateFlow()

    init {
        val database = AppDatabase.getInstance(application)
        focusRepository = FocusRepository(database.focusDao())
        focusPrefs = FocusPrefs(application)

        // Countdown ticker for break time remaining
        viewModelScope.launch {
            breakEndTimeMillis.collectLatest { endTime ->
                if (endTime > 0) {
                    while (System.currentTimeMillis() < endTime) {
                        _remainingMillis.value = endTime - System.currentTimeMillis()
                        delay(1000)
                    }
                    _remainingMillis.value = 0L
                    focusPrefs.clearBreak()
                }
            }
        }
    }

    /**
     * Data class combining a schedule with its blocked app count.
     */
    data class ScheduleWithApps(
        val schedule: FocusScheduleEntity,
        val blockedAppCount: Int
    )

    /**
     * Observable list of schedules with blocked app counts.
     */
    val schedules: StateFlow<List<ScheduleWithApps>> = focusRepository.observeAllSchedules()
        .map { scheduleList ->
            scheduleList.map { schedule ->
                val blockedApps = focusRepository.getBlockedAppsForSchedule(schedule.id)
                ScheduleWithApps(schedule, blockedApps.size)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Break end time in milliseconds.
     * 0 or past time means not on break.
     */
    val breakEndTimeMillis: StateFlow<Long> = focusPrefs.breakEndTimeMillis
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /**
     * Whether currently on break.
     */
    val isOnBreak: StateFlow<Boolean> = breakEndTimeMillis
        .map { it > 0 && System.currentTimeMillis() < it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Toggle a schedule's enabled state.
     */
    fun toggleScheduleEnabled(scheduleId: Long, enabled: Boolean) {
        viewModelScope.launch {
            focusRepository.setScheduleEnabled(scheduleId, enabled)
        }
    }

    /**
     * Break duration options.
     */
    enum class BreakDuration {
        THIRTY_MINUTES,
        ONE_HOUR,
        TWO_HOURS,
        UNTIL_TOMORROW
    }

    /**
     * Start a break for the given duration.
     */
    fun startBreak(duration: BreakDuration) {
        viewModelScope.launch {
            when (duration) {
                BreakDuration.THIRTY_MINUTES -> focusPrefs.startBreak(30 * 60 * 1000L)
                BreakDuration.ONE_HOUR -> focusPrefs.startBreak(60 * 60 * 1000L)
                BreakDuration.TWO_HOURS -> focusPrefs.startBreak(2 * 60 * 60 * 1000L)
                BreakDuration.UNTIL_TOMORROW -> focusPrefs.startBreakUntilTomorrow()
            }
        }
    }

    /**
     * Resume focus mode immediately (end break).
     */
    fun resumeNow() {
        viewModelScope.launch {
            focusPrefs.clearBreak()
        }
    }

    /**
     * Delete a schedule.
     */
    fun deleteSchedule(scheduleId: Long) {
        viewModelScope.launch {
            focusRepository.deleteSchedule(scheduleId)
        }
    }

    /**
     * Check if accessibility service is enabled.
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        return AppLaunchInterceptorService.isEnabled(getApplication())
    }

    /**
     * Open accessibility settings.
     */
    fun openAccessibilitySettings() {
        AppLaunchInterceptorService.openSettings(getApplication())
    }

    companion object {
        /**
         * Format remaining break time as a human-readable string.
         * Examples: "1h 23m", "45m", "2h"
         */
        fun formatRemainingTime(millis: Long): String {
            val totalMinutes = (millis / 1000 / 60).toInt()
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return when {
                hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                hours > 0 -> "${hours}h"
                else -> "${minutes}m"
            }
        }

        /**
         * Format days of week mask as a human-readable string.
         * Examples: "Mon-Fri", "Sat, Sun", "Every day"
         */
        fun formatDaysSummary(daysOfWeekMask: Int): String {
            if (daysOfWeekMask == FocusRepository.ALL_DAYS) {
                return "Every day"
            }
            if (daysOfWeekMask == FocusRepository.WEEKDAYS) {
                return "Mon-Fri"
            }
            if (daysOfWeekMask == FocusRepository.WEEKENDS) {
                return "Sat, Sun"
            }

            val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            val activeDays = mutableListOf<String>()

            for (i in 0..6) {
                val dayBit = 1 shl i
                if ((daysOfWeekMask and dayBit) != 0) {
                    activeDays.add(dayNames[i])
                }
            }

            return if (activeDays.isEmpty()) {
                "No days"
            } else {
                activeDays.joinToString(", ")
            }
        }

        /**
         * Format time in minutes from midnight to a display string.
         * Example: 540 -> "9:00 AM", 1020 -> "5:00 PM"
         */
        fun formatTime(minutesFromMidnight: Int): String {
            val hours = minutesFromMidnight / 60
            val minutes = minutesFromMidnight % 60
            val isPm = hours >= 12
            val displayHour = when {
                hours == 0 -> 12
                hours > 12 -> hours - 12
                else -> hours
            }
            val amPm = if (isPm) "PM" else "AM"
            return String.format("%d:%02d %s", displayHour, minutes, amPm)
        }
    }
}
