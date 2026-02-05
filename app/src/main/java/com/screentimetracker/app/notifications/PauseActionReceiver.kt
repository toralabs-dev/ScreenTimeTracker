package com.screentimetracker.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.screentimetracker.app.ui.main.MainActivity

/**
 * Handles the "Pause Now" notification action.
 * Dismisses the notification and opens the app.
 */
class PauseActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == NotificationHelper.ACTION_PAUSE) {
            // Cancel the notification first
            NotificationHelper.cancelAllNotifications(context)

            // Open MainActivity
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(mainIntent)
        }
    }
}
