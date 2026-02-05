package com.screentimetracker.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.screentimetracker.app.data.prefs.UserPrefs
import com.screentimetracker.app.worker.UsageCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that handles the "Snooze 60 min" action from notifications.
 */
class SnoozeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == NotificationHelper.ACTION_SNOOZE) {
            val userPrefs = UserPrefs(context)

            // Use goAsync() for background work in receiver
            val pendingResult = goAsync()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Set snooze for 60 minutes
                    userPrefs.snoozeFor(NotificationHelper.SNOOZE_DURATION_MILLIS)

                    // Cancel existing notifications
                    NotificationHelper.cancelAllNotifications(context)

                    // Cancel fast checks during snooze period
                    UsageCheckWorker.cancelFastChecks(context)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
