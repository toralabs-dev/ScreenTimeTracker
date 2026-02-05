package com.screentimetracker.app.focus.ui

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.screentimetracker.app.R
import com.screentimetracker.app.databinding.ActivityFocusBlockBinding

/**
 * Activity that blocks access to apps during Focus Mode.
 *
 * Displays a full-screen blocking UI when the user tries to open a blocked app.
 * Shows schedule info and provides options to:
 * - Go Back: Return to home launcher
 * - Allow once: Grant short-lived temporary override (30 seconds)
 * - End schedule for today: Pause the schedule until midnight
 *
 * Launched by AppLaunchInterceptorService when detecting a blocked app launch.
 */
class FocusBlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFocusBlockBinding
    private val viewModel: FocusBlockViewModel by viewModels()

    // Extras from AccessibilityService
    private lateinit var packageName: String
    private var scheduleId: Long = 0L
    private var scheduleName: String = ""
    private var scheduleEndTime: Long? = null

    // Countdown timer for time remaining display
    private var countdownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityFocusBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!extractExtras()) {
            finish()
            return
        }

        setupWindowInsets()
        setupUI()
        setupListeners()
        observeViewModel()
        startCountdown()
    }

    /**
     * Extract intent extras passed from the accessibility service.
     * @return true if required extras are present, false otherwise
     */
    private fun extractExtras(): Boolean {
        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return false
        scheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, 0L)
        scheduleName = intent.getStringExtra(EXTRA_SCHEDULE_NAME) ?: ""
        scheduleEndTime = if (intent.hasExtra(EXTRA_SCHEDULE_END_TIME)) {
            intent.getLongExtra(EXTRA_SCHEDULE_END_TIME, 0L)
        } else {
            null
        }
        return true
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun setupUI() {
        // Load app name and icon from package manager
        try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            binding.appName.text = pm.getApplicationLabel(appInfo)
            binding.appIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
        } catch (e: Exception) {
            // Fallback to package name and default icon if app info unavailable
            binding.appName.text = packageName
            binding.appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        // Set schedule info
        if (scheduleName.isNotEmpty()) {
            binding.scheduleName.text = getString(R.string.focus_block_schedule_active, scheduleName)
            binding.scheduleName.visibility = View.VISIBLE
            // Update tertiary button text with schedule name
            binding.btnAllow15Min.text = getString(R.string.focus_block_end_today, scheduleName)
        } else {
            binding.scheduleName.visibility = View.GONE
            binding.btnAllow15Min.visibility = View.GONE
        }

        updateTimeRemaining()
    }

    private fun setupListeners() {
        binding.btnGoBack.setOnClickListener {
            viewModel.onGoBack(packageName, scheduleId)
        }

        binding.btnAllowOnce.setOnClickListener {
            viewModel.onAllowOnce(packageName, scheduleId)
        }

        binding.btnAllow15Min.setOnClickListener {
            viewModel.onEndScheduleToday(packageName, scheduleId)
        }
    }

    private fun observeViewModel() {
        viewModel.actionResult.observe(this) { result ->
            when (result) {
                is FocusBlockViewModel.ActionResult.GoBack -> {
                    goToHome()
                }
                is FocusBlockViewModel.ActionResult.OverrideGranted -> {
                    // Launch the blocked app now that override is granted
                    launchBlockedApp()
                }
                is FocusBlockViewModel.ActionResult.Error -> {
                    // Show error and go home
                    goToHome()
                }
            }
        }
    }

    /**
     * Launch the blocked app after override is granted.
     */
    private fun launchBlockedApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(launchIntent)
        }
        finish()
    }

    /**
     * Start countdown timer to update time remaining display.
     * Updates every minute until schedule ends.
     */
    private fun startCountdown() {
        val endTime = scheduleEndTime ?: return
        val remaining = endTime - System.currentTimeMillis()
        if (remaining <= 0) return

        countdownTimer = object : CountDownTimer(remaining, 60_000L) {
            override fun onTick(millisUntilFinished: Long) {
                updateTimeRemaining()
            }

            override fun onFinish() {
                // Schedule ended, allow access
                finish()
            }
        }.start()
    }

    /**
     * Update the time remaining display based on schedule end time.
     */
    private fun updateTimeRemaining() {
        val endTime = scheduleEndTime ?: run {
            binding.timeRemaining.visibility = View.GONE
            return
        }

        val remaining = endTime - System.currentTimeMillis()
        if (remaining <= 0) {
            binding.timeRemaining.text = getString(R.string.focus_block_time_remaining, "0m")
            return
        }

        val hours = remaining / (1000 * 60 * 60)
        val minutes = (remaining / (1000 * 60)) % 60

        val timeText = if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
        binding.timeRemaining.text = getString(R.string.focus_block_time_remaining, timeText)
        binding.timeRemaining.visibility = View.VISIBLE
    }

    /**
     * Navigate to the home launcher.
     */
    private fun goToHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Treat back press as "Go Back" action
        viewModel.onGoBack(packageName, scheduleId)
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
        const val EXTRA_SCHEDULE_NAME = "extra_schedule_name"
        const val EXTRA_SCHEDULE_END_TIME = "extra_schedule_end_time"
    }
}
