package com.screentimetracker.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.screentimetracker.app.data.model.RecommendationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * DataStore wrapper for recommendation preferences.
 * Tracks dismissed recommendations to avoid showing the same type repeatedly.
 */
class RecommendationPrefs(private val context: Context) {

    companion object {
        private val Context.recommendationDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "recommendation_prefs"
        )

        // Prefix for dismissed recommendation keys
        private const val DISMISSED_PREFIX = "dismissed_"

        // Prefix for action taken keys (more permanent than dismissal)
        private const val ACTION_TAKEN_PREFIX = "action_taken_"

        // Duration to suppress dismissed recommendations (24 hours)
        private const val DISMISS_DURATION_MS = 24 * 60 * 60 * 1000L
    }

    /**
     * Get the preference key for a recommendation type's dismiss timestamp.
     */
    private fun getDismissedKey(type: RecommendationType): Preferences.Key<Long> {
        return longPreferencesKey("${DISMISSED_PREFIX}${type.name}")
    }

    /**
     * Check if a recommendation type is currently dismissed.
     * Returns true if the type was dismissed less than 24 hours ago.
     */
    suspend fun isDismissed(type: RecommendationType): Boolean = withContext(Dispatchers.IO) {
        val dismissedKey = getDismissedKey(type)
        val dismissedAt = context.recommendationDataStore.data.first()[dismissedKey] ?: 0L
        val now = System.currentTimeMillis()

        // Dismissed if within the suppression window
        now - dismissedAt < DISMISS_DURATION_MS
    }

    /**
     * Mark a recommendation type as dismissed.
     * The type will not be shown again for 24 hours.
     */
    suspend fun dismiss(type: RecommendationType) {
        val dismissedKey = getDismissedKey(type)
        context.recommendationDataStore.edit { prefs ->
            prefs[dismissedKey] = System.currentTimeMillis()
        }
    }

    /**
     * Clear dismissal for a specific type (for testing or manual reset).
     */
    suspend fun clearDismissal(type: RecommendationType) {
        val dismissedKey = getDismissedKey(type)
        context.recommendationDataStore.edit { prefs ->
            prefs.remove(dismissedKey)
        }
    }

    /**
     * Clear all dismissals (for day reset or testing).
     */
    suspend fun clearAllDismissals() {
        context.recommendationDataStore.edit { prefs ->
            val keysToRemove = prefs.asMap().keys.filter { key ->
                key.name.startsWith(DISMISSED_PREFIX)
            }
            keysToRemove.forEach { prefs.remove(it) }
        }
    }

    /**
     * Get the timestamp when a type was dismissed (0 if never dismissed).
     */
    suspend fun getDismissedTimestamp(type: RecommendationType): Long = withContext(Dispatchers.IO) {
        val dismissedKey = getDismissedKey(type)
        context.recommendationDataStore.data.first()[dismissedKey] ?: 0L
    }

    // ========== Action Taken Tracking ==========
    // When user takes a CTA action (e.g., creates a schedule), we track this
    // as a more permanent suppression than the 24-hour dismissal.

    /**
     * Get the preference key for a recommendation type's action taken state.
     */
    private fun getActionTakenKey(type: RecommendationType): Preferences.Key<String> {
        return stringPreferencesKey("${ACTION_TAKEN_PREFIX}${type.name}")
    }

    /**
     * Mark that the user has taken action on a recommendation type.
     * This is more permanent than a dismissal - the recommendation won't
     * reappear until the action is cleared.
     */
    suspend fun markActionTaken(type: RecommendationType) {
        val key = getActionTakenKey(type)
        context.recommendationDataStore.edit { prefs ->
            prefs[key] = System.currentTimeMillis().toString()
        }
    }

    /**
     * Check if the user has already taken action on this recommendation type.
     */
    suspend fun hasActionBeenTaken(type: RecommendationType): Boolean = withContext(Dispatchers.IO) {
        val key = getActionTakenKey(type)
        context.recommendationDataStore.data.first()[key] != null
    }

    /**
     * Clear the action taken state for a recommendation type.
     * Allows the recommendation to appear again if the pattern is detected.
     */
    suspend fun clearActionTaken(type: RecommendationType) {
        val key = getActionTakenKey(type)
        context.recommendationDataStore.edit { prefs ->
            prefs.remove(key)
        }
    }

    // ========== Clear All Data ==========

    /**
     * Clears all recommendation preferences, including dismissals and action tracking.
     * Used when user requests complete data deletion.
     */
    suspend fun clearAll() {
        context.recommendationDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
