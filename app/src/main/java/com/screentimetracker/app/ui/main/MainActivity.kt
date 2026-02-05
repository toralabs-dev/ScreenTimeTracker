package com.screentimetracker.app.ui.main

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.screentimetracker.app.focus.accessibility.AppLaunchInterceptorService
import com.screentimetracker.app.focus.data.FocusPrefs
import com.screentimetracker.app.ui.appdetail.AppDetailActivity
import com.screentimetracker.app.ui.apps.MostUsedAppsActivity
import com.screentimetracker.app.ui.permission.PermissionActivity
import com.screentimetracker.app.ui.settings.SettingsActivity
import com.screentimetracker.app.notifications.NotificationHelper
import com.screentimetracker.app.worker.UsageCheckWorker
import kotlinx.coroutines.launch
import com.screentimetracker.app.ui.chart.UsageMarkerView
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.screentimetracker.app.R
import com.screentimetracker.app.data.model.AppUsageInfo
import com.screentimetracker.app.data.model.DailyUsageEntry
import com.screentimetracker.app.data.model.DashboardState
import com.screentimetracker.app.data.model.Recommendation
import com.screentimetracker.app.data.model.RecommendationAction
import com.screentimetracker.app.data.model.RecommendationType
import com.screentimetracker.app.data.model.SessionInfo
import com.screentimetracker.app.data.model.TrendSummary
import com.screentimetracker.app.data.prefs.RecommendationPrefs
import com.screentimetracker.app.data.prefs.UserPrefs
import com.screentimetracker.app.data.usage.UsageSessionRepository
import com.screentimetracker.app.databinding.ActivityMainBinding
import com.screentimetracker.app.data.db.AppDatabase
import com.screentimetracker.app.focus.data.FocusRepository
import com.screentimetracker.app.focus.ui.schedule.ScheduleEditActivity
import com.google.android.material.snackbar.Snackbar

/**
 * MainActivity - Main dashboard of the app
 *
 * Displays screen time statistics including:
 * - Circular progress showing usage vs 2-hour daily limit
 * - Total time spent today on tracked social media apps
 * - Top 3 most-used apps with their usage times
 * - Daily usage trend chart for the past N days
 *
 * Uses ViewModel + LiveData architecture for reactive UI updates.
 */
class MainActivity : AppCompatActivity() {

    // ViewBinding for type-safe view access
    private lateinit var binding: ActivityMainBinding

    // ViewModel for dashboard data and business logic
    private val viewModel: DashboardViewModel by viewModels()

    // Focus Mode preferences for checking global enabled state
    private lateinit var focusPrefs: FocusPrefs

    // Recommendation preferences for dismiss tracking
    private lateinit var recommendationPrefs: RecommendationPrefs

    // Current recommendation for handling actions
    private var currentRecommendation: Recommendation? = null

    // Store daily entries for marker view access
    private var currentDailyEntries: List<DailyUsageEntry> = emptyList()

    // Store top apps for click handling
    private var currentTopApps: List<AppUsageInfo> = emptyList()

    // Store accessibility dialog reference for auto-dismiss
    private var focusAccessibilityDialog: androidx.appcompat.app.AlertDialog? = null

    // Permission launcher for POST_NOTIFICATIONS (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled - notifications will work if granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Inflate layout using ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Focus Mode preferences
        focusPrefs = FocusPrefs(this)

        // Initialize Recommendation preferences
        recommendationPrefs = RecommendationPrefs(this)

        // Handle window insets for edge-to-edge
        setupWindowInsets()

        // Setup chart configuration
        setupTrendChart()

        // Setup chip group listener for range selection
        setupTrendChipGroup()

        // Setup click listeners for navigation
        setupClickListeners()

        // Setup toolbar menu
        setupToolbarMenu()

        // Request notification permission on Android 13+
        requestNotificationPermissionIfNeeded()

        // Initialize notifications and background worker
        initializeNotifications()

        // Observe ViewModel data and update UI accordingly
        observeViewModel()
    }

    /**
     * Setup the toolbar menu with settings icon
     */
    private fun setupToolbarMenu() {
        binding.topAppBar.inflateMenu(R.menu.menu_main)
        binding.topAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_settings -> {
                    openSettings()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Request POST_NOTIFICATIONS permission on Android 13+ if not already granted.
     * This ensures users are prompted for notification permission at app startup.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Initialize notification channel and schedule background worker
     */
    private fun initializeNotifications() {
        // Create notification channel (required for Android 8.0+)
        NotificationHelper.createNotificationChannel(this)

        // Schedule periodic usage checks
        UsageCheckWorker.schedule(this)

        // Run an immediate check when app opens
        UsageCheckWorker.runImmediateCheck(this)
    }

    /**
     * Opens the Settings screen
     */
    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()

        // Check if usage access permission was revoked while app was in background
        if (!hasUsageAccessPermission()) {
            // Redirect to permission activity
            startActivity(Intent(this, PermissionActivity::class.java))
            finish()
            return
        }

        // Check if Focus Mode is enabled but accessibility service is disabled
        checkFocusAccessibilityStatus()

        // Refresh usage data every time the screen is shown
        // This ensures stats are up-to-date after returning from other apps
        viewModel.loadUsageData()
    }

    /**
     * Checks if Focus Mode is enabled in preferences but the accessibility service is disabled.
     * This can happen after app updates when Android disables the accessibility service for security.
     * Shows a dialog and notification prompting the user to re-enable it.
     */
    private fun checkFocusAccessibilityStatus() {
        lifecycleScope.launch {
            val focusEnabled = focusPrefs.isFocusModeGloballyEnabled()
            val a11yEnabled = AppLaunchInterceptorService.isEnabled(this@MainActivity)

            if (focusEnabled && !a11yEnabled) {
                showFocusAccessibilityDisabledDialog()
                // Also show notification so user sees it even if they dismiss dialog
                NotificationHelper.showFocusModeDisabledNotification(this@MainActivity)
            } else {
                // Auto-dismiss the dialog if accessibility is now enabled
                focusAccessibilityDialog?.dismiss()
                focusAccessibilityDialog = null
                // Auto-dismiss the notification if accessibility is now enabled (or focus mode disabled)
                NotificationHelper.dismissFocusModeDisabledNotification(this@MainActivity)
            }
        }
    }

    /**
     * Shows a dialog informing the user that the accessibility service needs to be re-enabled.
     */
    private fun showFocusAccessibilityDisabledDialog() {
        // Don't show if already showing
        if (focusAccessibilityDialog?.isShowing == true) return

        focusAccessibilityDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.focus_accessibility_required_title)
            .setMessage(R.string.focus_accessibility_disabled_message)
            .setPositiveButton(R.string.focus_accessibility_open_settings) { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton(R.string.focus_accessibility_cancel, null)
            .show()
    }

    /**
     * Opens the system accessibility settings screen.
     */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    /**
     * Checks if the app has usage access permission.
     */
    private fun hasUsageAccessPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
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

    /**
     * Setup window insets for edge-to-edge display
     */
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    /**
     * Configure the LineChart appearance and behavior
     */
    private fun setupTrendChart() {
        binding.trendLineChart.apply {
            // General chart settings
            description.isEnabled = false
            setTouchEnabled(true)
            setDrawGridBackground(false)
            isDragEnabled = true
            setScaleEnabled(false)
            setPinchZoom(false)

            // Disable legend (we have summary text instead)
            legend.isEnabled = false

            // Configure X-axis (dates)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textColor = MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
                textSize = 10f
            }

            // Configure left Y-axis (time in minutes)
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
                textColor = MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
                textSize = 10f
                axisMinimum = 0f
                // Format Y-axis labels as hours/minutes
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val minutes = value.toInt()
                        return if (minutes >= 60) {
                            "${minutes / 60}h"
                        } else {
                            "${minutes}m"
                        }
                    }
                }
            }

            // Disable right Y-axis
            axisRight.isEnabled = false

            // Add 2-hour limit line
            val errorColor = MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorError, Color.RED)
            val limitLine = LimitLine(120f, "2h limit").apply {
                lineWidth = 1f
                lineColor = errorColor
                textColor = errorColor
                textSize = 10f
                enableDashedLine(10f, 10f, 0f)
            }
            axisLeft.addLimitLine(limitLine)

            // Set custom marker for tap interaction
            // Using top-level class with lambda to avoid Activity memory leak
            marker = UsageMarkerView(this@MainActivity) { currentDailyEntries }

            // Extra padding for better appearance
            setExtraOffsets(8f, 8f, 8f, 8f)
        }
    }

    /**
     * Setup chip group listener for trend range selection
     */
    private fun setupTrendChipGroup() {
        binding.trendRangeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val days = when (checkedIds[0]) {
                    R.id.chip7Days -> 7
                    R.id.chip14Days -> 14
                    R.id.chip30Days -> 30
                    else -> 7
                }
                viewModel.loadDailyUsage(days)
            }
        }
    }

    /**
     * Setup click listeners for navigation to other screens
     */
    private fun setupClickListeners() {
        // "See all" button opens MostUsedAppsActivity
        binding.seeAllButton.setOnClickListener {
            openMostUsedApps()
        }

        // App card clicks will be set up when data is bound
        // See bindAppCard method for click handling
    }

    /**
     * Opens the Most Used Apps screen showing all tracked apps
     */
    private fun openMostUsedApps() {
        val intent = Intent(this, MostUsedAppsActivity::class.java)
        startActivity(intent)
    }

    /**
     * Opens the App Detail screen for a specific app
     */
    private fun openAppDetail(packageName: String, appName: String) {
        val intent = Intent(this, AppDetailActivity::class.java).apply {
            putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AppDetailActivity.EXTRA_APP_NAME, appName)
        }
        startActivity(intent)
    }

    /**
     * Observe LiveData from ViewModel and update UI when data changes
     */
    private fun observeViewModel() {
        viewModel.dashboardState.observe(this) { state ->
            updateUI(state)
        }
    }

    /**
     * Updates all UI elements based on the current dashboard state
     */
    private fun updateUI(state: DashboardState) {
        // Show/hide loading indicator
        binding.loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        if (state.isLoading) {
            return // Don't update other UI while loading
        }

        // Update circular progress indicator
        updateProgressIndicator(state)

        // Update the top apps list
        updateTopApps(state.topApps)

        // Update the longest sessions list
        updateLongestSessions(state.longestSessions)

        // Update the recommendation section (replaces old insight)
        updateRecommendation(state)

        // Update the trends section
        updateTrends(state.trendSummary, state.isTrendLoading)

        // Show empty state if no data is available
        updateEmptyState(state)
    }

    /**
     * Updates the circular progress indicator and related text
     */
    private fun updateProgressIndicator(state: DashboardState) {
        val percentage = state.getUsagePercentage()

        // Update circular progress (0-100)
        binding.circularProgress.progress = percentage.coerceAtMost(100)

        // Update total time text in center of circle
        binding.totalTimeText.text = state.getFormattedTotalTime()

        // Update percentage text below the circle with dynamic limit
        val limitFormatted = formatDuration(state.dailyLimitMillis)
        binding.percentageText.text = getString(R.string.dashboard_limit_format, percentage, limitFormatted)

        // Update status text (remaining or over limit)
        updateStatusText(state)

        // Change progress color based on limit status
        updateProgressColor(state.isOverLimit())
    }

    /**
     * Formats milliseconds as a human-readable duration string.
     */
    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    /**
     * Updates the status message below the progress indicator
     */
    private fun updateStatusText(state: DashboardState) {
        val remainingMs = state.dailyLimitMillis - state.totalUsageMillis

        if (state.isOverLimit()) {
            // Over limit - show how much over
            val overMs = -remainingMs
            val overMinutes = overMs / 1000 / 60
            val overHours = overMinutes / 60
            val overMins = overMinutes % 60

            val overTime = when {
                overHours > 0 -> "${overHours}h ${overMins}m"
                else -> "${overMins}m"
            }
            binding.statusText.text = getString(R.string.dashboard_over_limit, overTime)
            binding.statusText.setTextColor(
                MaterialColors.getColor(binding.statusText, com.google.android.material.R.attr.colorError)
            )
        } else {
            // Under limit - show remaining time
            val remainingMinutes = remainingMs / 1000 / 60
            val remainingHours = remainingMinutes / 60
            val remainingMins = remainingMinutes % 60

            val remainingTime = when {
                remainingHours > 0 -> "${remainingHours}h ${remainingMins}m"
                remainingMinutes > 0 -> "${remainingMins}m"
                else -> "0m"
            }
            binding.statusText.text = getString(R.string.dashboard_time_remaining, remainingTime)
            binding.statusText.setTextColor(
                MaterialColors.getColor(binding.statusText, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
        }
    }

    /**
     * Changes the progress indicator color based on whether user is over limit
     */
    private fun updateProgressColor(isOverLimit: Boolean) {
        val color = if (isOverLimit) {
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorError, Color.RED)
        } else {
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
        }
        binding.circularProgress.setIndicatorColor(color)
    }

    /**
     * Updates the top 3 apps cards with usage data
     */
    private fun updateTopApps(topApps: List<AppUsageInfo>) {
        // Store for click handling
        currentTopApps = topApps

        // Hide all app cards initially
        binding.app1Card.visibility = View.GONE
        binding.app2Card.visibility = View.GONE
        binding.app3Card.visibility = View.GONE

        // Show and populate cards for available apps
        if (topApps.isNotEmpty()) {
            binding.topAppsHeader.visibility = View.VISIBLE
            bindAppCard(0, topApps.getOrNull(0))
            bindAppCard(1, topApps.getOrNull(1))
            bindAppCard(2, topApps.getOrNull(2))
        } else {
            binding.topAppsHeader.visibility = View.GONE
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
            View.OnClickListener { openAppDetail(appInfo.packageName, appInfo.appName) }
        } else {
            null
        }

        when (index) {
            0 -> {
                binding.app1Card.visibility = View.VISIBLE
                binding.app1Name.text = appInfo.appName
                binding.app1Time.text = appInfo.getFormattedTime()
                binding.app1Card.setOnClickListener(clickListener)
                binding.app1Card.isClickable = clickListener != null
                if (appInfo.icon != null) {
                    binding.app1Icon.setImageDrawable(appInfo.icon)
                } else {
                    binding.app1Icon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
            1 -> {
                binding.app2Card.visibility = View.VISIBLE
                binding.app2Name.text = appInfo.appName
                binding.app2Time.text = appInfo.getFormattedTime()
                binding.app2Card.setOnClickListener(clickListener)
                binding.app2Card.isClickable = clickListener != null
                if (appInfo.icon != null) {
                    binding.app2Icon.setImageDrawable(appInfo.icon)
                } else {
                    binding.app2Icon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
            2 -> {
                binding.app3Card.visibility = View.VISIBLE
                binding.app3Name.text = appInfo.appName
                binding.app3Time.text = appInfo.getFormattedTime()
                binding.app3Card.setOnClickListener(clickListener)
                binding.app3Card.isClickable = clickListener != null
                if (appInfo.icon != null) {
                    binding.app3Icon.setImageDrawable(appInfo.icon)
                } else {
                    binding.app3Icon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
        }
    }

    /**
     * Updates the longest sessions cards with session data
     */
    private fun updateLongestSessions(sessions: List<SessionInfo>) {
        // Hide all session cards initially
        binding.session1Card.visibility = View.GONE
        binding.session2Card.visibility = View.GONE
        binding.session3Card.visibility = View.GONE

        // Show and populate cards for available sessions
        if (sessions.isNotEmpty()) {
            binding.longestSessionsTitle.visibility = View.VISIBLE
            bindSessionCard(0, sessions.getOrNull(0))
            bindSessionCard(1, sessions.getOrNull(1))
            bindSessionCard(2, sessions.getOrNull(2))
        } else {
            binding.longestSessionsTitle.visibility = View.GONE
        }
    }

    /**
     * Binds session data to the appropriate card view.
     * For cross-midnight sessions, shows "Today: Xm" to indicate only today's portion.
     */
    private fun bindSessionCard(index: Int, session: SessionInfo?) {
        if (session == null) return

        // Get window times for overlap calculation
        val windowStart = UsageSessionRepository.getStartOfTodayMillis()
        val windowEnd = UsageSessionRepository.getCurrentTimeMillis()

        // For cross-midnight sessions, show "Today: Xm"
        val durationText = if (session.startedBeforeWindow(windowStart)) {
            "Today: ${session.getFormattedOverlapDuration(windowStart, windowEnd)}"
        } else {
            session.getFormattedDuration()
        }

        when (index) {
            0 -> {
                binding.session1Card.visibility = View.VISIBLE
                binding.session1Name.text = session.appName
                binding.session1Duration.text = durationText
                binding.session1TimeRange.text = session.getFormattedTimeRange()
            }
            1 -> {
                binding.session2Card.visibility = View.VISIBLE
                binding.session2Name.text = session.appName
                binding.session2Duration.text = durationText
                binding.session2TimeRange.text = session.getFormattedTimeRange()
            }
            2 -> {
                binding.session3Card.visibility = View.VISIBLE
                binding.session3Name.text = session.appName
                binding.session3Duration.text = durationText
                binding.session3TimeRange.text = session.getFormattedTimeRange()
            }
        }
    }

    /**
     * Updates the recommendation card with smart recommendation.
     * Shows the card only if a recommendation is available.
     */
    private fun updateRecommendation(state: DashboardState) {
        val recommendation = state.recommendation
        currentRecommendation = recommendation

        if (recommendation != null) {
            binding.recommendationCard.visibility = View.VISIBLE
            binding.recommendationText.text = recommendation.insightText
            binding.recommendationCtaButton.text = recommendation.ctaText

            // Setup CTA button click handler
            binding.recommendationCtaButton.setOnClickListener {
                handleRecommendationCta(recommendation)
            }

            // Setup dismiss button click handler
            binding.dismissButton.setOnClickListener {
                dismissRecommendation(recommendation)
            }

            // Hide chevron icon for celebratory action (no navigation)
            if (recommendation.ctaAction is RecommendationAction.Celebratory) {
                binding.recommendationCtaButton.icon = null
            } else {
                binding.recommendationCtaButton.setIconResource(R.drawable.ic_chevron_right)
            }
        } else {
            binding.recommendationCard.visibility = View.GONE
        }
    }

    /**
     * Handle recommendation CTA button click.
     * Shows confirmation dialog and executes the appropriate action.
     */
    private fun handleRecommendationCta(recommendation: Recommendation) {
        when (val action = recommendation.ctaAction) {
            is RecommendationAction.CreateFocusSchedule -> {
                showScheduleConfirmationDialog(action)
            }
            is RecommendationAction.OpenSettings -> {
                openSettingsWithSuggestedLimit(action.suggestedLimitMillis)
            }
            is RecommendationAction.StartBreak -> {
                showBreakConfirmationDialog(action)
            }
            is RecommendationAction.Celebratory -> {
                // Just dismiss with a nice message
                dismissRecommendation(recommendation)
            }
        }
    }

    /**
     * Shows confirmation dialog for creating a Focus schedule.
     */
    private fun showScheduleConfirmationDialog(action: RecommendationAction.CreateFocusSchedule) {
        val startTimeFormatted = formatMinutesToTime(action.startTimeMinutes)
        val endTimeFormatted = formatMinutesToTime(action.endTimeMinutes)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_focus_schedule)
            .setMessage(getString(R.string.schedule_confirmation_message,
                startTimeFormatted,
                endTimeFormatted))
            .setPositiveButton(R.string.create) { _, _ ->
                createScheduleDirectly(action)
            }
            .setNeutralButton(R.string.customize) { _, _ ->
                openScheduleEditWithPrefill(action)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Shows confirmation dialog for starting a break.
     */
    private fun showBreakConfirmationDialog(action: RecommendationAction.StartBreak) {
        val appName = UserPrefs.ALL_TRACKABLE_APPS[action.packageName] ?: action.packageName

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.start_break_title)
            .setMessage(getString(R.string.start_break_message, appName))
            .setPositiveButton(R.string.start_break) { _, _ ->
                startAppBreak(action)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Creates a Focus schedule directly with the recommended settings.
     */
    private fun createScheduleDirectly(action: RecommendationAction.CreateFocusSchedule) {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getInstance(this@MainActivity)
                val focusRepository = FocusRepository(db.focusDao())

                focusRepository.createSchedule(
                    name = action.suggestedName,
                    daysOfWeekMask = action.daysOfWeekMask,
                    startTimeMinutes = action.startTimeMinutes,
                    endTimeMinutes = action.endTimeMinutes,
                    blockedPackages = action.blockedPackages
                )

                // Enable Focus Mode globally if not already enabled
                if (!focusPrefs.isFocusModeGloballyEnabled()) {
                    focusPrefs.setFocusModeGloballyEnabled(true)
                }

                Snackbar.make(
                    binding.root,
                    R.string.focus_schedule_saved,
                    Snackbar.LENGTH_SHORT
                ).show()

                // Mark action taken and clear from ViewModel state
                currentRecommendation?.let {
                    recommendationPrefs.markActionTaken(it.type)
                }
                viewModel.clearRecommendation()
                binding.recommendationCard.visibility = View.GONE
                currentRecommendation = null
            } catch (e: Exception) {
                Snackbar.make(
                    binding.root,
                    "Failed to create schedule",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Opens the schedule edit activity with pre-filled values.
     */
    private fun openScheduleEditWithPrefill(action: RecommendationAction.CreateFocusSchedule) {
        // For now, just open the schedule list - full prefill would require passing extras
        val intent = Intent(this, ScheduleEditActivity::class.java)
        // Note: To fully implement prefill, add extras to ScheduleEditActivity
        startActivity(intent)
    }

    /**
     * Opens Settings with an optional suggested limit highlighted.
     */
    private fun openSettingsWithSuggestedLimit(suggestedLimitMillis: Long?) {
        val intent = Intent(this, SettingsActivity::class.java)
        if (suggestedLimitMillis != null) {
            intent.putExtra("suggested_limit_millis", suggestedLimitMillis)
        }
        startActivity(intent)
    }

    /**
     * Starts a break from a specific app.
     */
    private fun startAppBreak(action: RecommendationAction.StartBreak) {
        lifecycleScope.launch {
            focusPrefs.startBreak(action.durationMillis)

            Snackbar.make(
                binding.root,
                R.string.take_a_break,
                Snackbar.LENGTH_SHORT
            ).show()

            // Mark action taken and clear from ViewModel state
            currentRecommendation?.let {
                recommendationPrefs.markActionTaken(it.type)
            }
            viewModel.clearRecommendation()
            binding.recommendationCard.visibility = View.GONE
            currentRecommendation = null
        }
    }

    /**
     * Dismisses the recommendation and prevents it from showing for 24 hours.
     */
    private fun dismissRecommendation(recommendation: Recommendation) {
        lifecycleScope.launch {
            recommendationPrefs.dismiss(recommendation.type)
        }

        // Clear from ViewModel state to prevent reappearing on state updates
        viewModel.clearRecommendation()

        // Hide the card immediately
        binding.recommendationCard.visibility = View.GONE
        currentRecommendation = null
    }

    /**
     * Formats minutes from midnight to "X:XX AM/PM" format.
     */
    private fun formatMinutesToTime(minutes: Int): String {
        val hour = minutes / 60
        val minute = minutes % 60

        val hourDisplay = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val amPm = if (hour < 12) "AM" else "PM"

        return if (minute == 0) {
            "$hourDisplay $amPm"
        } else {
            String.format("%d:%02d %s", hourDisplay, minute, amPm)
        }
    }

    /**
     * Updates the trends section with chart and summary data
     */
    private fun updateTrends(trendSummary: TrendSummary, isLoading: Boolean) {
        // Show trends title always (section is always visible)
        binding.trendsTitle.visibility = View.VISIBLE

        // Show/hide loading indicator
        binding.trendLoadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE

        if (isLoading) {
            // Show card but hide chart content while loading
            binding.trendsCard.visibility = View.VISIBLE
            binding.trendsEmptyCard.visibility = View.GONE
            return
        }

        // Check if there's data to display
        if (trendSummary.hasData()) {
            binding.trendsCard.visibility = View.VISIBLE
            binding.trendsEmptyCard.visibility = View.GONE

            // Update summary text
            binding.trendAverageText.text = getString(
                R.string.dashboard_trends_average,
                trendSummary.getFormattedAverage()
            )
            binding.trendWorstDayText.text = getString(
                R.string.dashboard_trends_worst_day,
                trendSummary.getFormattedWorstDay()
            )

            // Update the chart
            updateTrendChart(trendSummary.dailyEntries)

            // Update data coverage indicator
            updateDataCoverageIndicator(trendSummary)
        } else {
            binding.trendsCard.visibility = View.GONE
            binding.trendsEmptyCard.visibility = View.VISIBLE
        }
    }

    /**
     * Updates the data coverage indicator.
     * The partial data message is hidden - the chart visually communicates data availability
     * through subtle gray dots for days with no usage.
     */
    private fun updateDataCoverageIndicator(trendSummary: TrendSummary) {
        // Always hide the message - chart speaks for itself with visual encoding
        binding.dataCoverageText.visibility = View.GONE
        binding.trendLineChart.alpha = 1.0f
    }

    /**
     * Updates the LineChart with daily usage data.
     * Uses visual encoding for data points:
     * - Subtle gray dots: days with no usage data
     * - Primary color: normal usage days (under limit)
     * - Red dots: days exceeding the 2-hour limit
     */
    private fun updateTrendChart(dailyEntries: List<DailyUsageEntry>) {
        // Store entries for marker view
        currentDailyEntries = dailyEntries

        // Convert entries to chart data points
        // X-axis: index (0, 1, 2, ...), Y-axis: usage in minutes
        val entries = dailyEntries.mapIndexed { index, entry ->
            Entry(index.toFloat(), (entry.totalMillis / 1000 / 60).toFloat())
        }

        // Colors for different states
        val primaryColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
        val errorColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorError, Color.RED)
        // Use a very light gray for no-data dots to make them subtle/ghosted
        val noDataColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY)

        val limitMinutes = 120L // 2 hours

        // Determine circle colors per point based on usage
        val circleColors = dailyEntries.map { entry ->
            val minutes = entry.totalMillis / 1000 / 60
            when {
                minutes == 0L -> noDataColor      // No data: subtle gray
                minutes >= limitMinutes -> errorColor  // Over limit: red
                else -> primaryColor              // Normal: primary
            }
        }

        // Create dataset with Material 3 styling
        val dataSet = LineDataSet(entries, "Daily Usage").apply {
            color = primaryColor
            lineWidth = 2.5f
            setDrawCircles(true)
            circleRadius = 4f
            setCircleColors(circleColors)
            // Use solid dots (no holes) so light gray no-data dots appear subtle
            // rather than as prominent rings
            setDrawCircleHole(false)
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = primaryColor
            fillAlpha = 30
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.2f
            setDrawHighlightIndicators(true)
            highLightColor = primaryColor
            highlightLineWidth = 1f
        }

        // Update X-axis labels with dates
        binding.trendLineChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val index = value.toInt()
                return if (index in dailyEntries.indices) {
                    dailyEntries[index].getFormattedDate()
                } else {
                    ""
                }
            }
        }

        // Set label count based on data size to avoid crowding
        val labelCount = when {
            dailyEntries.size <= 7 -> dailyEntries.size
            dailyEntries.size <= 14 -> 7
            else -> 6
        }
        binding.trendLineChart.xAxis.labelCount = labelCount

        // Set data and refresh chart
        binding.trendLineChart.data = LineData(dataSet)
        binding.trendLineChart.invalidate()
        binding.trendLineChart.animateX(500)
    }

    /**
     * Shows or hides the empty state card based on data availability
     */
    private fun updateEmptyState(state: DashboardState) {
        if (!state.hasData && !state.isLoading) {
            binding.emptyStateCard.visibility = View.VISIBLE
            binding.topAppsHeader.visibility = View.GONE
            binding.longestSessionsTitle.visibility = View.GONE
        } else {
            binding.emptyStateCard.visibility = View.GONE
        }
    }

}
