package com.screentimetracker.app.focus.ui.schedule

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screentimetracker.app.data.db.AppDatabase
import com.screentimetracker.app.data.db.entity.FocusScheduleEntity
import com.screentimetracker.app.data.prefs.UserPrefs
import com.screentimetracker.app.focus.data.FocusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Schedule Edit screen.
 * Handles form state and save operations for creating/editing schedules.
 */
class ScheduleEditViewModel(application: Application) : AndroidViewModel(application) {

    private val focusRepository: FocusRepository
    private val packageManager: PackageManager = application.packageManager

    init {
        val database = AppDatabase.getInstance(application)
        focusRepository = FocusRepository(database.focusDao())
    }

    // Current schedule ID (null for new schedule)
    private var scheduleId: Long? = null

    // ========== Form State ==========

    val scheduleName = MutableStateFlow("")
    val daysOfWeekMask = MutableStateFlow(FocusRepository.WEEKDAYS)
    val startTimeMinutes = MutableStateFlow(9 * 60)  // 9:00 AM
    val endTimeMinutes = MutableStateFlow(17 * 60)   // 5:00 PM
    val blockedPackages = MutableStateFlow<Set<String>>(emptySet())

    // ========== Available Apps ==========

    /**
     * Data class for app info display.
     */
    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: Drawable?
    )

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    // ========== Save Result ==========

    sealed class SaveResult {
        object Success : SaveResult()
        data class Error(val message: String) : SaveResult()
    }

    private val _saveResult = MutableSharedFlow<SaveResult>()
    val saveResult: SharedFlow<SaveResult> = _saveResult.asSharedFlow()

    // ========== Loading State ==========

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadInstalledApps()
    }

    /**
     * Load trackable apps that can be blocked.
     * Uses the same hardcoded list as SettingsActivity for consistency.
     */
    private fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoading.value = true
            val apps = withContext(Dispatchers.IO) {
                UserPrefs.ALL_TRACKABLE_APPS.map { (packageName, displayName) ->
                    AppInfo(
                        packageName = packageName,
                        appName = displayName,
                        icon = try {
                            packageManager.getApplicationIcon(packageName)
                        } catch (e: Exception) {
                            null
                        }
                    )
                }.sortedBy { it.appName.lowercase() }
            }
            _installedApps.value = apps
            _isLoading.value = false
        }
    }

    /**
     * Load an existing schedule for editing.
     */
    fun loadSchedule(scheduleId: Long) {
        this.scheduleId = scheduleId
        viewModelScope.launch {
            val schedule = focusRepository.getScheduleById(scheduleId)
            if (schedule != null) {
                scheduleName.value = schedule.name
                daysOfWeekMask.value = schedule.daysOfWeekMask
                startTimeMinutes.value = schedule.startTimeMinutes
                endTimeMinutes.value = schedule.endTimeMinutes

                val packages = focusRepository.getBlockedPackagesForSchedule(scheduleId)
                blockedPackages.value = packages.toSet()
            }
        }
    }

    /**
     * Toggle a day in the days of week mask.
     */
    fun toggleDay(dayBit: Int) {
        val currentMask = daysOfWeekMask.value
        daysOfWeekMask.value = currentMask xor dayBit
    }

    /**
     * Check if a day is selected.
     */
    fun isDaySelected(dayBit: Int): Boolean {
        return (daysOfWeekMask.value and dayBit) != 0
    }

    /**
     * Set the start time.
     */
    fun setStartTime(hour: Int, minute: Int) {
        startTimeMinutes.value = hour * 60 + minute
    }

    /**
     * Set the end time.
     */
    fun setEndTime(hour: Int, minute: Int) {
        endTimeMinutes.value = hour * 60 + minute
    }

    /**
     * Toggle an app's blocked state.
     */
    fun toggleApp(packageName: String, isBlocked: Boolean) {
        val current = blockedPackages.value.toMutableSet()
        if (isBlocked) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        blockedPackages.value = current
    }

    /**
     * Check if an app is blocked.
     */
    fun isAppBlocked(packageName: String): Boolean {
        return blockedPackages.value.contains(packageName)
    }

    /**
     * Save the schedule (create new or update existing).
     */
    fun save() {
        viewModelScope.launch {
            val name = scheduleName.value.trim()

            // Validation
            if (name.isEmpty()) {
                _saveResult.emit(SaveResult.Error("Please enter a schedule name"))
                return@launch
            }

            if (daysOfWeekMask.value == 0) {
                _saveResult.emit(SaveResult.Error("Please select at least one day"))
                return@launch
            }

            if (blockedPackages.value.isEmpty()) {
                _saveResult.emit(SaveResult.Error("Please select at least one app to block"))
                return@launch
            }

            try {
                val existingId = scheduleId
                if (existingId != null) {
                    // Update existing schedule
                    val existingSchedule = focusRepository.getScheduleById(existingId)
                    if (existingSchedule != null) {
                        val updatedSchedule = existingSchedule.copy(
                            name = name,
                            daysOfWeekMask = daysOfWeekMask.value,
                            startTimeMinutes = startTimeMinutes.value,
                            endTimeMinutes = endTimeMinutes.value
                        )
                        focusRepository.updateScheduleWithBlockedApps(
                            updatedSchedule,
                            blockedPackages.value.toList()
                        )
                    }
                } else {
                    // Create new schedule
                    focusRepository.createSchedule(
                        name = name,
                        daysOfWeekMask = daysOfWeekMask.value,
                        startTimeMinutes = startTimeMinutes.value,
                        endTimeMinutes = endTimeMinutes.value,
                        blockedPackages = blockedPackages.value.toList()
                    )
                }
                _saveResult.emit(SaveResult.Success)
            } catch (e: Exception) {
                _saveResult.emit(SaveResult.Error("Failed to save schedule: ${e.message}"))
            }
        }
    }

    /**
     * Check if we're editing an existing schedule.
     */
    fun isEditMode(): Boolean = scheduleId != null
}
