package com.screentimetracker.app.ui.main

import android.content.pm.PackageManager
import android.view.View
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.screentimetracker.app.data.model.AppUsageInfo

/**
 * Controller responsible for managing app usage cards in the dashboard.
 * Extracted from MainActivity to reduce god class complexity.
 */
class AppCardsController(
    private val packageManager: PackageManager,
    private val headerView: View,
    private val card1: MaterialCardView,
    private val card2: MaterialCardView,
    private val card3: MaterialCardView,
    private val app1Name: MaterialTextView,
    private val app1Time: MaterialTextView,
    private val app2Name: MaterialTextView,
    private val app2Time: MaterialTextView,
    private val app3Name: MaterialTextView,
    private val app3Time: MaterialTextView,
    private val onAppClick: (packageName: String, appName: String) -> Unit
) {

    /**
     * Updates the top 3 apps cards with usage data.
     */
    fun updateTopApps(topApps: List<AppUsageInfo>) {
        // Hide all app cards initially
        card1.visibility = View.GONE
        card2.visibility = View.GONE
        card3.visibility = View.GONE

        // Show and populate cards for available apps
        if (topApps.isNotEmpty()) {
            headerView.visibility = View.VISIBLE
            bindAppCard(0, topApps.getOrNull(0))
            bindAppCard(1, topApps.getOrNull(1))
            bindAppCard(2, topApps.getOrNull(2))
        } else {
            headerView.visibility = View.GONE
        }
    }

    /**
     * Binds app data to the appropriate card view.
     * Only enables click handler if the app is still installed.
     */
    private fun bindAppCard(index: Int, appInfo: AppUsageInfo?) {
        if (appInfo == null) return

        // Only enable click if the app is still installed
        val clickListener = if (isPackageInstalled(appInfo.packageName)) {
            View.OnClickListener { onAppClick(appInfo.packageName, appInfo.appName) }
        } else {
            null
        }

        when (index) {
            0 -> {
                card1.visibility = View.VISIBLE
                app1Name.text = appInfo.appName
                app1Time.text = appInfo.getFormattedTime()
                card1.setOnClickListener(clickListener)
                card1.isClickable = clickListener != null
            }
            1 -> {
                card2.visibility = View.VISIBLE
                app2Name.text = appInfo.appName
                app2Time.text = appInfo.getFormattedTime()
                card2.setOnClickListener(clickListener)
                card2.isClickable = clickListener != null
            }
            2 -> {
                card3.visibility = View.VISIBLE
                app3Name.text = appInfo.appName
                app3Time.text = appInfo.getFormattedTime()
                card3.setOnClickListener(clickListener)
                card3.isClickable = clickListener != null
            }
        }
    }

    /**
     * Checks if a package is installed on the device.
     */
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
