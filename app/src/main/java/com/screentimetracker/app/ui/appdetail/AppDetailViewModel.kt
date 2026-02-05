package com.screentimetracker.app.ui.appdetail

import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.screentimetracker.app.data.model.AppDetailState
import com.screentimetracker.app.data.model.DailyUsageEntry
import com.screentimetracker.app.data.model.SessionInfo
import com.screentimetracker.app.data.usage.UsageSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the App Detail screen.
 *
 * Handles loading detailed usage data for a specific app including:
 * - Today's total usage time
 * - Today's sessions (all sessions, sorted newest first)
 * - Per-app daily trend for selected date range
 */
class AppDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableLiveData<AppDetailState>()
    val state: LiveData<AppDetailState> = _state

    private val usageStatsManager: UsageStatsManager =
        application.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val packageManager: PackageManager = application.packageManager

    private var currentPackageName: String = ""
    private var currentAppName: String = ""

    /**
     * Initializes the ViewModel with the target app's package name.
     */
    fun init(packageName: String, appName: String) {
        currentPackageName = packageName
        currentAppName = appName
        loadTodayData()
        loadTrendData(7)
    }

    /**
     * Loads today's usage data for the specified app.
     */
    fun loadTodayData() {
        _state.value = _state.value?.copy(isLoading = true)
            ?: AppDetailState(
                packageName = currentPackageName,
                appName = currentAppName,
                isLoading = true
            )

        viewModelScope.launch {
            val (todayTotal, sessions) = withContext(Dispatchers.IO) {
                queryTodayUsage()
            }

            // Get window times for overlap calculation
            val windowStart = UsageSessionRepository.getStartOfTodayMillis()
            val windowEnd = UsageSessionRepository.getCurrentTimeMillis()

            // Find longest session by OVERLAP duration (not full duration)
            // A cross-midnight session with 7 min total but 5 min today counts as 5 min
            val longestOverlap = UsageSessionRepository.getLongestOverlapDuration(
                sessions, windowStart, windowEnd
            )

            val currentState = _state.value ?: AppDetailState()
            _state.value = currentState.copy(
                packageName = currentPackageName,
                appName = currentAppName,
                todayTotalMillis = todayTotal,
                sessionCountToday = sessions.size,
                longestSessionMillisToday = longestOverlap,
                sessionsToday = sessions.sortedByDescending { it.startTimeMillis },
                isLoading = false
            )
        }
    }

    /**
     * Loads trend data for the specified number of days.
     */
    fun loadTrendData(days: Int) {
        val currentState = _state.value ?: AppDetailState()
        _state.value = currentState.copy(isTrendLoading = true, selectedDays = days)

        viewModelScope.launch {
            val trendEntries = withContext(Dispatchers.IO) {
                queryDailyUsageForApp(days)
            }

            val latestState = _state.value ?: AppDetailState()
            _state.value = latestState.copy(
                trendEntries = trendEntries,
                selectedDays = days,
                isTrendLoading = false
            )
        }
    }

    /**
     * Queries today's total usage and sessions for the specified app.
     * Uses UsageSessionRepository for consistent midnight-aware session extraction.
     * Sessions preserve REAL start/end times but totals use OVERLAP duration.
     *
     * @return Pair of (total usage millis, list of sessions)
     */
    private fun queryTodayUsage(): Pair<Long, List<SessionInfo>> {
        // Get time range for today using java.time for consistent timezone handling
        val startTime = UsageSessionRepository.getStartOfTodayMillis()
        val endTime = UsageSessionRepository.getCurrentTimeMillis()

        // Query sessions using midnight-aware repository
        // Returns sessions with REAL timestamps that overlap with today
        val sessions = UsageSessionRepository.getSessionsForAppOverlappingWindow(
            usageStatsManager = usageStatsManager,
            packageName = currentPackageName,
            appName = currentAppName,
            windowStart = startTime,
            windowEnd = endTime
        )

        // Compute total from sessions using OVERLAP duration (not full duration)
        // Cross-midnight sessions only count today's portion
        val sessionDerivedTotal = UsageSessionRepository.computeTotalOverlapFromSessions(
            sessions, startTime, endTime
        )

        // Also query UsageStats for comparison
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        val usageStatsTotal = usageStatsList
            ?.filter { it.packageName == currentPackageName }
            ?.sumOf { it.totalTimeInForeground }
            ?: 0L

        // Use the larger value to ensure consistency
        // This ensures Total Time is never less than what sessions show
        val todayTotal = maxOf(usageStatsTotal, sessionDerivedTotal)

        return Pair(todayTotal, sessions)
    }

    /**
     * Queries daily usage for the specific app over the past N days.
     * Uses java.time for consistent timezone handling.
     */
    private fun queryDailyUsageForApp(days: Int): List<DailyUsageEntry> {
        val entries = mutableListOf<DailyUsageEntry>()
        val now = System.currentTimeMillis()

        for (daysAgo in (days - 1) downTo 0) {
            // Calculate day boundaries using repository for consistent timezone handling
            val dayReference = now - (daysAgo * 24 * 60 * 60 * 1000L)
            val dayStart = UsageSessionRepository.getStartOfDayMillis(dayReference)
            val dayEnd = UsageSessionRepository.getEndOfDayMillis(dayReference)

            val totalMillis = queryDayUsageForApp(dayStart, dayEnd)

            entries.add(
                DailyUsageEntry(
                    dateMillis = dayStart,
                    totalMillis = totalMillis
                )
            )
        }

        return entries
    }

    /**
     * Queries usage for the specific app within a day range.
     */
    private fun queryDayUsageForApp(startTime: Long, endTime: Long): Long {
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        return usageStatsList
            ?.filter { it.packageName == currentPackageName }
            ?.sumOf { it.totalTimeInForeground }
            ?: 0L
    }
}
