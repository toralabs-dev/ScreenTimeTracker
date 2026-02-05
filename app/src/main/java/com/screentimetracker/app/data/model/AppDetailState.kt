package com.screentimetracker.app.data.model

/**
 * Data class representing the state of the App Detail screen.
 *
 * @param packageName The app's package name
 * @param appName The user-friendly app name
 * @param todayTotalMillis Total usage time today in milliseconds
 * @param sessionCountToday Number of sessions today
 * @param longestSessionMillisToday Duration of the longest session today in milliseconds
 * @param sessionsToday All sessions for this app today, sorted newest first
 * @param trendEntries Daily usage entries for the selected range
 * @param selectedDays Number of days selected for trend (7, 14, or 30)
 * @param isLoading Whether data is currently being loaded
 * @param isTrendLoading Whether trend data is currently being loaded
 */
data class AppDetailState(
    val packageName: String = "",
    val appName: String = "",
    val todayTotalMillis: Long = 0L,
    val sessionCountToday: Int = 0,
    val longestSessionMillisToday: Long = 0L,
    val sessionsToday: List<SessionInfo> = emptyList(),
    val trendEntries: List<DailyUsageEntry> = emptyList(),
    val selectedDays: Int = 7,
    val isLoading: Boolean = true,
    val isTrendLoading: Boolean = false
) {
    /**
     * Returns formatted total time as "Xh Ym" or "Xm"
     */
    fun getFormattedTotalTime(): String {
        val totalMinutes = todayTotalMillis / 1000 / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "0m"
        }
    }

    /**
     * Returns formatted longest session duration as "Xh Ym" or "Xm"
     */
    fun getFormattedLongestSession(): String {
        val totalMinutes = longestSessionMillisToday / 1000 / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "0m"
        }
    }

    /**
     * Returns average daily usage for the trend period
     */
    fun getFormattedAverageUsage(): String {
        val entriesWithData = trendEntries.filter { it.totalMillis > 0 }
        if (entriesWithData.isEmpty()) return "0m"

        val averageMillis = entriesWithData.sumOf { it.totalMillis } / entriesWithData.size
        val totalMinutes = averageMillis / 1000 / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "0m"
        }
    }

    /**
     * Whether there's any trend data to display
     */
    fun hasTrendData(): Boolean = trendEntries.any { it.totalMillis > 0 }

    /**
     * Whether there's any session data to display
     */
    fun hasSessionData(): Boolean = sessionsToday.isNotEmpty()
}
