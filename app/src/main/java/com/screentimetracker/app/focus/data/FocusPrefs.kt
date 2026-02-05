package com.screentimetracker.app.focus.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * DataStore wrapper for Focus Mode preferences.
 * Handles global focus mode state and onboarding flags.
 */
class FocusPrefs(private val context: Context) {

    companion object {
        private val Context.focusDataStore: DataStore<Preferences> by preferencesDataStore(name = "focus_prefs")

        // Global focus mode toggle (master switch)
        private val FOCUS_GLOBALLY_ENABLED = booleanPreferencesKey("focus_globally_enabled")

        // Onboarding flags
        private val HAS_SHOWN_ACCESSIBILITY_PROMPT = booleanPreferencesKey("has_shown_accessibility_prompt")
        private val HAS_COMPLETED_FOCUS_ONBOARDING = booleanPreferencesKey("has_completed_focus_onboarding")

        // Quick settings tile state
        private val TILE_ADDED = booleanPreferencesKey("tile_added")

        // Break state (snooze feature)
        private val BREAK_END_TIME_MILLIS = longPreferencesKey("break_end_time_millis")
    }

    // ========== Global Focus Mode State ==========

    /**
     * Flow of the global focus mode enabled state.
     * When false, all focus schedules are suspended.
     */
    val focusGloballyEnabled: Flow<Boolean> = context.focusDataStore.data.map { prefs ->
        prefs[FOCUS_GLOBALLY_ENABLED] ?: false
    }

    /**
     * Set the global focus mode enabled state.
     * When false, all focus schedules are suspended.
     */
    suspend fun setFocusModeGloballyEnabled(enabled: Boolean) {
        context.focusDataStore.edit { prefs ->
            prefs[FOCUS_GLOBALLY_ENABLED] = enabled
        }
    }

    /**
     * Get the current global focus mode state synchronously.
     */
    suspend fun isFocusModeGloballyEnabled(): Boolean = withContext(Dispatchers.IO) {
        context.focusDataStore.data.first()[FOCUS_GLOBALLY_ENABLED] ?: false
    }

    /**
     * Toggle the global focus mode state.
     * @return The new state after toggling
     */
    suspend fun toggleFocusMode(): Boolean {
        val newState = !isFocusModeGloballyEnabled()
        setFocusModeGloballyEnabled(newState)
        return newState
    }

    // ========== Onboarding Flags ==========

    /**
     * Check if we've shown the accessibility service prompt.
     */
    suspend fun hasShownAccessibilityPrompt(): Boolean = withContext(Dispatchers.IO) {
        context.focusDataStore.data.first()[HAS_SHOWN_ACCESSIBILITY_PROMPT] ?: false
    }

    /**
     * Mark the accessibility prompt as shown.
     */
    suspend fun setAccessibilityPromptShown() {
        context.focusDataStore.edit { prefs ->
            prefs[HAS_SHOWN_ACCESSIBILITY_PROMPT] = true
        }
    }

    /**
     * Check if user has completed focus mode onboarding.
     */
    suspend fun hasCompletedFocusOnboarding(): Boolean = withContext(Dispatchers.IO) {
        context.focusDataStore.data.first()[HAS_COMPLETED_FOCUS_ONBOARDING] ?: false
    }

    /**
     * Mark focus mode onboarding as completed.
     */
    suspend fun setFocusOnboardingCompleted() {
        context.focusDataStore.edit { prefs ->
            prefs[HAS_COMPLETED_FOCUS_ONBOARDING] = true
        }
    }

    // ========== Break State (Take a Break / Snooze) ==========

    /**
     * Flow of the break end time in milliseconds.
     * 0 or past time means not on break.
     */
    val breakEndTimeMillis: Flow<Long> = context.focusDataStore.data.map { prefs ->
        prefs[BREAK_END_TIME_MILLIS] ?: 0L
    }

    /**
     * Get the current break end time synchronously.
     */
    suspend fun getBreakEndTimeMillis(): Long = withContext(Dispatchers.IO) {
        context.focusDataStore.data.first()[BREAK_END_TIME_MILLIS] ?: 0L
    }

    /**
     * Set the break end time.
     */
    suspend fun setBreakEndTimeMillis(endTimeMillis: Long) {
        context.focusDataStore.edit { prefs ->
            prefs[BREAK_END_TIME_MILLIS] = endTimeMillis
        }
    }

    /**
     * Start a break for the given duration.
     */
    suspend fun startBreak(durationMillis: Long) {
        setBreakEndTimeMillis(System.currentTimeMillis() + durationMillis)
    }

    /**
     * Start a break until midnight tonight (start of tomorrow).
     */
    suspend fun startBreakUntilTomorrow() {
        val midnight = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        setBreakEndTimeMillis(midnight)
    }

    /**
     * Clear the break state (resume focus mode).
     */
    suspend fun clearBreak() {
        setBreakEndTimeMillis(0L)
    }

    /**
     * Check if currently on break.
     */
    suspend fun isOnBreak(): Boolean {
        val endTime = getBreakEndTimeMillis()
        return endTime > 0 && System.currentTimeMillis() < endTime
    }

    // ========== Quick Settings Tile ==========

    /**
     * Check if the quick settings tile has been added.
     */
    suspend fun isTileAdded(): Boolean = withContext(Dispatchers.IO) {
        context.focusDataStore.data.first()[TILE_ADDED] ?: false
    }

    /**
     * Mark the quick settings tile as added.
     */
    suspend fun setTileAdded(added: Boolean) {
        context.focusDataStore.edit { prefs ->
            prefs[TILE_ADDED] = added
        }
    }

    // ========== Combined State Flow ==========

    /**
     * Data class for observing focus preferences as a single flow.
     */
    data class FocusPrefsSnapshot(
        val focusGloballyEnabled: Boolean,
        val hasShownAccessibilityPrompt: Boolean,
        val hasCompletedOnboarding: Boolean
    )

    /**
     * Observe all focus preferences as a single flow.
     */
    val prefsSnapshot: Flow<FocusPrefsSnapshot> = context.focusDataStore.data.map { prefs ->
        FocusPrefsSnapshot(
            focusGloballyEnabled = prefs[FOCUS_GLOBALLY_ENABLED] ?: false,
            hasShownAccessibilityPrompt = prefs[HAS_SHOWN_ACCESSIBILITY_PROMPT] ?: false,
            hasCompletedOnboarding = prefs[HAS_COMPLETED_FOCUS_ONBOARDING] ?: false
        )
    }

    // ========== Clear All Data ==========

    /**
     * Clears all focus preferences, resetting to defaults.
     * Used when user requests complete data deletion.
     */
    suspend fun clearAll() {
        context.focusDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
