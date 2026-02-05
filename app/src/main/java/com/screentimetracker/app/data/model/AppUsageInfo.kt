package com.screentimetracker.app.data.model

import android.graphics.drawable.Drawable

/**
 * Data class representing usage information for a single app.
 *
 * @param packageName The app's package name (e.g., "com.instagram.android")
 * @param appName The user-friendly app name (e.g., "Instagram")
 * @param usageTimeMillis Time spent in the app today in milliseconds
 * @param icon The app's icon drawable (loaded from PackageManager)
 */
data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val usageTimeMillis: Long,
    val icon: Drawable? = null
) {
    /**
     * Returns usage time formatted as "Xh Ym" or "Xm" if less than an hour
     */
    fun getFormattedTime(): String {
        val totalMinutes = usageTimeMillis / 1000 / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }
}
