package com.screentimetracker.app.focus.ui.schedule

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.screentimetracker.app.R
import com.screentimetracker.app.databinding.ActivityScheduleListBinding
import com.screentimetracker.app.notifications.NotificationHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity for viewing and managing Focus Mode schedules.
 * Shows a list of all schedules with enable/disable toggles.
 */
class ScheduleListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScheduleListBinding
    private val viewModel: ScheduleListViewModel by viewModels()
    private lateinit var scheduleAdapter: ScheduleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityScheduleListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupBreakCard()
        setupSchedulesList()
        setupFab()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // Dismiss any lingering notification if accessibility service is working
        if (viewModel.isAccessibilityServiceEnabled()) {
            NotificationHelper.dismissFocusModeDisabledNotification(this)
        }
        checkAccessibilityServiceState()
    }

    /**
     * Checks if any schedules are enabled but the accessibility service is disabled.
     * This can happen after app updates when Android disables the accessibility service
     * for security reasons. Prompts the user to re-enable it.
     */
    private fun checkAccessibilityServiceState() {
        val hasEnabledSchedules = viewModel.schedules.value.any { it.schedule.isEnabled }
        if (hasEnabledSchedules && !viewModel.isAccessibilityServiceEnabled()) {
            showAccessibilityRequiredDialog(isReenableScenario = true)
        }
    }

    private fun setupToolbar() {
        binding.topAppBar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupBreakCard() {
        binding.takeBreakButton.setOnClickListener {
            showBreakDurationDialog()
        }
        binding.resumeNowButton.setOnClickListener {
            viewModel.resumeNow()
        }
    }

    private fun showBreakDurationDialog() {
        val options = arrayOf(
            getString(R.string.break_30_minutes),
            getString(R.string.break_1_hour),
            getString(R.string.break_2_hours),
            getString(R.string.break_until_tomorrow)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.take_a_break_title)
            .setItems(options) { _, which ->
                val duration = when (which) {
                    0 -> ScheduleListViewModel.BreakDuration.THIRTY_MINUTES
                    1 -> ScheduleListViewModel.BreakDuration.ONE_HOUR
                    2 -> ScheduleListViewModel.BreakDuration.TWO_HOURS
                    else -> ScheduleListViewModel.BreakDuration.UNTIL_TOMORROW
                }
                viewModel.startBreak(duration)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Shows a dialog prompting the user to enable the accessibility service.
     * @param isReenableScenario true if the service was previously enabled but got disabled
     *                          (e.g., after app update), false for first-time enablement
     */
    private fun showAccessibilityRequiredDialog(isReenableScenario: Boolean = false) {
        val messageRes = if (isReenableScenario) {
            R.string.focus_accessibility_disabled_message
        } else {
            R.string.focus_accessibility_required_message
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.focus_accessibility_required_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.focus_accessibility_open_settings) { _, _ ->
                viewModel.openAccessibilitySettings()
            }
            .setNegativeButton(R.string.focus_accessibility_cancel, null)
            .show()
    }

    private fun setupSchedulesList() {
        scheduleAdapter = ScheduleAdapter(
            onItemClick = { scheduleId ->
                openScheduleEdit(scheduleId)
            },
            onToggleEnabled = { scheduleId, enabled ->
                viewModel.toggleScheduleEnabled(scheduleId, enabled)
            }
        )

        binding.schedulesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ScheduleListActivity)
            adapter = scheduleAdapter
        }
    }

    private fun setupFab() {
        binding.fabAddSchedule.setOnClickListener {
            openScheduleEdit(null)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe break state
                launch {
                    viewModel.isOnBreak.collectLatest { onBreak ->
                        binding.takeBreakCard.visibility = if (onBreak) View.GONE else View.VISIBLE
                        binding.onBreakBanner.visibility = if (onBreak) View.VISIBLE else View.GONE
                    }
                }

                // Observe remaining break time
                launch {
                    viewModel.remainingMillis.collectLatest { millis ->
                        if (millis > 0) {
                            binding.breakRemainingText.text = getString(
                                R.string.break_remaining_format,
                                ScheduleListViewModel.formatRemainingTime(millis)
                            )
                        }
                    }
                }

                // Observe schedules list
                launch {
                    viewModel.schedules.collectLatest { schedules ->
                        scheduleAdapter.submitList(schedules)

                        // Show/hide empty state
                        if (schedules.isEmpty()) {
                            binding.emptyState.visibility = View.VISIBLE
                            binding.schedulesRecyclerView.visibility = View.GONE
                        } else {
                            binding.emptyState.visibility = View.GONE
                            binding.schedulesRecyclerView.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun openScheduleEdit(scheduleId: Long?) {
        val intent = Intent(this, ScheduleEditActivity::class.java)
        if (scheduleId != null) {
            intent.putExtra(ScheduleEditActivity.EXTRA_SCHEDULE_ID, scheduleId)
        }
        startActivity(intent)
    }

    /**
     * Shows a snackbar for deleted schedule with undo option.
     */
    fun showDeletedSnackbar(scheduleId: Long) {
        Snackbar.make(binding.root, R.string.focus_schedule_deleted, Snackbar.LENGTH_LONG).show()
    }
}
