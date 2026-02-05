package com.screentimetracker.app.focus.ui.schedule

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.text.Editable
import android.text.TextWatcher
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.screentimetracker.app.R
import com.screentimetracker.app.databinding.ActivityScheduleEditBinding
import com.screentimetracker.app.focus.data.FocusRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity for creating or editing a Focus Mode schedule.
 */
class ScheduleEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
    }

    private lateinit var binding: ActivityScheduleEditBinding
    private val viewModel: ScheduleEditViewModel by viewModels()
    private lateinit var blockedAppsAdapter: BlockedAppsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityScheduleEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if editing existing schedule
        val scheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L)
        if (scheduleId != -1L) {
            viewModel.loadSchedule(scheduleId)
        }

        setupToolbar()
        setupNameInput()
        setupDayChips()
        setupTimePickers()
        setupBlockedAppsList()
        observeViewModel()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.topAppBar)

        binding.topAppBar.setNavigationOnClickListener {
            finish()
        }

        // Update title based on mode
        if (intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L) != -1L) {
            binding.topAppBar.title = getString(R.string.focus_schedule_edit_title)
        } else {
            binding.topAppBar.title = getString(R.string.focus_schedule_new_title)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_schedule_edit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                viewModel.save()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupNameInput() {
        binding.scheduleNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.scheduleName.value = s?.toString() ?: ""
            }
        })
    }

    private fun setupDayChips() {
        // Map chips to day bits
        val dayChips = mapOf(
            binding.chipMon to FocusRepository.DAY_MONDAY,
            binding.chipTue to FocusRepository.DAY_TUESDAY,
            binding.chipWed to FocusRepository.DAY_WEDNESDAY,
            binding.chipThu to FocusRepository.DAY_THURSDAY,
            binding.chipFri to FocusRepository.DAY_FRIDAY,
            binding.chipSat to FocusRepository.DAY_SATURDAY,
            binding.chipSun to FocusRepository.DAY_SUNDAY
        )

        dayChips.forEach { (chip, dayBit) ->
            chip.setOnCheckedChangeListener { _, _ ->
                viewModel.toggleDay(dayBit)
            }
        }
    }

    private fun updateDayChips(daysOfWeekMask: Int) {
        val dayChips = mapOf(
            binding.chipMon to FocusRepository.DAY_MONDAY,
            binding.chipTue to FocusRepository.DAY_TUESDAY,
            binding.chipWed to FocusRepository.DAY_WEDNESDAY,
            binding.chipThu to FocusRepository.DAY_THURSDAY,
            binding.chipFri to FocusRepository.DAY_FRIDAY,
            binding.chipSat to FocusRepository.DAY_SATURDAY,
            binding.chipSun to FocusRepository.DAY_SUNDAY
        )

        dayChips.forEach { (chip, dayBit) ->
            // Remove listener before setting checked state
            chip.setOnCheckedChangeListener(null)
            chip.isChecked = (daysOfWeekMask and dayBit) != 0
            chip.setOnCheckedChangeListener { _, _ ->
                viewModel.toggleDay(dayBit)
            }
        }
    }

    private fun setupTimePickers() {
        binding.startTimeButton.setOnClickListener {
            showTimePicker(
                currentMinutes = viewModel.startTimeMinutes.value,
                title = "Start Time"
            ) { hour, minute ->
                viewModel.setStartTime(hour, minute)
            }
        }

        binding.endTimeButton.setOnClickListener {
            showTimePicker(
                currentMinutes = viewModel.endTimeMinutes.value,
                title = "End Time"
            ) { hour, minute ->
                viewModel.setEndTime(hour, minute)
            }
        }
    }

    private fun showTimePicker(
        currentMinutes: Int,
        title: String,
        onTimeSelected: (hour: Int, minute: Int) -> Unit
    ) {
        val currentHour = currentMinutes / 60
        val currentMinute = currentMinutes % 60

        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(currentHour)
            .setMinute(currentMinute)
            .setTitleText(title)
            .build()

        picker.addOnPositiveButtonClickListener {
            onTimeSelected(picker.hour, picker.minute)
        }

        picker.show(supportFragmentManager, "time_picker")
    }

    private fun setupBlockedAppsList() {
        blockedAppsAdapter = BlockedAppsAdapter(
            onToggle = { packageName, isBlocked ->
                viewModel.toggleApp(packageName, isBlocked)
            },
            isAppBlocked = { packageName ->
                viewModel.isAppBlocked(packageName)
            }
        )

        binding.blockedAppsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ScheduleEditActivity)
            adapter = blockedAppsAdapter
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe schedule name
                launch {
                    viewModel.scheduleName.collectLatest { name ->
                        if (binding.scheduleNameInput.text.toString() != name) {
                            binding.scheduleNameInput.setText(name)
                        }
                    }
                }

                // Observe days of week
                launch {
                    viewModel.daysOfWeekMask.collectLatest { mask ->
                        updateDayChips(mask)
                    }
                }

                // Observe start time
                launch {
                    viewModel.startTimeMinutes.collectLatest { minutes ->
                        binding.startTimeButton.text = ScheduleListViewModel.formatTime(minutes)
                    }
                }

                // Observe end time
                launch {
                    viewModel.endTimeMinutes.collectLatest { minutes ->
                        binding.endTimeButton.text = ScheduleListViewModel.formatTime(minutes)
                    }
                }

                // Observe installed apps
                launch {
                    viewModel.installedApps.collectLatest { apps ->
                        blockedAppsAdapter.submitList(apps)
                    }
                }

                // Observe blocked packages to update adapter
                launch {
                    viewModel.blockedPackages.collectLatest {
                        // Force adapter to rebind items when blocked packages change
                        blockedAppsAdapter.notifyDataSetChanged()
                    }
                }

                // Observe save result
                launch {
                    viewModel.saveResult.collectLatest { result ->
                        when (result) {
                            is ScheduleEditViewModel.SaveResult.Success -> {
                                Snackbar.make(
                                    binding.root,
                                    R.string.focus_schedule_saved,
                                    Snackbar.LENGTH_SHORT
                                ).show()
                                finish()
                            }
                            is ScheduleEditViewModel.SaveResult.Error -> {
                                Snackbar.make(
                                    binding.root,
                                    result.message,
                                    Snackbar.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }
}
