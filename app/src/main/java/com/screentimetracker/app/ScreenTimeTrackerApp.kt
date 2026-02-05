package com.screentimetracker.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.screentimetracker.app.data.prefs.UserPrefs

/**
 * Application class for ScreenTimeTracker.
 * Applies the saved theme preference at app startup.
 */
class ScreenTimeTrackerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        applyTheme()
    }

    /**
     * Applies the saved theme mode preference.
     * Called at app startup to ensure consistent theming before any activities are created.
     */
    fun applyTheme() {
        val prefs = UserPrefs(this)
        when (prefs.getThemeModeSync()) {
            UserPrefs.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            UserPrefs.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
