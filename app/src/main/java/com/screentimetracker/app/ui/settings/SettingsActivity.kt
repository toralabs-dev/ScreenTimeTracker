package com.screentimetracker.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.screentimetracker.app.BuildConfig
import com.screentimetracker.app.R
import com.screentimetracker.app.data.prefs.UserPrefs
import com.screentimetracker.app.databinding.ActivitySettingsBinding
import com.screentimetracker.app.focus.ui.schedule.ScheduleListActivity
import com.screentimetracker.app.notifications.NotificationHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Settings screen for configuring daily limits, notifications, and tracked apps.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var trackedAppsAdapter: TrackedAppsAdapter

    // Track which switch triggered the permission request
    private var pendingNotificationSwitch: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // Permission denied - show dialog and revert switch
            showPermissionDeniedDialog()
            revertPendingSwitch()
        }
        pendingNotificationSwitch = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupDailyLimitSection()
        setupThemeSection()
        setupNotificationsSection()
        setupSnoozeSection()
        setupFocusModeSection()
        setupTrackedAppsSection()
        setupDataManagementSection()
        setupAboutSection()

        observeViewModel()
    }

    private fun setupToolbar() {
        binding.topAppBar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupDailyLimitSection() {
        binding.limitSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val minutes = value.toInt()
                updateLimitDisplay(minutes)
                viewModel.setDailyLimit(minutes * 60 * 1000L)
                showSettingsSavedFeedback()
            }
        }
    }

    private fun updateLimitDisplay(minutes: Int) {
        val hours = minutes / 60
        val mins = minutes % 60

        val displayText = when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            else -> "${mins}m"
        }

        binding.limitValueText.text = displayText
    }

    private fun setupThemeSection() {
        binding.themeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.themeLight -> UserPrefs.THEME_LIGHT
                R.id.themeDark -> UserPrefs.THEME_DARK
                else -> UserPrefs.THEME_SYSTEM
            }
            viewModel.setThemeMode(mode)
        }
    }

    private fun setupNotificationsSection() {
        binding.warningSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasNotificationPermission()) {
                pendingNotificationSwitch = "warning"
                requestNotificationPermission()
            } else {
                viewModel.setWarningNotificationsEnabled(isChecked)
                showSettingsSavedFeedback()
            }
        }

        binding.limitSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasNotificationPermission()) {
                pendingNotificationSwitch = "limit"
                requestNotificationPermission()
            } else {
                viewModel.setLimitNotificationsEnabled(isChecked)
                showSettingsSavedFeedback()
            }
        }
    }

    private fun setupSnoozeSection() {
        binding.snoozeButton.setOnClickListener {
            viewModel.snoozeNotifications()
        }
    }

    private fun setupFocusModeSection() {
        binding.focusModeCard.setOnClickListener {
            startActivity(Intent(this, ScheduleListActivity::class.java))
        }
    }

    private fun setupTrackedAppsSection() {
        trackedAppsAdapter = TrackedAppsAdapter { packageName, isTracked ->
            viewModel.setAppTracked(packageName, isTracked)
        }

        binding.trackedAppsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = trackedAppsAdapter
        }
    }

    private fun setupDataManagementSection() {
        binding.clearDataCard.setOnClickListener {
            showClearDataConfirmationDialog()
        }
    }

    private fun setupAboutSection() {
        // Privacy Policy link
        binding.privacyPolicyRow.setOnClickListener {
            openPrivacyPolicy()
        }

        // App version display
        binding.appVersionText.text = getString(R.string.settings_app_version, BuildConfig.VERSION_NAME)
    }

    private fun showClearDataConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_clear_data_confirm_title)
            .setMessage(R.string.settings_clear_data_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_clear_data) { _, _ ->
                viewModel.clearAllData()
            }
            .show()
    }

    private fun openPrivacyPolicy() {
        val url = getString(R.string.privacy_policy_url)
        try {
            // Try to open with Chrome Custom Tabs for better UX
            CustomTabsIntent.Builder()
                .build()
                .launchUrl(this, url.toUri())
        } catch (e: Exception) {
            // Fallback to regular browser intent
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Could not open privacy policy", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe daily limit
                launch {
                    viewModel.dailyLimitMillis.collectLatest { limitMillis ->
                        val limitMinutes = (limitMillis / 60_000).toInt()
                        binding.limitSlider.value = limitMinutes.toFloat().coerceIn(30f, 360f)
                        updateLimitDisplay(limitMinutes)
                    }
                }

                // Observe warning notifications enabled
                launch {
                    viewModel.warningNotificationsEnabled.collectLatest { enabled ->
                        if (binding.warningSwitch.isChecked != enabled) {
                            binding.warningSwitch.isChecked = enabled
                        }
                    }
                }

                // Observe limit notifications enabled
                launch {
                    viewModel.limitNotificationsEnabled.collectLatest { enabled ->
                        if (binding.limitSwitch.isChecked != enabled) {
                            binding.limitSwitch.isChecked = enabled
                        }
                    }
                }

                // Observe theme mode
                launch {
                    viewModel.themeMode.collectLatest { mode ->
                        val checkedId = when (mode) {
                            UserPrefs.THEME_LIGHT -> R.id.themeLight
                            UserPrefs.THEME_DARK -> R.id.themeDark
                            else -> R.id.themeSystem
                        }
                        if (binding.themeRadioGroup.checkedRadioButtonId != checkedId) {
                            binding.themeRadioGroup.check(checkedId)
                        }
                    }
                }

                // Observe snooze state
                launch {
                    viewModel.isSnoozed.collectLatest { isSnoozed ->
                        updateSnoozeUI(isSnoozed, viewModel.snoozeEndTimeFormatted.value)
                    }
                }

                launch {
                    viewModel.snoozeEndTimeFormatted.collectLatest { endTime ->
                        updateSnoozeUI(viewModel.isSnoozed.value, endTime)
                    }
                }

                // Observe tracked apps
                launch {
                    viewModel.trackedAppsList.collectLatest { apps ->
                        trackedAppsAdapter.submitList(apps)
                    }
                }

                // Observe data cleared event
                launch {
                    viewModel.dataCleared.collectLatest {
                        Snackbar.make(binding.root, R.string.settings_clear_data_success, Snackbar.LENGTH_LONG).show()
                        // Restart the activity to reflect cleared state
                        recreate()
                    }
                }
            }
        }
    }

    private fun updateSnoozeUI(isSnoozed: Boolean, endTimeFormatted: String?) {
        if (isSnoozed && endTimeFormatted != null) {
            binding.snoozeStatusText.text = getString(R.string.settings_snooze_active, endTimeFormatted)
            binding.snoozeButton.text = getString(R.string.settings_snooze_clear)
            binding.snoozeButton.setOnClickListener {
                viewModel.clearSnooze()
            }
        } else {
            binding.snoozeStatusText.text = getString(R.string.settings_snooze_desc)
            binding.snoozeButton.text = getString(R.string.settings_snooze_button)
            binding.snoozeButton.setOnClickListener {
                viewModel.snoozeNotifications()
            }
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun revertPendingSwitch() {
        when (pendingNotificationSwitch) {
            "warning" -> {
                binding.warningSwitch.isChecked = false
                viewModel.setWarningNotificationsEnabled(false)
            }
            "limit" -> {
                binding.limitSwitch.isChecked = false
                viewModel.setLimitNotificationsEnabled(false)
            }
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_denied_title)
            .setMessage(R.string.permission_denied_message)
            .setPositiveButton(R.string.permission_denied_settings) { _, _ ->
                openAppNotificationSettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAppNotificationSettings() {
        val intent = Intent().apply {
            action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    /**
     * Shows a brief Snackbar to confirm settings were saved.
     */
    private fun showSettingsSavedFeedback() {
        Snackbar.make(binding.root, R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
    }
}
