package com.screentimetracker.app.data.model

/**
 * Data class representing the overall dashboard state.
 *
 * @param totalUsageMillis Total time spent on tracked social media apps today
 * @param dailyLimitMillis The daily limit (2 hours = 7,200,000 ms)
 * @param topApps List of the top 3 most-used apps, sorted by usage time descending
 * @param longestSessions List of the top 3 longest individual sessions
 * @param sessionsTodayAll All sessions for today across tracked apps (>= 1 minute)
 * @param recommendation Smart recommendation with contextual CTA (replaces insightText)
 * @param trendSummary Summary of daily usage trends
 * @param isLoading Whether the data is currently being loaded
 * @param isTrendLoading Whether the trend data is currently being loaded
 * @param hasData Whether any usage data is available
 */
data class DashboardState(
    val totalUsageMillis: Long = 0,
    val dailyLimitMillis: Long = 7_200_000L, // 2 hours in milliseconds
    val topApps: List<AppUsageInfo> = emptyList(),
    val longestSessions: List<SessionInfo> = emptyList(),
    val sessionsTodayAll: List<SessionInfo> = emptyList(),
    val recommendation: Recommendation? = null,
    val trendSummary: TrendSummary = TrendSummary(),
    val isLoading: Boolean = true,
    val isTrendLoading: Boolean = false,
    val hasData: Boolean = false
) {
    /**
     * Returns the percentage of daily limit used (0-100+)
     */
    fun getUsagePercentage(): Int {
        if (dailyLimitMillis == 0L) return 0
        return ((totalUsageMillis.toFloat() / dailyLimitMillis) * 100).toInt()
    }

    /**
     * Returns whether the user has exceeded their daily limit
     */
    fun isOverLimit(): Boolean = totalUsageMillis > dailyLimitMillis

    /**
     * Returns total usage formatted as "Xh Ym" or "Xm" if less than an hour
     */
    fun getFormattedTotalTime(): String {
        val totalMinutes = totalUsageMillis / 1000 / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "0m"
        }
    }
}
