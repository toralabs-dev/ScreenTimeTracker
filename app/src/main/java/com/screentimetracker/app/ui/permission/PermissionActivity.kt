package com.screentimetracker.app.ui.permission

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.screentimetracker.app.R
import com.screentimetracker.app.databinding.ActivityPermissionBinding
import com.screentimetracker.app.ui.main.MainActivity

/**
 * PermissionActivity - Entry point of the app
 *
 * This activity handles the PACKAGE_USAGE_STATS permission flow:
 * 1. Checks if the permission is already granted
 * 2. If granted, redirects to MainActivity immediately
 * 3. If not granted, shows an explanation and button to open settings
 * 4. Monitors permission state when user returns from settings
 *
 * The PACKAGE_USAGE_STATS permission is special - it can't be requested via
 * runtime permission dialog. Users must manually enable it in system settings.
 */
class PermissionActivity : AppCompatActivity() {

    // ViewBinding for type-safe view access
    private lateinit var binding: ActivityPermissionBinding

    // Activity result launcher for settings navigation
    // We use this to know when the user returns from settings
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Check permission when user returns from settings
        checkPermissionAndProceed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Inflate layout using ViewBinding
        binding = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle window insets for edge-to-edge
        setupWindowInsets()

        // Setup click listener for the permission button
        binding.grantPermissionButton.setOnClickListener {
            if (hasUsageStatsPermission()) {
                navigateToMain()
            } else {
                openUsageAccessSettings()
            }
        }

        // Check if permission is already granted
        checkPermissionAndProceed()
    }

    override fun onResume() {
        super.onResume()
        // Re-check permission every time activity resumes
        // This handles cases where user grants permission via settings directly
        checkPermissionAndProceed()
    }

    /**
     * Setup window insets for edge-to-edge display
     * Applies appropriate padding to avoid system bars overlapping content
     */
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    /**
     * Check if PACKAGE_USAGE_STATS permission is granted
     *
     * This permission is checked via AppOpsManager, not the regular permission system.
     * MODE_ALLOWED means the user has granted usage access in settings.
     *
     * @return true if permission is granted, false otherwise
     */
    private fun hasUsageStatsPermission(): Boolean {
        val appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        // Check the operation mode for USAGE_STATS
        val mode = appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )

        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Check permission and update UI or navigate to main screen
     *
     * If permission is granted:
     * - Update UI to show granted state briefly
     * - Navigate to MainActivity
     *
     * If permission is not granted:
     * - Show the permission request UI
     * - Display status chip indicating permission is required
     */
    private fun checkPermissionAndProceed() {
        if (hasUsageStatsPermission()) {
            // Permission granted - update UI to show success state
            updateUIForGrantedState()
            // Navigate to main after a brief delay to show the success state
            binding.root.postDelayed({
                navigateToMain()
            }, 500)
        } else {
            // Permission not granted - show request UI
            updateUIForRequestState()
        }
    }

    /**
     * Update UI to show that permission is granted
     */
    private fun updateUIForGrantedState() {
        binding.apply {
            titleText.text = getString(R.string.permission_granted_title)
            descriptionText.text = getString(R.string.permission_granted_description)
            grantPermissionButton.text = getString(R.string.permission_button_continue)

            // Update status chip to show granted state
            statusChip.apply {
                visibility = View.VISIBLE
                text = "Permission Granted"
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                        this@PermissionActivity,
                        com.google.android.material.R.attr.colorPrimaryContainer,
                        android.graphics.Color.GREEN
                    )
                )
                setChipIconResource(android.R.drawable.ic_menu_info_details)
            }
        }
    }

    /**
     * Update UI to show permission request state
     */
    private fun updateUIForRequestState() {
        binding.apply {
            titleText.text = getString(R.string.permission_title)
            descriptionText.text = getString(R.string.permission_description)
            grantPermissionButton.text = getString(R.string.permission_button_grant)

            // Show status chip indicating permission is needed
            statusChip.apply {
                visibility = View.VISIBLE
                text = "Permission Required"
            }
        }
    }

    /**
     * Open system Usage Access Settings
     *
     * This opens the system settings page where users can enable usage access
     * for our app. This is the only way to grant PACKAGE_USAGE_STATS permission.
     */
    private fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

        // Use the launcher so we get a callback when user returns
        settingsLauncher.launch(intent)
    }

    /**
     * Navigate to MainActivity
     *
     * Called when permission is granted. Finishes this activity so user
     * can't go back to the permission screen.
     */
    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish() // Remove this activity from back stack
    }
}
