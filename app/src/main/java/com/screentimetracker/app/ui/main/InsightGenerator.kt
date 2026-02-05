package com.screentimetracker.app.ui.main

import com.screentimetracker.app.data.model.SessionInfo
import java.util.Calendar

/**
 * Rule-based insight generator for session behavior analysis.
 * Generates 1-2 actionable sentences based on today's session patterns.
 */
object InsightGenerator {

    private enum class TimeWindow(val displayName: String) {
        LATE_NIGHT("late night"),
        MORNING("morning"),
        AFTERNOON("afternoon"),
        EVENING("evening")
    }

    /**
     * Generates insight text based on session data.
     *
     * @param allSessions All sessions for today (>= 1 minute)
     * @param top3Sessions Top 3 longest sessions (by overlap with today)
     * @param totalUsageMillis Total usage time today (overlap-based)
     * @param windowStart Start of today's window (midnight)
     * @param windowEnd End of today's window (now)
     * @return Insight text (1-2 sentences) or null if no insights apply
     */
    fun generateInsight(
        allSessions: List<SessionInfo>,
        top3Sessions: List<SessionInfo>,
        totalUsageMillis: Long,
        windowStart: Long,
        windowEnd: Long
    ): String? {
        // Need at least 2 sessions to generate insights
        if (allSessions.size < 2) return null

        val insights = mutableListOf<String>()

        // Rule 1: Dominant app (same app in 2+ of top 3 longest sessions)
        getDominantAppInsight(top3Sessions)?.let { insights.add(it) }

        // Rule 2: Time-of-day cluster (2+ of top 3 in same time window)
        if (insights.size < 2) {
            getTimeClusterInsight(top3Sessions)?.let { insights.add(it) }
        }

        // Rule 3: Long-session dominance (uses overlap durations)
        if (insights.size < 2) {
            getLongSessionDominanceInsight(top3Sessions, totalUsageMillis, windowStart, windowEnd)?.let { insights.add(it) }
        }

        // Rule 4: Quick-return pattern (uses overlap durations for session validity check)
        if (insights.size < 2) {
            getQuickReturnInsight(allSessions, windowStart, windowEnd)?.let { insights.add(it) }
        }

        return if (insights.isEmpty()) null else insights.joinToString(" ")
    }

    /**
     * Rule 1: Check if same app appears in 2+ of top 3 longest sessions.
     */
    private fun getDominantAppInsight(top3Sessions: List<SessionInfo>): String? {
        if (top3Sessions.size < 2) return null

        val appCounts = top3Sessions.groupingBy { it.appName }.eachCount()
        val dominantApp = appCounts.entries.find { it.value >= 2 }

        return dominantApp?.let {
            "${it.key} shows up in most of your longest sessions today."
        }
    }

    /**
     * Rule 2: Check if 2+ of top 3 sessions cluster in same time window.
     * Windows: Morning (6-12), Afternoon (12-18), Evening (18-24), Late night (0-6)
     */
    private fun getTimeClusterInsight(top3Sessions: List<SessionInfo>): String? {
        if (top3Sessions.size < 2) return null

        val windowCounts = top3Sessions
            .map { getTimeWindow(it.startTimeMillis) }
            .groupingBy { it }
            .eachCount()

        val dominantWindow = windowCounts.entries.find { it.value >= 2 }

        return dominantWindow?.let {
            "Your longest sessions cluster in the ${it.key.displayName}."
        }
    }

    /**
     * Rule 3: Analyze ratio of top 3 session durations to total usage.
     * Uses OVERLAP durations (today's portion only) for cross-midnight accuracy.
     * >= 60%: usage dominated by long sessions
     * <= 30%: usage spread across many short sessions
     */
    private fun getLongSessionDominanceInsight(
        top3Sessions: List<SessionInfo>,
        totalUsageMillis: Long,
        windowStart: Long,
        windowEnd: Long
    ): String? {
        if (top3Sessions.isEmpty() || totalUsageMillis <= 0) return null

        // Use overlap duration for accurate cross-midnight calculation
        val top3TotalMillis = top3Sessions.sumOf { it.overlapDuration(windowStart, windowEnd) }
        val ratio = top3TotalMillis.toFloat() / totalUsageMillis

        return when {
            ratio >= 0.60f -> "Most of today's time came from a few long sessions (not quick checks)."
            ratio <= 0.30f -> "Today's usage is spread across many short sessions."
            else -> null
        }
    }

    /**
     * Rule 4: Check for quick-return pattern.
     * If any session starts within 60 minutes after the previous ended,
     * and both sessions have >= 2 minutes overlap with today.
     */
    private fun getQuickReturnInsight(
        allSessions: List<SessionInfo>,
        windowStart: Long,
        windowEnd: Long
    ): String? {
        if (allSessions.size < 2) return null

        val sortedSessions = allSessions.sortedBy { it.startTimeMillis }
        val minSessionDuration = 2 * 60 * 1000L // 2 minutes
        val quickReturnThreshold = 60 * 60 * 1000L // 60 minutes

        for (i in 0 until sortedSessions.size - 1) {
            val current = sortedSessions[i]
            val next = sortedSessions[i + 1]

            // Check if both sessions have >= 2 minutes overlap with today
            if (current.overlapDuration(windowStart, windowEnd) >= minSessionDuration &&
                next.overlapDuration(windowStart, windowEnd) >= minSessionDuration) {
                // Check if next session starts within 60 minutes of current ending
                val gap = next.startTimeMillis - current.endTimeMillis
                if (gap in 0..quickReturnThreshold) {
                    return "You tend to return soon after a session."
                }
            }
        }

        return null
    }

    /**
     * Determines the time window for a given timestamp.
     */
    private fun getTimeWindow(timeMillis: Long): TimeWindow {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = timeMillis
        }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        return when (hour) {
            in 0..5 -> TimeWindow.LATE_NIGHT
            in 6..11 -> TimeWindow.MORNING
            in 12..17 -> TimeWindow.AFTERNOON
            else -> TimeWindow.EVENING // 18-23
        }
    }
}
