package com.screentimetracker.app.ui.apps

import android.app.Application
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import com.screentimetracker.app.data.prefs.UserPrefs
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.screentimetracker.app.data.model.AppUsageInfo
import java.util.Calendar

/**
 * ViewModel for the Most Used Apps screen.
 *
 * Handles querying UsageStatsManager for all tracked app usage data for today,
 * sorted by usage time descending.
 */
class MostUsedAppsViewModel(application: Application) : AndroidViewModel(application) {

    private val _appUsageList = MutableLiveData<List<AppUsageInfo>>()
    val appUsageList: LiveData<List<AppUsageInfo>> = _appUsageList

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val usageStatsManager: UsageStatsManager =
        application.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val packageManager: PackageManager = application.packageManager

    init {
        loadTodayUsage()
    }

    /**
     * Loads usage data for all tracked apps today.
     */
    fun loadTodayUsage() {
        _isLoading.value = true

        // Get time range for today (midnight to now)
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis

        // Query usage stats for today
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        // Process and filter the usage stats
        val appUsageList = processUsageStats(usageStatsList)

        _appUsageList.value = appUsageList
        _isLoading.value = false
    }

    /**
     * Processes raw UsageStats and returns all tracked apps sorted by usage time.
     */
    private fun processUsageStats(usageStatsList: List<UsageStats>?): List<AppUsageInfo> {
        if (usageStatsList.isNullOrEmpty()) {
            return emptyList()
        }

        // Aggregate usage time by package name
        val aggregatedUsage = usageStatsList
            .filter { stats ->
                UserPrefs.ALL_TRACKABLE_APPS.containsKey(stats.packageName) && stats.totalTimeInForeground > 0
            }
            .groupBy { it.packageName }
            .mapValues { (_, statsList) ->
                statsList.sumOf { it.totalTimeInForeground }
            }

        // Convert to AppUsageInfo and sort descending
        return aggregatedUsage
            .filter { (_, totalTime) -> totalTime > 0 }
            .map { (packageName, totalTime) ->
                val appName = UserPrefs.ALL_TRACKABLE_APPS[packageName] ?: getAppName(packageName)
                AppUsageInfo(
                    packageName = packageName,
                    appName = appName,
                    usageTimeMillis = totalTime
                )
            }
            .sortedByDescending { it.usageTimeMillis }
    }

    /**
     * Gets the user-friendly app name from PackageManager.
     */
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            UserPrefs.ALL_TRACKABLE_APPS[packageName] ?: packageName.substringAfterLast(".")
        }
    }

}
