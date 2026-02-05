package com.screentimetracker.app.ui.main

import android.content.Context
import com.screentimetracker.app.R
import com.screentimetracker.app.data.model.Recommendation
import com.screentimetracker.app.data.model.RecommendationAction
import com.screentimetracker.app.data.model.RecommendationType
import com.screentimetracker.app.data.model.SessionInfo
import com.screentimetracker.app.data.prefs.RecommendationPrefs
import com.screentimetracker.app.data.prefs.UserPrefs
import com.screentimetracker.app.data.usage.UsageSessionRepository
import com.screentimetracker.app.focus.data.FocusRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * Multi-day recommendation generator for contextual, actionable insights.
 *
 * Analysis Window: 3 days (today + 2 previous days)
 *
 * Priority order (first match wins):
 * 1. OVER_LIMIT_STREAK - If over limit 2+ consecutive days (urgent)
 * 2. PEAK_HOUR - If same 2-hour window has 40%+ more usage across 2+ days
 * 3. DOMINANT_APP - If one app is 50%+ of total usage across 3 days
 * 4. EVENING_USAGE - If 30%+ of usage is after 8 PM across 2+ days
 * 5. QUICK_RETURN - If user returns to same app within 30 min frequently
 * 6. WEEKEND_SPIKE - If weekend average is 50%+ higher than weekday
 * 7. IMPROVING - If 3-day average is 15%+ better than previous 3 days
 *
 * Early morning handling: If current time is before 10 AM and today has < 30 min usage,
 * rely entirely on previous days' data.
 */
class RecommendationGenerator(private val context: Context) {

    private val recommendationPrefs = RecommendationPrefs(context)

    companion object {
        // Analysis window
        const val ANALYSIS_DAYS = 3

        // Early morning thresholds
        private const val EARLY_MORNING_HOUR = 10
        private const val EARLY_MORNING_MIN_USAGE_MS = 30 * 60 * 1000L // 30 minutes

        // Pattern detection thresholds
        private const val PEAK_HOUR_INCREASE_THRESHOLD = 0.40 // 40% more usage
        private const val PEAK_HOUR_MIN_DAYS = 2 // Must appear in at least 2 days
        private const val DOMINANT_APP_THRESHOLD = 0.50 // 50% of total usage
        private const val EVENING_USAGE_THRESHOLD = 0.30 // 30% of usage after 8 PM
        private const val EVENING_HOUR_START = 20 // 8 PM
        private const val EVENING_MIN_DAYS = 2
        private const val OVER_LIMIT_MIN_DAYS = 2
        private const val QUICK_RETURN_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes
        private const val QUICK_RETURN_MIN_OCCURRENCES = 3
        private const val WEEKEND_SPIKE_THRESHOLD = 0.50 // 50% higher
        private const val IMPROVING_THRESHOLD = 0.15 // 15% improvement

        // Time window size for peak hour detection (2 hours)
        private const val PEAK_WINDOW_SIZE_HOURS = 2
    }

    /**
     * Data class for multi-day analysis results.
     */
    data class MultiDayData(
        val dayData: Map<LocalDate, UsageSessionRepository.DaySessionData>,
        val allSessions: List<SessionInfo>,
        val totalUsageMillis: Long,
        val dailyLimitMillis: Long,
        val useHistoricalOnly: Boolean // True if early morning with minimal today data
    )

    /**
     * Generate a recommendation based on multi-day session data.
     *
     * @param dayData Map of LocalDate to session data for each day
     * @param dailyLimitMillis User's daily limit setting
     * @return Recommendation if a pattern is detected, null otherwise
     */
    suspend fun generateRecommendation(
        dayData: Map<LocalDate, UsageSessionRepository.DaySessionData>,
        dailyLimitMillis: Long
    ): Recommendation? {
        if (dayData.isEmpty()) return null

        val today = LocalDate.now(ZoneId.systemDefault())
        val todayData = dayData[today]
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // Early morning handling: If before 10 AM and < 30 min usage today, exclude today
        val useHistoricalOnly = currentHour < EARLY_MORNING_HOUR &&
                (todayData?.totalOverlapMillis ?: 0L) < EARLY_MORNING_MIN_USAGE_MS

        val effectiveDayData = if (useHistoricalOnly) {
            dayData.filterKeys { it != today }
        } else {
            dayData
        }

        if (effectiveDayData.isEmpty()) return null

        val allSessions = effectiveDayData.values.flatMap { it.sessions }
        val totalUsageMillis = effectiveDayData.values.sumOf { it.totalOverlapMillis }

        val multiDayData = MultiDayData(
            dayData = effectiveDayData,
            allSessions = allSessions,
            totalUsageMillis = totalUsageMillis,
            dailyLimitMillis = dailyLimitMillis,
            useHistoricalOnly = useHistoricalOnly
        )

        // Check patterns in priority order
        return checkOverLimitStreak(multiDayData, dayData, dailyLimitMillis)
            ?: checkPeakHour(multiDayData)
            ?: checkDominantApp(multiDayData)
            ?: checkEveningUsage(multiDayData)
            ?: checkQuickReturn(multiDayData)
            ?: checkWeekendSpike(multiDayData)
            ?: checkImproving(dayData, dailyLimitMillis)
    }

    /**
     * Check if dismissed or action already taken before returning recommendation.
     * Once dismissed, a recommendation type stays dismissed for 24 hours.
     * If user took action (e.g., created a schedule), it stays suppressed until cleared.
     */
    private suspend fun checkNotDismissed(
        type: RecommendationType,
        createRecommendation: () -> Recommendation
    ): Recommendation? {
        // Don't show if user already took action on this recommendation type
        if (recommendationPrefs.hasActionBeenTaken(type)) {
            return null
        }
        // Don't show if dismissed within 24 hours
        if (recommendationPrefs.isDismissed(type)) {
            return null
        }
        return createRecommendation()
    }

    // ========== Pattern Detection Methods ==========

    /**
     * Rule 1: Over limit streak (2+ consecutive days over limit)
     */
    private suspend fun checkOverLimitStreak(
        data: MultiDayData,
        fullDayData: Map<LocalDate, UsageSessionRepository.DaySessionData>,
        dailyLimitMillis: Long
    ): Recommendation? {
        val sortedDays = fullDayData.entries.sortedByDescending { it.key }
        var consecutiveOverLimit = 0

        for ((_, dayData) in sortedDays) {
            if (dayData.totalOverlapMillis > dailyLimitMillis) {
                consecutiveOverLimit++
            } else {
                break
            }
        }

        if (consecutiveOverLimit < OVER_LIMIT_MIN_DAYS) return null

        // Suggest a slightly higher limit (add 30 min to current limit, cap at 6 hours)
        val suggestedLimit = minOf(dailyLimitMillis + 30 * 60 * 1000L, 6 * 60 * 60 * 1000L)
        val suggestedLimitFormatted = formatDuration(suggestedLimit)

        return checkNotDismissed(
            RecommendationType.OVER_LIMIT_STREAK
        ) {
            Recommendation(
                type = RecommendationType.OVER_LIMIT_STREAK,
                insightText = context.getString(R.string.rec_over_limit_streak, consecutiveOverLimit),
                ctaText = context.getString(R.string.cta_adjust_limit, suggestedLimitFormatted),
                ctaAction = RecommendationAction.OpenSettings(suggestedLimitMillis = suggestedLimit),
                priority = 100,
                metadata = mapOf(
                    "consecutiveDays" to consecutiveOverLimit,
                    "suggestedLimit" to suggestedLimit
                )
            )
        }
    }

    /**
     * Rule 2: Peak hour detection (40%+ more usage in same 2-hour window across 2+ days)
     */
    private suspend fun checkPeakHour(data: MultiDayData): Recommendation? {
        if (data.dayData.size < PEAK_HOUR_MIN_DAYS) return null

        // Analyze hourly usage across days
        val hourlyUsageByDay = mutableMapOf<LocalDate, MutableMap<Int, Long>>()

        for ((date, dayData) in data.dayData) {
            val hourlyUsage = mutableMapOf<Int, Long>()
            for (session in dayData.sessions) {
                // Distribute session time across hours it spans
                distributeSessionToHours(session, dayData.windowStart, dayData.windowEnd, hourlyUsage)
            }
            hourlyUsageByDay[date] = hourlyUsage
        }

        // Find 2-hour windows with consistently high usage
        for (startHour in 0..22) {
            val endHour = startHour + PEAK_WINDOW_SIZE_HOURS

            var daysWithPeak = 0
            var totalWindowUsage = 0L
            var totalDayUsage = 0L

            for ((date, hourlyUsage) in hourlyUsageByDay) {
                val dayTotal = data.dayData[date]?.totalOverlapMillis ?: 0L
                if (dayTotal < 10 * 60 * 1000L) continue // Skip days with < 10 min total

                val windowUsage = (startHour until endHour).sumOf { hourlyUsage[it] ?: 0L }
                val expectedAvg = dayTotal / 12.0 * PEAK_WINDOW_SIZE_HOURS // Average for 2-hour window

                if (windowUsage > expectedAvg * (1 + PEAK_HOUR_INCREASE_THRESHOLD)) {
                    daysWithPeak++
                }
                totalWindowUsage += windowUsage
                totalDayUsage += dayTotal
            }

            if (daysWithPeak >= PEAK_HOUR_MIN_DAYS && totalDayUsage > 0) {
                val percentageIncrease = ((totalWindowUsage.toDouble() / totalDayUsage * 12.0 / PEAK_WINDOW_SIZE_HOURS) - 1) * 100

                if (percentageIncrease >= PEAK_HOUR_INCREASE_THRESHOLD * 100) {
                    val startTimeFormatted = formatHour(startHour)
                    val endTimeFormatted = formatHour(endHour)

                    return checkNotDismissed(
                        RecommendationType.PEAK_HOUR
                    ) {
                        Recommendation(
                            type = RecommendationType.PEAK_HOUR,
                            insightText = context.getString(
                                R.string.rec_peak_hour,
                                percentageIncrease.toInt(),
                                startTimeFormatted,
                                endTimeFormatted
                            ),
                            ctaText = context.getString(R.string.cta_focus_at_time, startTimeFormatted),
                            ctaAction = RecommendationAction.CreateFocusSchedule(
                                suggestedName = context.getString(R.string.rec_schedule_name_focus, startTimeFormatted),
                                startTimeMinutes = startHour * 60,
                                endTimeMinutes = endHour * 60,
                                daysOfWeekMask = FocusRepository.WEEKDAYS,
                                blockedPackages = getTopApps(data.allSessions, 5)
                            ),
                            priority = 90,
                            metadata = mapOf(
                                "startHour" to startHour,
                                "endHour" to endHour,
                                "percentageIncrease" to percentageIncrease.toInt()
                            )
                        )
                    }
                }
            }
        }

        return null
    }

    /**
     * Rule 3: Dominant app (one app is 50%+ of total usage)
     */
    private suspend fun checkDominantApp(data: MultiDayData): Recommendation? {
        if (data.totalUsageMillis < 30 * 60 * 1000L) return null // Need at least 30 min total

        val appUsage = mutableMapOf<String, Long>()
        val appNames = mutableMapOf<String, String>()

        for (dayData in data.dayData.values) {
            for (session in dayData.sessions) {
                val overlap = session.overlapDuration(dayData.windowStart, dayData.windowEnd)
                appUsage[session.packageName] = (appUsage[session.packageName] ?: 0L) + overlap
                appNames[session.packageName] = session.appName
            }
        }

        val dominantApp = appUsage.entries.maxByOrNull { it.value } ?: return null
        val percentage = dominantApp.value.toDouble() / data.totalUsageMillis

        if (percentage < DOMINANT_APP_THRESHOLD) return null

        val appName = appNames[dominantApp.key] ?: return null

        return checkNotDismissed(
            RecommendationType.DOMINANT_APP
        ) {
            Recommendation(
                type = RecommendationType.DOMINANT_APP,
                insightText = context.getString(R.string.rec_dominant_app, appName, (percentage * 100).toInt()),
                ctaText = context.getString(R.string.cta_block_app, appName),
                ctaAction = RecommendationAction.CreateFocusSchedule(
                    suggestedName = context.getString(R.string.rec_schedule_name_work_hours),
                    startTimeMinutes = 9 * 60, // 9 AM
                    endTimeMinutes = 17 * 60, // 5 PM
                    daysOfWeekMask = FocusRepository.WEEKDAYS,
                    blockedPackages = listOf(dominantApp.key)
                ),
                priority = 80,
                metadata = mapOf(
                    "packageName" to dominantApp.key,
                    "appName" to appName,
                    "percentage" to (percentage * 100).toInt()
                )
            )
        }
    }

    /**
     * Rule 4: Evening usage (30%+ of usage after 8 PM)
     */
    private suspend fun checkEveningUsage(data: MultiDayData): Recommendation? {
        if (data.dayData.size < EVENING_MIN_DAYS) return null

        var daysWithEveningPattern = 0
        var totalEveningUsage = 0L
        var totalUsage = 0L

        for ((_, dayData) in data.dayData) {
            if (dayData.totalOverlapMillis < 15 * 60 * 1000L) continue // Skip days with < 15 min

            var eveningUsage = 0L
            for (session in dayData.sessions) {
                val eveningWindow = getEveningWindowForDay(dayData.windowStart)
                eveningUsage += session.overlapDuration(eveningWindow.first, eveningWindow.second)
            }

            val eveningRatio = eveningUsage.toDouble() / dayData.totalOverlapMillis
            if (eveningRatio >= EVENING_USAGE_THRESHOLD) {
                daysWithEveningPattern++
            }
            totalEveningUsage += eveningUsage
            totalUsage += dayData.totalOverlapMillis
        }

        if (daysWithEveningPattern < EVENING_MIN_DAYS || totalUsage == 0L) return null

        val avgEveningUsage = totalEveningUsage / daysWithEveningPattern
        val avgEveningFormatted = formatDuration(avgEveningUsage)

        return checkNotDismissed(
            RecommendationType.EVENING_USAGE
        ) {
            Recommendation(
                type = RecommendationType.EVENING_USAGE,
                insightText = context.getString(R.string.rec_evening_usage, avgEveningFormatted, formatHour(EVENING_HOUR_START)),
                ctaText = context.getString(R.string.cta_winddown, formatHour(EVENING_HOUR_START)),
                ctaAction = RecommendationAction.CreateFocusSchedule(
                    suggestedName = context.getString(R.string.rec_schedule_name_winddown),
                    startTimeMinutes = EVENING_HOUR_START * 60 + 30, // 8:30 PM
                    endTimeMinutes = 23 * 60, // 11 PM
                    daysOfWeekMask = FocusRepository.ALL_DAYS,
                    blockedPackages = getTopApps(data.allSessions, 5)
                ),
                priority = 70,
                metadata = mapOf(
                    "avgEveningUsageMillis" to avgEveningUsage,
                    "daysWithPattern" to daysWithEveningPattern
                )
            )
        }
    }

    /**
     * Rule 5: Quick return pattern (returning to same app within 30 min)
     */
    private suspend fun checkQuickReturn(data: MultiDayData): Recommendation? {
        val quickReturnCounts = mutableMapOf<String, Int>()
        val appNames = mutableMapOf<String, String>()

        for (dayData in data.dayData.values) {
            val sessionsByApp = dayData.sessions.groupBy { it.packageName }

            for ((packageName, sessions) in sessionsByApp) {
                if (sessions.size < 2) continue

                val sorted = sessions.sortedBy { it.startTimeMillis }
                for (i in 0 until sorted.size - 1) {
                    val gap = sorted[i + 1].startTimeMillis - sorted[i].endTimeMillis
                    if (gap in 0..QUICK_RETURN_THRESHOLD_MS) {
                        quickReturnCounts[packageName] = (quickReturnCounts[packageName] ?: 0) + 1
                        appNames[packageName] = sorted[i].appName
                    }
                }
            }
        }

        val topQuickReturn = quickReturnCounts.entries
            .filter { it.value >= QUICK_RETURN_MIN_OCCURRENCES }
            .maxByOrNull { it.value } ?: return null

        val appName = appNames[topQuickReturn.key] ?: return null
        val avgReturnMinutes = 30 // Approximation

        return checkNotDismissed(
            RecommendationType.QUICK_RETURN
        ) {
            Recommendation(
                type = RecommendationType.QUICK_RETURN,
                insightText = context.getString(R.string.rec_quick_return, appName, avgReturnMinutes),
                ctaText = context.getString(R.string.cta_take_break, appName),
                ctaAction = RecommendationAction.StartBreak(
                    packageName = topQuickReturn.key,
                    durationMillis = 60 * 60 * 1000L // 1 hour break
                ),
                priority = 60,
                metadata = mapOf(
                    "packageName" to topQuickReturn.key,
                    "appName" to appName,
                    "quickReturnCount" to topQuickReturn.value
                )
            )
        }
    }

    /**
     * Rule 6: Weekend spike (weekend usage 50%+ higher than weekdays)
     */
    private suspend fun checkWeekendSpike(data: MultiDayData): Recommendation? {
        val weekdayUsage = mutableListOf<Long>()
        val weekendUsage = mutableListOf<Long>()

        for ((date, dayData) in data.dayData) {
            val dayOfWeek = date.dayOfWeek.value // 1 = Monday, 7 = Sunday
            val isWeekend = dayOfWeek == 6 || dayOfWeek == 7 // Saturday or Sunday

            if (isWeekend) {
                weekendUsage.add(dayData.totalOverlapMillis)
            } else {
                weekdayUsage.add(dayData.totalOverlapMillis)
            }
        }

        // Need at least 1 weekend day and 1 weekday
        if (weekendUsage.isEmpty() || weekdayUsage.isEmpty()) return null

        val avgWeekend = weekendUsage.average()
        val avgWeekday = weekdayUsage.average()

        if (avgWeekday == 0.0) return null

        val increaseRatio = (avgWeekend - avgWeekday) / avgWeekday

        if (increaseRatio < WEEKEND_SPIKE_THRESHOLD) return null

        val multiplier = (avgWeekend / avgWeekday).let { "%.1f".format(it) }

        return checkNotDismissed(
            RecommendationType.WEEKEND_SPIKE
        ) {
            Recommendation(
                type = RecommendationType.WEEKEND_SPIKE,
                insightText = context.getString(R.string.rec_weekend_spike, multiplier),
                ctaText = context.getString(R.string.cta_weekend_focus),
                ctaAction = RecommendationAction.CreateFocusSchedule(
                    suggestedName = context.getString(R.string.rec_schedule_name_weekend),
                    startTimeMinutes = 10 * 60, // 10 AM
                    endTimeMinutes = 18 * 60, // 6 PM
                    daysOfWeekMask = FocusRepository.WEEKENDS,
                    blockedPackages = getTopApps(data.allSessions, 5)
                ),
                priority = 50,
                metadata = mapOf(
                    "weekendAvgMillis" to avgWeekend.toLong(),
                    "weekdayAvgMillis" to avgWeekday.toLong(),
                    "multiplier" to multiplier
                )
            )
        }
    }

    /**
     * Rule 7: Improving (3-day average is 15%+ better than previous period)
     * Note: This requires data from beyond the 3-day window, so we check against limit instead.
     */
    private suspend fun checkImproving(
        fullDayData: Map<LocalDate, UsageSessionRepository.DaySessionData>,
        dailyLimitMillis: Long
    ): Recommendation? {
        if (fullDayData.size < 2) return null

        val sortedDays = fullDayData.entries.sortedByDescending { it.key }
        val recentDays = sortedDays.take(3)

        // Calculate average of recent days
        val recentAvg = recentDays.map { it.value.totalOverlapMillis }.average()

        // Check if under limit and trending down
        val isUnderLimit = recentAvg < dailyLimitMillis
        val isDecreasing = if (recentDays.size >= 2) {
            recentDays[0].value.totalOverlapMillis <= recentDays[1].value.totalOverlapMillis
        } else false

        // Calculate percentage under limit
        val percentageUnderLimit = if (dailyLimitMillis > 0) {
            ((dailyLimitMillis - recentAvg) / dailyLimitMillis * 100).toInt()
        } else 0

        if (!isUnderLimit || percentageUnderLimit < 15 || !isDecreasing) return null

        return checkNotDismissed(
            RecommendationType.IMPROVING
        ) {
            Recommendation(
                type = RecommendationType.IMPROVING,
                insightText = context.getString(R.string.rec_improving, percentageUnderLimit),
                ctaText = context.getString(R.string.cta_keep_it_up),
                ctaAction = RecommendationAction.Celebratory,
                priority = 40,
                metadata = mapOf(
                    "percentageUnderLimit" to percentageUnderLimit,
                    "recentAvgMillis" to recentAvg.toLong()
                )
            )
        }
    }

    // ========== Helper Methods ==========

    /**
     * Distribute session time across hours it spans.
     */
    private fun distributeSessionToHours(
        session: SessionInfo,
        windowStart: Long,
        windowEnd: Long,
        hourlyUsage: MutableMap<Int, Long>
    ) {
        val calendar = Calendar.getInstance()
        val effectiveStart = maxOf(session.startTimeMillis, windowStart)
        val effectiveEnd = minOf(session.endTimeMillis, windowEnd)

        if (effectiveStart >= effectiveEnd) return

        calendar.timeInMillis = effectiveStart
        val startHour = calendar.get(Calendar.HOUR_OF_DAY)

        calendar.timeInMillis = effectiveEnd
        val endHour = calendar.get(Calendar.HOUR_OF_DAY)

        if (startHour == endHour) {
            hourlyUsage[startHour] = (hourlyUsage[startHour] ?: 0L) + (effectiveEnd - effectiveStart)
        } else {
            // Distribute across hours
            for (hour in startHour..endHour) {
                calendar.timeInMillis = effectiveStart
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val hourStart = calendar.timeInMillis

                calendar.add(Calendar.HOUR_OF_DAY, 1)
                val hourEnd = calendar.timeInMillis

                val overlapStart = maxOf(effectiveStart, hourStart)
                val overlapEnd = minOf(effectiveEnd, hourEnd)

                if (overlapEnd > overlapStart) {
                    hourlyUsage[hour] = (hourlyUsage[hour] ?: 0L) + (overlapEnd - overlapStart)
                }
            }
        }
    }

    /**
     * Get evening window (8 PM to midnight) for a given day.
     */
    private fun getEveningWindowForDay(dayStartMillis: Long): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = dayStartMillis
        calendar.set(Calendar.HOUR_OF_DAY, EVENING_HOUR_START)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        val eveningStart = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        val eveningEnd = calendar.timeInMillis

        return Pair(eveningStart, eveningEnd)
    }

    /**
     * Get top N apps by total usage.
     */
    private fun getTopApps(sessions: List<SessionInfo>, count: Int): List<String> {
        return sessions
            .groupBy { it.packageName }
            .mapValues { (_, sessions) -> sessions.sumOf { it.durationMillis } }
            .entries
            .sortedByDescending { it.value }
            .take(count)
            .map { it.key }
    }

    /**
     * Format hour as "X AM/PM".
     */
    private fun formatHour(hour: Int): String {
        return when {
            hour == 0 -> "12 AM"
            hour < 12 -> "$hour AM"
            hour == 12 -> "12 PM"
            else -> "${hour - 12} PM"
        }
    }

    /**
     * Format duration as "Xh Ym" or "Xm".
     */
    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "0m"
        }
    }
}
