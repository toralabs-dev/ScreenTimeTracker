package com.screentimetracker.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * DataStore wrapper for user preferences.
 * Handles daily limits, notification settings, tracked apps, and notification state.
 */
class UserPrefs(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

        // Daily limit
        private val DAILY_LIMIT_MILLIS = longPreferencesKey("daily_limit_millis")
        const val DEFAULT_DAILY_LIMIT_MILLIS = 2 * 60 * 60 * 1000L // 2 hours

        // Notification settings
        private val WARNING_NOTIFICATIONS_ENABLED = booleanPreferencesKey("warning_notifications_enabled")
        private val WARNING_PERCENT = intPreferencesKey("warning_percent")
        private val LIMIT_NOTIFICATIONS_ENABLED = booleanPreferencesKey("limit_notifications_enabled")
        const val DEFAULT_WARNING_PERCENT = 80

        // Tracked apps (stored as comma-separated string)
        private val TRACKED_PACKAGES = stringPreferencesKey("tracked_packages")

        // Notification state tracking
        private val LAST_NOTIFIED_DATE = stringPreferencesKey("last_notified_date") // ISO date string
        private val LAST_NOTIFIED_LEVEL = stringPreferencesKey("last_notified_level") // NONE, WARN, LIMIT
        private val SNOOZE_UNTIL_MILLIS = longPreferencesKey("snooze_until_millis")

        // Fast check mode state tracking
        private val FAST_CHECK_MODE_ACTIVE = booleanPreferencesKey("fast_check_mode_active")
        private val FAST_CHECK_LAST_DATE = stringPreferencesKey("fast_check_last_date")

        // Initial sync tracking (for first launch)
        private val HAS_COMPLETED_INITIAL_SYNC = booleanPreferencesKey("has_completed_initial_sync")

        // Theme mode preference
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        // Fast check mode thresholds (time-based, in minutes before warning threshold)
        const val FAST_CHECK_LEAD_TIME_MINUTES = 15  // Enter fast mode 15 min before warning
        const val FAST_CHECK_EXIT_BUFFER_MINUTES = 20  // Exit fast mode 20 min before warning (5 min hysteresis)

        // Default tracked apps
        val DEFAULT_TRACKED_PACKAGES = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.facebook.katana",
            "com.twitter.android",
            "com.google.android.youtube",
            "com.snapchat.android",
            "com.reddit.frontpage",
            "com.whatsapp"
        )

        // All trackable apps with display names
        val ALL_TRACKABLE_APPS = mapOf(
            "com.instagram.android" to "Instagram",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.facebook.katana" to "Facebook",
            "com.twitter.android" to "X (Twitter)",
            "com.google.android.youtube" to "YouTube",
            "com.snapchat.android" to "Snapchat",
            "com.reddit.frontpage" to "Reddit",
            "com.whatsapp" to "WhatsApp"
        )

        /**
         * Returns today's date as a string in ISO format (yyyy-MM-dd).
         */
        fun todayDateString(): String {
            return LocalDate.now(ZoneId.systemDefault()).toString()
        }
    }

    enum class NotificationLevel {
        NONE, WARN, LIMIT
    }

    // ========== Daily Limit ==========

    val dailyLimitMillis: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[DAILY_LIMIT_MILLIS] ?: DEFAULT_DAILY_LIMIT_MILLIS
    }

    suspend fun setDailyLimitMillis(millis: Long) {
        context.dataStore.edit { prefs ->
            prefs[DAILY_LIMIT_MILLIS] = millis
        }
    }

    suspend fun getDailyLimitMillisSync(): Long = withContext(Dispatchers.IO) {
        context.dataStore.data.first()[DAILY_LIMIT_MILLIS] ?: DEFAULT_DAILY_LIMIT_MILLIS
    }

    // ========== Notification Settings ==========

    val warningNotificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[WARNING_NOTIFICATIONS_ENABLED] ?: true
    }

    suspend fun setWarningNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[WARNING_NOTIFICATIONS_ENABLED] = enabled
        }
    }

    suspend fun getWarningNotificationsEnabledSync(): Boolean = withContext(Dispatchers.IO) {
        context.dataStore.data.first()[WARNING_NOTIFICATIONS_ENABLED] ?: true
    }

    val warningPercent: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[WARNING_PERCENT] ?: DEFAULT_WARNING_PERCENT
    }

    suspend fun setWarningPercent(percent: Int) {
        context.dataStore.edit { prefs ->
            prefs[WARNING_PERCENT] = percent.coerceIn(50, 95)
        }
    }

    suspend fun getWarningPercentSync(): Int = withContext(Dispatchers.IO) {
        context.dataStore.data.first()[WARNING_PERCENT] ?: DEFAULT_WARNING_PERCENT
    }

    val limitNotificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[LIMIT_NOTIFICATIONS_ENABLED] ?: true
    }

    suspend fun setLimitNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[LIMIT_NOTIFICATIONS_ENABLED] = enabled
        }
    }

    suspend fun getLimitNotificationsEnabledSync(): Boolean = withContext(Dispatchers.IO) {
        context.dataStore.data.first()[LIMIT_NOTIFICATIONS_ENABLED] ?: true
    }

    // ========== Tracked Apps ==========

    val trackedPackages: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val stored = prefs[TRACKED_PACKAGES]
        if (stored.isNullOrEmpty()) {
            DEFAULT_TRACKED_PACKAGES
        } else {
            stored.split(",").toSet()
        }
    }

    suspend fun setTrackedPackages(packages: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[TRACKED_PACKAGES] = packages.joinToString(",")
        }
    }

    suspend fun getTrackedPackagesSync(): Set<String> = withContext(Dispatchers.IO) {
        val stored = context.dataStore.data.first()[TRACKED_PACKAGES]
        if (stored.isNullOrEmpty()) {
            DEFAULT_TRACKED_PACKAGES
        } else {
            stored.split(",").toSet()
        }
    }

    suspend fun setAppTracked(packageName: String, tracked: Boolean) {
        val current = getTrackedPackagesSync().toMutableSet()
        if (tracked) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        setTrackedPackages(current)
    }

    // ========== Notification State ==========

    suspend fun getLastNotifiedDate(): LocalDate? = withContext(Dispatchers.IO) {
        val stored = context.dataStore.data.first()[LAST_NOTIFIED_DATE]
        stored?.let { LocalDate.parse(it) }
    }

    suspend fun getLastNotifiedLevel(): NotificationLevel = withContext(Dispatchers.IO) {
        val stored = context.dataStore.data.first()[LAST_NOTIFIED_LEVEL]
        stored?.let { NotificationLevel.valueOf(it) } ?: NotificationLevel.NONE
    }

    suspend fun setNotificationState(date: LocalDate, level: NotificationLevel) {
        context.dataStore.edit { prefs ->
            prefs[LAST_NOTIFIED_DATE] = date.toString()
            prefs[LAST_NOTIFIED_LEVEL] = level.name
        }
    }

    val snoozeUntilMillis: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[SNOOZE_UNTIL_MILLIS] ?: 0L
    }

    suspend fun getSnoozeUntilMillisSync(): Long = withContext(Dispatchers.IO) {
        context.dataStore.data.first()[SNOOZE_UNTIL_MILLIS] ?: 0L
    }

    suspend fun setSnoozeUntilMillis(millis: Long) {
        context.dataStore.edit { prefs ->
            prefs[SNOOZE_UNTIL_MILLIS] = millis
        }
    }

    suspend fun clearSnooze() {
        setSnoozeUntilMillis(0L)
    }

    suspend fun snoozeFor(durationMillis: Long) {
        setSnoozeUntilMillis(System.currentTimeMillis() + durationMillis)
    }

    suspend fun isSnoozed(): Boolean {
        return System.currentTimeMillis() < getSnoozeUntilMillisSync()
    }

    // ========== Initial Sync ==========

    val hasCompletedInitialSync: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[HAS_COMPLETED_INITIAL_SYNC] ?: false
    }

    suspend fun setInitialSyncCompleted() {
        context.dataStore.edit { prefs ->
            prefs[HAS_COMPLETED_INITIAL_SYNC] = true
        }
    }

    // ========== Theme Mode ==========

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE] ?: THEME_SYSTEM
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[THEME_MODE] = mode
        }
    }

    fun getThemeModeSync(): String {
        return kotlinx.coroutines.runBlocking {
            context.dataStore.data.first()[THEME_MODE] ?: THEME_SYSTEM
        }
    }

    // ========== Fast Check Mode ==========

    /**
     * Check if fast check mode is currently active.
     * Automatically resets if the day has changed.
     */
    suspend fun isFastCheckModeActive(): Boolean = withContext(Dispatchers.IO) {
        val lastDate = context.dataStore.data.first()[FAST_CHECK_LAST_DATE]
        val today = todayDateString()

        // Auto-reset on day change
        if (lastDate != today) {
            setFastCheckModeActive(false)
            return@withContext false
        }

        context.dataStore.data.first()[FAST_CHECK_MODE_ACTIVE] ?: false
    }

    /**
     * Set fast check mode active state.
     * Also updates the last date for day-change detection.
     */
    suspend fun setFastCheckModeActive(active: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[FAST_CHECK_MODE_ACTIVE] = active
            prefs[FAST_CHECK_LAST_DATE] = todayDateString()
        }
    }

    /**
     * Check if we should send a notification for the given level.
     * Returns true if:
     * - Not currently snoozed
     * - Haven't already sent this level today (or a higher level)
     */
    suspend fun shouldNotify(level: NotificationLevel): Boolean {
        if (isSnoozed()) return false

        val today = LocalDate.now(ZoneId.systemDefault())
        val lastDate = getLastNotifiedDate()
        val lastLevel = getLastNotifiedLevel()

        // If it's a new day, reset and allow notification
        if (lastDate != today) {
            return true
        }

        // Same day: only notify if this level is higher than what we've already sent
        return level.ordinal > lastLevel.ordinal
    }

    /**
     * Reset notification state at midnight.
     */
    suspend fun resetNotificationStateIfNewDay() {
        val today = LocalDate.now(ZoneId.systemDefault())
        val lastDate = getLastNotifiedDate()

        if (lastDate != today) {
            setNotificationState(today, NotificationLevel.NONE)
        }
    }

    // ========== Combined Preferences Flow ==========

    data class PrefsSnapshot(
        val dailyLimitMillis: Long,
        val warningNotificationsEnabled: Boolean,
        val warningPercent: Int,
        val limitNotificationsEnabled: Boolean,
        val trackedPackages: Set<String>,
        val snoozeUntilMillis: Long
    ) {
        fun isSnoozed(): Boolean = System.currentTimeMillis() < snoozeUntilMillis
    }

    val prefsSnapshot: Flow<PrefsSnapshot> = context.dataStore.data.map { prefs ->
        val trackedStr = prefs[TRACKED_PACKAGES]
        val tracked = if (trackedStr.isNullOrEmpty()) {
            DEFAULT_TRACKED_PACKAGES
        } else {
            trackedStr.split(",").toSet()
        }

        PrefsSnapshot(
            dailyLimitMillis = prefs[DAILY_LIMIT_MILLIS] ?: DEFAULT_DAILY_LIMIT_MILLIS,
            warningNotificationsEnabled = prefs[WARNING_NOTIFICATIONS_ENABLED] ?: true,
            warningPercent = prefs[WARNING_PERCENT] ?: DEFAULT_WARNING_PERCENT,
            limitNotificationsEnabled = prefs[LIMIT_NOTIFICATIONS_ENABLED] ?: true,
            trackedPackages = tracked,
            snoozeUntilMillis = prefs[SNOOZE_UNTIL_MILLIS] ?: 0L
        )
    }

    // ========== Clear All Data ==========

    /**
     * Clears all user preferences, resetting to defaults.
     * Used when user requests complete data deletion.
     */
    suspend fun clearAll() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
