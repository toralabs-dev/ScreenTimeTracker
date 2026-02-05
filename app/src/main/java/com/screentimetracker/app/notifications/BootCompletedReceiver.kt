package com.screentimetracker.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.screentimetracker.app.data.prefs.UserPrefs
import com.screentimetracker.app.worker.UsageCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that restores the usage check worker after device reboot.
 * Re-schedules periodic checks and evaluates whether fast mode should resume.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.d(TAG, "Boot completed - restoring usage check worker")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userPrefs = UserPrefs(context)

                // Check if notifications are enabled
                val warningEnabled = userPrefs.getWarningNotificationsEnabledSync()
                val limitEnabled = userPrefs.getLimitNotificationsEnabledSync()

                if (warningEnabled || limitEnabled) {
                    // Re-schedule the periodic worker
                    UsageCheckWorker.schedule(context)

                    // Run an immediate check to evaluate current state
                    // This will also re-enter fast mode if needed
                    UsageCheckWorker.runImmediateCheck(context)

                    Log.d(TAG, "Usage check worker restored after boot")
                } else {
                    Log.d(TAG, "Notifications disabled, skipping worker restore")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring worker after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
