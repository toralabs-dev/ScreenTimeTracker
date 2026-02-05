package com.screentimetracker.app.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class representing daily usage statistics for a single day.
 *
 * @param dateMillis The date in milliseconds (midnight of that day)
 * @param totalMillis Total time spent on tracked social media apps in milliseconds
 */
data class DailyUsageEntry(
    val dateMillis: Long,
    val totalMillis: Long
) {
    /**
     * Returns the date formatted as "M/d" (e.g., "1/15")
     */
    fun getFormattedDate(): String {
        val dateFormat = SimpleDateFormat("M/d", Locale.getDefault())
        return dateFormat.format(Date(dateMillis))
    }

    /**
     * Returns usage time formatted as "Xh Ym" or "Xm" if less than an hour
     */
    fun getFormattedTime(): String {
        val totalMinutes = totalMillis / 1000 / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "0m"
        }
    }
}

/**
 * Data class containing trend summary statistics.
 *
 * @param dailyEntries List of daily usage entries
 * @param averageMillis Average daily usage in milliseconds
 * @param worstDayEntry The day with the highest usage (null if no data)
 * @param selectedDays Number of days being displayed (7, 14, or 30)
 */
data class TrendSummary(
    val dailyEntries: List<DailyUsageEntry> = emptyList(),
    val averageMillis: Long = 0,
    val worstDayEntry: DailyUsageEntry? = null,
    val selectedDays: Int = 7
) {
    /**
     * Returns average usage formatted as "Xh Ym" or "Xm"
     */
    fun getFormattedAverage(): String {
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
     * Returns worst day formatted as "Xh Ym (M/d)"
     */
    fun getFormattedWorstDay(): String {
        val entry = worstDayEntry ?: return "N/A"
        return "${entry.getFormattedTime()} (${entry.getFormattedDate()})"
    }

    /**
     * Returns true if there's any usage data to display
     */
    fun hasData(): Boolean = dailyEntries.any { it.totalMillis > 0 }
}
