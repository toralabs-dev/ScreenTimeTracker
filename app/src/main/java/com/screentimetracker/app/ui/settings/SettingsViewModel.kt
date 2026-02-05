package com.screentimetracker.app.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screentimetracker.app.data.db.AppDatabase
import com.screentimetracker.app.data.prefs.RecommendationPrefs
import com.screentimetracker.app.data.prefs.UserPrefs
import com.screentimetracker.app.focus.data.FocusPrefs
import com.screentimetracker.app.worker.UsageCheckWorker
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * ViewModel for the Settings screen.
 * Exposes preferences as StateFlows and handles save actions.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val userPrefs = UserPrefs(application)
    private val focusPrefs = FocusPrefs(application)
    private val recommendationPrefs = RecommendationPrefs(application)
    private val appDatabase = AppDatabase.getInstance(application)

    // ========== Preferences State ==========

    val dailyLimitMillis: StateFlow<Long> = userPrefs.dailyLimitMillis
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPrefs.DEFAULT_DAILY_LIMIT_MILLIS)

    val warningNotificationsEnabled: StateFlow<Boolean> = userPrefs.warningNotificationsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val warningPercent: StateFlow<Int> = userPrefs.warningPercent
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPrefs.DEFAULT_WARNING_PERCENT)

    val limitNotificationsEnabled: StateFlow<Boolean> = userPrefs.limitNotificationsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val trackedPackages: StateFlow<Set<String>> = userPrefs.trackedPackages
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPrefs.DEFAULT_TRACKED_PACKAGES)

    val snoozeUntilMillis: StateFlow<Long> = userPrefs.snoozeUntilMillis
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val themeMode: StateFlow<String> = userPrefs.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPrefs.THEME_SYSTEM)

    // ========== Derived State ==========

    /**
     * Whether notifications are currently snoozed.
     */
    val isSnoozed: StateFlow<Boolean> = snoozeUntilMillis.map { snoozeUntil ->
        System.currentTimeMillis() < snoozeUntil
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Formatted snooze end time (e.g., "2:30 PM").
     */
    val snoozeEndTimeFormatted: StateFlow<String?> = snoozeUntilMillis.map { snoozeUntil ->
        if (System.currentTimeMillis() < snoozeUntil) {
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            timeFormat.format(Date(snoozeUntil))
        } else {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ========== Events ==========

    private val _requestNotificationPermission = MutableSharedFlow<Unit>()
    val requestNotificationPermission: SharedFlow<Unit> = _requestNotificationPermission

    private val _dataCleared = MutableSharedFlow<Unit>()
    val dataCleared: SharedFlow<Unit> = _dataCleared

    // ========== Actions ==========

    fun setDailyLimit(millis: Long) {
        viewModelScope.launch {
            userPrefs.setDailyLimitMillis(millis)
        }
    }

    fun setWarningNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPrefs.setWarningNotificationsEnabled(enabled)
            updateWorkerSchedule()
        }
    }

    fun setWarningPercent(percent: Int) {
        viewModelScope.launch {
            userPrefs.setWarningPercent(percent)
        }
    }

    fun setLimitNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPrefs.setLimitNotificationsEnabled(enabled)
            updateWorkerSchedule()
        }
    }

    fun setAppTracked(packageName: String, tracked: Boolean) {
        viewModelScope.launch {
            userPrefs.setAppTracked(packageName, tracked)
        }
    }

    fun snoozeNotifications() {
        viewModelScope.launch {
            userPrefs.snoozeFor(60 * 60 * 1000L) // 60 minutes
        }
    }

    fun clearSnooze() {
        viewModelScope.launch {
            userPrefs.clearSnooze()
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            userPrefs.setThemeMode(mode)
            // Apply immediately
            when (mode) {
                UserPrefs.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                UserPrefs.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }

    fun requestPermissionIfNeeded() {
        viewModelScope.launch {
            _requestNotificationPermission.emit(Unit)
        }
    }

    private fun updateWorkerSchedule() {
        viewModelScope.launch {
            val warningEnabled = userPrefs.getWarningNotificationsEnabledSync()
            val limitEnabled = userPrefs.getLimitNotificationsEnabledSync()

            val context = getApplication<Application>()
            if (warningEnabled || limitEnabled) {
                UsageCheckWorker.schedule(context)
            } else {
                UsageCheckWorker.cancel(context)
                // Also cancel fast checks and reset fast mode state when all notifications disabled
                UsageCheckWorker.cancelFastChecks(context)
                userPrefs.setFastCheckModeActive(false)
            }
        }
    }

    // ========== Tracked Apps List ==========

    private val packageManager: PackageManager = application.packageManager

    data class TrackedAppItem(
        val packageName: String,
        val displayName: String,
        val isTracked: Boolean,
        val icon: Drawable? = null
    )

    val trackedAppsList: StateFlow<List<TrackedAppItem>> = trackedPackages.map { tracked ->
        UserPrefs.ALL_TRACKABLE_APPS.map { (packageName, displayName) ->
            val icon = try {
                packageManager.getApplicationIcon(packageName)
            } catch (e: Exception) {
                null
            }
            TrackedAppItem(
                packageName = packageName,
                displayName = displayName,
                isTracked = tracked.contains(packageName),
                icon = icon
            )
        }.sortedBy { it.displayName }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ========== Data Management ==========

    /**
     * Clears all user data including database tables and preferences.
     * This is an irreversible operation used for Play Store data deletion compliance.
     */
    fun clearAllData() {
        viewModelScope.launch {
            // Cancel any scheduled workers first
            val context = getApplication<Application>()
            UsageCheckWorker.cancel(context)
            UsageCheckWorker.cancelFastChecks(context)

            // Clear Room database tables
            appDatabase.clearAllTables()

            // Clear all DataStore preferences
            userPrefs.clearAll()
            focusPrefs.clearAll()
            recommendationPrefs.clearAll()

            // Reset theme to system default
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

            // Notify UI that data was cleared
            _dataCleared.emit(Unit)
        }
    }
}
