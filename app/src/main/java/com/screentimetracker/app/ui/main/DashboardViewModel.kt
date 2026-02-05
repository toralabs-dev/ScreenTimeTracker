package com.screentimetracker.app.ui.main

import android.app.Application
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.screentimetracker.app.data.model.AppUsageInfo
import com.screentimetracker.app.data.model.DailyUsageEntry
import com.screentimetracker.app.data.model.DashboardState
import com.screentimetracker.app.data.model.Recommendation
import com.screentimetracker.app.data.model.TrendSummary
import com.screentimetracker.app.data.prefs.UserPrefs
import com.screentimetracker.app.data.repository.UsageRepository
import com.screentimetracker.app.data.usage.UsageSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Dashboard screen.
 *
 * Handles querying UsageStatsManager for app usage data and transforming
 * it into a format suitable for display. Uses AndroidViewModel to access
 * application context for PackageManager operations.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    // LiveData for dashboard state - observed by the Activity
    private val _dashboardState = MutableLiveData<DashboardState>()
    val dashboardState: LiveData<DashboardState> = _dashboardState

    // UsageStatsManager instance for querying usage data
    private val usageStatsManager: UsageStatsManager =
        application.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    // PackageManager for getting app names
    private val packageManager: PackageManager = application.packageManager

    // User preferences for settings
    private val userPrefs: UserPrefs = UserPrefs(application)

    // Usage repository for hybrid data access (system + local database)
    private var usageRepository: UsageRepository? = null

    // Recommendation generator for multi-day pattern analysis
    private val recommendationGenerator = RecommendationGenerator(application)

    // Track the currently selected number of days for trends
    private var currentTrendDays = DEFAULT_TREND_DAYS

    // Track if recommendation has been dismissed this session (to prevent reappearing on state updates)
    private var isRecommendationDismissed = false

    // Cached preferences values
    private var cachedTrackedPackages: Set<String> = UserPrefs.DEFAULT_TRACKED_PACKAGES
    private var cachedDailyLimitMillis: Long = UserPrefs.DEFAULT_DAILY_LIMIT_MILLIS

    init {
        // Load initial data
        loadUsageData()
        loadDailyUsage(DEFAULT_TREND_DAYS)
    }

    /**
     * Refreshes usage data from UsageStatsManager.
     * Called from onResume() in the Activity to update stats each time the screen is shown.
     *
     * Uses UsageSessionRepository for consistent midnight-aware session extraction.
     * Sessions that cross midnight are properly clipped to today's window.
     */
    fun loadUsageData() {
        // Preserve existing trend data while loading
        val currentState = _dashboardState.value
        _dashboardState.value = DashboardState(
            isLoading = true,
            trendSummary = currentState?.trendSummary ?: TrendSummary()
        )

        viewModelScope.launch {
            // Load preferences first
            cachedTrackedPackages = userPrefs.trackedPackages.first()
            cachedDailyLimitMillis = userPrefs.dailyLimitMillis.first()

            // Check if initial sync needed (first launch)
            val needsInitialSync = !userPrefs.hasCompletedInitialSync.first()
            if (needsInitialSync) {
                val repo = getOrCreateUsageRepository()
                repo.syncPastDays(7)  // Backfill 7 days of available history
                userPrefs.setInitialSyncCompleted()
            }

            // Get time range for today using java.time for consistent timezone handling
            val startTime = UsageSessionRepository.getStartOfTodayMillis()
            val endTime = UsageSessionRepository.getCurrentTimeMillis()

            // Query usage stats for today (used for per-app breakdown)
            val usageStatsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            // Process the usage stats for per-app data
            val appUsageList = processUsageStats(usageStatsList)

            // Get top 3 apps sorted by usage time
            val topApps = appUsageList
                .sortedByDescending { it.usageTimeMillis }
                .take(3)

            // Query ALL individual sessions using midnight-aware repository
            // This handles cross-midnight sessions by querying with a buffer and clipping
            val allSessions = UsageSessionRepository.getSessionsForWindow(
                usageStatsManager = usageStatsManager,
                windowStart = startTime,
                windowEnd = endTime,
                trackedPackages = cachedTrackedPackages,
                appNameResolver = { packageName -> UserPrefs.ALL_TRACKABLE_APPS[packageName] ?: getAppName(packageName) }
            )

            // Compute total from sessions using OVERLAP duration (not full session duration)
            // This ensures cross-midnight sessions only count today's portion
            val sessionDerivedTotal = UsageSessionRepository.computeTotalOverlapFromSessions(
                allSessions, startTime, endTime
            )

            // Use the larger of UsageStats total vs session-derived total
            // UsageStats may have more granular tracking, but sessions provide consistency
            val usageStatsTotal = appUsageList.sumOf { it.usageTimeMillis }
            val totalUsage = maxOf(usageStatsTotal, sessionDerivedTotal)

            // Get top 3 longest sessions by TODAY's OVERLAP duration (not full session duration)
            // A session that started at 11:58pm yesterday and ran until 12:05am today
            // has 7 min full duration but only 5 min overlap with today
            val longestSessions = allSessions
                .sortedByDescending { it.overlapDuration(startTime, endTime) }
                .take(3)

            // Generate smart recommendation using multi-day pattern analysis
            // Query 3 days of data for stable pattern detection
            // Skip if recommendation was already dismissed this session
            val recommendation = if (isRecommendationDismissed) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    generateRecommendation(cachedDailyLimitMillis)
                }
            }

            // Update state, preserving trend data from the LATEST state (not the captured one)
            // This prevents race conditions with loadDailyUsage() running concurrently
            val latestState = _dashboardState.value
            _dashboardState.value = DashboardState(
                totalUsageMillis = totalUsage,
                dailyLimitMillis = cachedDailyLimitMillis,
                topApps = topApps,
                longestSessions = longestSessions,
                sessionsTodayAll = allSessions,
                recommendation = recommendation,
                trendSummary = latestState?.trendSummary ?: TrendSummary(),
                isLoading = false,
                isTrendLoading = latestState?.isTrendLoading ?: false,
                hasData = appUsageList.isNotEmpty() || allSessions.isNotEmpty()
            )
        }
    }

    /**
     * Generate a smart recommendation based on multi-day session data.
     * Uses 3 days of data for stable pattern detection.
     */
    private suspend fun generateRecommendation(dailyLimitMillis: Long): Recommendation? {
        val dayData = UsageSessionRepository.getSessionsForPastDays(
            usageStatsManager = usageStatsManager,
            days = RecommendationGenerator.ANALYSIS_DAYS,
            trackedPackages = cachedTrackedPackages,
            appNameResolver = { packageName -> UserPrefs.ALL_TRACKABLE_APPS[packageName] ?: getAppName(packageName) }
        )

        return recommendationGenerator.generateRecommendation(dayData, dailyLimitMillis)
    }

    /**
     * Loads daily usage data for the past N days on a background thread.
     * Updates the trendSummary in dashboard state when complete.
     *
     * Uses UsageRepository for hybrid data access:
     * - Days 0-7: Uses UsageStatsManager (most accurate)
     * - Days 8+: Falls back to local database (persisted data)
     *
     * @param days Number of days to load (7, 14, or 30)
     */
    fun loadDailyUsage(days: Int) {
        currentTrendDays = days

        // Set trend loading state
        val currentState = _dashboardState.value
        _dashboardState.value = currentState?.copy(
            isTrendLoading = true,
            trendSummary = currentState.trendSummary.copy(selectedDays = days)
        ) ?: DashboardState(isTrendLoading = true)

        viewModelScope.launch {
            // Ensure repository is initialized with current tracked packages
            val repo = getOrCreateUsageRepository()

            val dailyEntries = withContext(Dispatchers.IO) {
                repo.getDailyUsageForDays(days)
            }

            // Calculate summary statistics
            val entriesWithData = dailyEntries.filter { it.totalMillis > 0 }
            val averageMillis = if (entriesWithData.isNotEmpty()) {
                entriesWithData.sumOf { it.totalMillis } / entriesWithData.size
            } else {
                0L
            }
            val worstDay = dailyEntries.maxByOrNull { it.totalMillis }

            val trendSummary = TrendSummary(
                dailyEntries = dailyEntries,
                averageMillis = averageMillis,
                worstDayEntry = worstDay,
                selectedDays = days
            )

            // Update state on main thread
            val latestState = _dashboardState.value
            _dashboardState.value = latestState?.copy(
                trendSummary = trendSummary,
                isTrendLoading = false
            ) ?: DashboardState(trendSummary = trendSummary, isTrendLoading = false)
        }
    }

    /**
     * Clears the current recommendation from the state.
     * Called when user dismisses or takes action on a recommendation.
     * Prevents the recommendation from reappearing on subsequent state updates.
     */
    fun clearRecommendation() {
        isRecommendationDismissed = true
        val currentState = _dashboardState.value
        if (currentState != null) {
            _dashboardState.value = currentState.copy(recommendation = null)
        }
    }

    /**
     * Gets or creates the UsageRepository instance.
     * Lazy initialization ensures tracked packages are loaded first.
     */
    private suspend fun getOrCreateUsageRepository(): UsageRepository {
        return usageRepository ?: run {
            // Load tracked packages if not already cached
            if (cachedTrackedPackages == UserPrefs.DEFAULT_TRACKED_PACKAGES) {
                cachedTrackedPackages = userPrefs.trackedPackages.first()
            }
            UsageRepository(
                context = getApplication(),
                usageStatsManager = usageStatsManager,
                trackedPackages = cachedTrackedPackages
            ).also { usageRepository = it }
        }
    }

    /**
     * Processes raw UsageStats and filters/transforms them into AppUsageInfo objects.
     * Aggregates usage time per app (same package name) and only includes apps from
     * our tracked list that have actual usage time.
     */
    private fun processUsageStats(usageStatsList: List<UsageStats>?): List<AppUsageInfo> {
        if (usageStatsList.isNullOrEmpty()) {
            return emptyList()
        }

        // Aggregate usage time by package name (using cached tracked packages)
        val aggregatedUsage = usageStatsList
            .filter { stats ->
                cachedTrackedPackages.contains(stats.packageName) && stats.totalTimeInForeground > 0
            }
            .groupBy { it.packageName }
            .mapValues { (_, statsList) ->
                statsList.sumOf { it.totalTimeInForeground }
            }

        // Convert aggregated data to AppUsageInfo objects
        return aggregatedUsage
            .filter { (_, totalTime) -> totalTime > 0 }
            .map { (packageName, totalTime) ->
                val appName = UserPrefs.ALL_TRACKABLE_APPS[packageName] ?: getAppName(packageName)
                val icon = try {
                    packageManager.getApplicationIcon(packageName)
                } catch (e: Exception) {
                    null
                }

                AppUsageInfo(
                    packageName = packageName,
                    appName = appName,
                    usageTimeMillis = totalTime,
                    icon = icon
                )
            }
    }

    /**
     * Gets the user-friendly app name from PackageManager.
     * Falls back to our tracked apps map or package name if app is not accessible.
     */
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            // App not accessible, use display name from our map or package name
            UserPrefs.ALL_TRACKABLE_APPS[packageName] ?: packageName.substringAfterLast(".")
        }
    }

    companion object {
        // Default daily limit: 2 hours in milliseconds
        const val DEFAULT_DAILY_LIMIT_MS = 2 * 60 * 60 * 1000L

        // Default number of days for trend chart
        const val DEFAULT_TREND_DAYS = 7
    }
}
