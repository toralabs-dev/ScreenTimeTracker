package com.screentimetracker.app.focus.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.screentimetracker.app.data.db.AppDatabase
import com.screentimetracker.app.focus.EvaluationResult
import com.screentimetracker.app.focus.FocusScheduleEvaluator
import com.screentimetracker.app.focus.data.FocusPrefs
import com.screentimetracker.app.focus.data.FocusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AccessibilityService that intercepts app launches and enforces Focus Mode schedules.
 *
 * Listens to TYPE_WINDOW_STATE_CHANGED events to detect when apps are launched,
 * then calls FocusScheduleEvaluator to determine if the app should be blocked.
 * When blocking is needed, launches FocusBlockActivity to intercept the user.
 */
class AppLaunchInterceptorService : AccessibilityService() {

    private lateinit var evaluator: FocusScheduleEvaluator
    private lateinit var focusPrefs: FocusPrefs
    private lateinit var focusRepository: FocusRepository

    // Coroutine scope for async operations
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Prevent duplicate blocking for same app
    private var lastBlockedPackage: String? = null
    private var lastBlockedTime: Long = 0L

    // Our own package - never block
    private lateinit var ownPackageName: String

    // System packages to never block
    private val systemPackages = setOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.samsung.android.launcher",
        "com.mi.android.globallauncher",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.oneplus.launcher",
        "com.android.settings",
        "com.android.dialer",
        "com.android.phone",
        "com.android.emergency",
        "com.android.incallui",
        "com.android.server.telecom"
    )

    override fun onCreate() {
        super.onCreate()
        ownPackageName = packageName

        // Initialize dependencies
        val db = AppDatabase.getInstance(applicationContext)
        focusPrefs = FocusPrefs(applicationContext)
        focusRepository = FocusRepository(db.focusDao())
        evaluator = FocusScheduleEvaluator(focusPrefs, focusRepository)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Skip own package and system packages
        if (packageName == ownPackageName || isSystemPackage(packageName)) return

        // Skip if same package was just blocked (cooldown)
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && (now - lastBlockedTime) < BLOCK_COOLDOWN_MS) {
            return
        }

        // Evaluate in coroutine scope
        serviceScope.launch {
            try {
                val result = evaluator.evaluate(packageName)

                if (result is EvaluationResult.Blocked) {
                    lastBlockedPackage = packageName
                    lastBlockedTime = now

                    // Launch block activity
                    launchBlockScreen(result)
                }
            } catch (e: Exception) {
                // Log but don't crash - prevents service from being disabled by Android/OEM
                // after repeated crashes
                android.util.Log.e(TAG, "Error evaluating $packageName", e)
            }
        }
    }

    private fun launchBlockScreen(blocked: EvaluationResult.Blocked) {
        // FocusBlockActivity will be implemented in Step 4
        // For now, construct the intent with all necessary data
        try {
            val intent = Intent().apply {
                setClassName(
                    applicationContext,
                    "com.screentimetracker.app.focus.ui.FocusBlockActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_PACKAGE_NAME, blocked.packageName)
                putExtra(EXTRA_SCHEDULE_ID, blocked.scheduleId)
                putExtra(EXTRA_SCHEDULE_NAME, blocked.scheduleName)
                blocked.scheduleEndTime?.let { putExtra(EXTRA_SCHEDULE_END_TIME, it) }
            }
            startActivity(intent)
        } catch (e: Exception) {
            // FocusBlockActivity not yet implemented - this is expected in Step 3
            // Silently ignore until Step 4 is complete
        }
    }

    /**
     * Check if a package is a system package that should never be blocked.
     */
    private fun isSystemPackage(packageName: String): Boolean {
        return packageName in systemPackages ||
                packageName.startsWith("com.android.") ||
                packageName.startsWith("com.google.android.inputmethod") ||
                packageName.startsWith("com.samsung.android.inputmethod") ||
                packageName == "android"
    }

    override fun onInterrupt() {
        // Required override - cleanup if needed
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "AppLaunchInterceptor"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
        const val EXTRA_SCHEDULE_NAME = "extra_schedule_name"
        const val EXTRA_SCHEDULE_END_TIME = "extra_schedule_end_time"

        // 1 second cooldown to prevent rapid re-blocking
        private const val BLOCK_COOLDOWN_MS = 1000L

        /**
         * Check if the accessibility service is enabled.
         */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val componentName = ComponentName(context, AppLaunchInterceptorService::class.java)
            return enabledServices.contains(componentName.flattenToString())
        }

        /**
         * Open accessibility settings for the user to enable the service.
         */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }
}
