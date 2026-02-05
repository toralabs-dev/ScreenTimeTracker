package com.screentimetracker.app.data.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Data class representing a single continuous app usage session.
 *
 * Sessions store REAL start/end times (not clipped to day boundaries).
 * For cross-midnight sessions, startTimeMillis may be from yesterday.
 *
 * @param packageName The app's package name (e.g., "com.instagram.android")
 * @param appName The user-friendly app name (e.g., "Instagram")
 * @param startTimeMillis Session start time in milliseconds since epoch (REAL time, may be yesterday)
 * @param endTimeMillis Session end time in milliseconds since epoch (REAL time)
 * @param isInferredStart True if the start time was inferred (orphan end event)
 */
data class SessionInfo(
    val packageName: String,
    val appName: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val isInferredStart: Boolean = false
) {
    /**
     * Returns the full session duration in milliseconds (not clipped to any window)
     */
    val durationMillis: Long
        get() = endTimeMillis - startTimeMillis

    /**
     * Computes the overlap duration with a given time window.
     * This is the portion of the session that falls within [windowStart, windowEnd].
     *
     * @param windowStart Start of the window in milliseconds
     * @param windowEnd End of the window in milliseconds
     * @return Overlap duration in milliseconds (0 if no overlap)
     */
    fun overlapDuration(windowStart: Long, windowEnd: Long): Long {
        // No overlap if session ends before window or starts after window
        if (endTimeMillis <= windowStart || startTimeMillis >= windowEnd) {
            return 0L
        }

        val overlapStart = maxOf(startTimeMillis, windowStart)
        val overlapEnd = minOf(endTimeMillis, windowEnd)
        return overlapEnd - overlapStart
    }

    /**
     * Returns true if this session overlaps with the given time window.
     */
    fun overlapsWindow(windowStart: Long, windowEnd: Long): Boolean {
        return endTimeMillis > windowStart && startTimeMillis < windowEnd
    }

    /**
     * Returns true if the session started before the given window.
     * Useful for indicating cross-midnight sessions in the UI.
     */
    fun startedBeforeWindow(windowStart: Long): Boolean {
        return startTimeMillis < windowStart
    }

    /**
     * Returns session duration formatted as "Xh Ym" or "Xm" if less than an hour
     */
    fun getFormattedDuration(): String {
        return formatDuration(durationMillis)
    }

    /**
     * Returns the overlap duration with the given window, formatted.
     */
    fun getFormattedOverlapDuration(windowStart: Long, windowEnd: Long): String {
        return formatDuration(overlapDuration(windowStart, windowEnd))
    }

    /**
     * Returns the time range formatted as "H:MM AM - H:MM PM"
     * For cross-midnight sessions, includes date indicator if start is on different day.
     */
    fun getFormattedTimeRange(): String {
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val startTime = timeFormat.format(Date(startTimeMillis))
        val endTime = timeFormat.format(Date(endTimeMillis))

        // Check if start and end are on different days
        val startCal = Calendar.getInstance().apply { timeInMillis = startTimeMillis }
        val endCal = Calendar.getInstance().apply { timeInMillis = endTimeMillis }

        return if (startCal.get(Calendar.DAY_OF_YEAR) != endCal.get(Calendar.DAY_OF_YEAR) ||
            startCal.get(Calendar.YEAR) != endCal.get(Calendar.YEAR)) {
            // Different days - add "Yesterday" indicator
            "Yesterday $startTime - $endTime"
        } else {
            "$startTime - $endTime"
        }
    }

    companion object {
        /**
         * Formats a duration in milliseconds as "Xh Ym" or "Xm"
         */
        fun formatDuration(millis: Long): String {
            val totalMinutes = millis / 1000 / 60
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60

            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m"
                else -> "<1m"
            }
        }
    }
}
