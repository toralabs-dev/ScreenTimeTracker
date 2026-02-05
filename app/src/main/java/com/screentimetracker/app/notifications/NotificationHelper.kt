package com.screentimetracker.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.screentimetracker.app.R
import com.screentimetracker.app.ui.main.MainActivity

/**
 * Helper class for creating and managing usage notifications.
 */
object NotificationHelper {

    private const val TAG = "NotificationHelper"
    private const val CHANNEL_ID = "usage_alerts"
    private const val CHANNEL_NAME = "Usage Alerts"
    private const val CHANNEL_DESCRIPTION = "Notifications for screen time warnings and limits"

    private const val NOTIFICATION_ID_WARNING = 1001
    private const val NOTIFICATION_ID_LIMIT = 1002
    private const val NOTIFICATION_ID_FOCUS_DISABLED = 1003

    const val ACTION_SNOOZE = "com.screentimetracker.app.ACTION_SNOOZE"
    const val ACTION_PAUSE = "com.screentimetracker.app.ACTION_PAUSE"
    const val SNOOZE_DURATION_MILLIS = 60 * 60 * 1000L // 60 minutes

    /**
     * Creates the notification channel for usage alerts.
     * Should be called on app startup.
     */
    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESCRIPTION
            enableVibration(true)
            setShowBadge(true)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Checks if the app has permission to post notifications (Android 13+).
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permission not required before Android 13
        }
    }

    /**
     * Formats milliseconds as a human-readable duration string.
     * Examples: "1h 36m", "45m", "2h"
     */
    fun formatDuration(millis: Long): String {
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
     * Shows the 80% warning notification.
     *
     * @param context The application context
     * @param currentUsageMillis Current usage in milliseconds (unused, kept for API compatibility)
     * @param limitMillis Daily limit in milliseconds (unused, kept for API compatibility)
     * @param insightText Optional insight text to append
     */
    fun showWarningNotification(
        context: Context,
        currentUsageMillis: Long,
        limitMillis: Long,
        insightText: String? = null
    ) {
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "Cannot show warning notification: POST_NOTIFICATIONS permission not granted")
            return
        }

        val title = context.getString(R.string.notification_warning_title)
        val baseText = context.getString(R.string.notification_warning_text)

        val fullText = if (insightText != null) {
            "$baseText $insightText"
        } else {
            baseText
        }

        showNotification(
            context = context,
            notificationId = NOTIFICATION_ID_WARNING,
            title = title,
            text = fullText,
            includeActions = true
        )
    }

    /**
     * Shows the 100% limit reached notification.
     *
     * @param context The application context
     * @param currentUsageMillis Current usage in milliseconds (unused, kept for API compatibility)
     * @param limitMillis Daily limit in milliseconds (unused, kept for API compatibility)
     * @param insightText Optional insight text to append
     */
    fun showLimitNotification(
        context: Context,
        currentUsageMillis: Long,
        limitMillis: Long,
        insightText: String? = null
    ) {
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "Cannot show limit notification: POST_NOTIFICATIONS permission not granted")
            return
        }

        val title = context.getString(R.string.notification_limit_title)
        val baseText = context.getString(R.string.notification_limit_text)

        val fullText = if (insightText != null) {
            "$baseText $insightText"
        } else {
            baseText
        }

        showNotification(
            context = context,
            notificationId = NOTIFICATION_ID_LIMIT,
            title = title,
            text = fullText,
            includeActions = true
        )
    }

    private fun showNotification(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        includeActions: Boolean
    ) {
        // Intent to open the app
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)

        if (includeActions) {
            // Add "Pause now" action (dismisses notification and opens the app)
            val pauseIntent = Intent(context, PauseActionReceiver::class.java).apply {
                action = ACTION_PAUSE
            }
            val pausePendingIntent = PendingIntent.getBroadcast(
                context,
                1,  // Different request code from snooze
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_pause,
                context.getString(R.string.notification_action_pause),
                pausePendingIntent
            )

            // Add "Keep going (1h)" snooze action
            val snoozeIntent = Intent(context, SnoozeReceiver::class.java).apply {
                action = ACTION_SNOOZE
            }
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(
                R.drawable.ic_snooze,
                context.getString(R.string.notification_action_snooze),
                snoozePendingIntent
            )
        }

        with(NotificationManagerCompat.from(context)) {
            try {
                notify(notificationId, builder.build())
            } catch (e: SecurityException) {
                // Permission was revoked
            }
        }
    }

    /**
     * Cancels all usage notifications.
     */
    fun cancelAllNotifications(context: Context) {
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.cancel(NOTIFICATION_ID_WARNING)
        notificationManager.cancel(NOTIFICATION_ID_LIMIT)
    }

    /**
     * Shows a notification when Focus Mode is enabled but the accessibility service is disabled.
     * This can happen after app updates when Android disables accessibility services for security.
     * Tapping the notification opens the accessibility settings.
     */
    fun showFocusModeDisabledNotification(context: Context) {
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "Cannot show focus disabled notification: POST_NOTIFICATIONS permission not granted")
            return
        }

        createNotificationChannel(context)

        // Intent to open accessibility settings
        val settingsIntent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            context,
            2,  // Unique request code to avoid PendingIntent caching conflicts
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.focus_notification_disabled_title))
            .setContentText(context.getString(R.string.focus_notification_disabled_message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_FOCUS_DISABLED, notification)
    }

    /**
     * Dismisses the Focus Mode disabled notification.
     * Should be called when the user re-enables the accessibility service.
     */
    fun dismissFocusModeDisabledNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_FOCUS_DISABLED)
    }
}
