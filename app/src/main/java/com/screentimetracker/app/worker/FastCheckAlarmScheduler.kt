package com.screentimetracker.app.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log

/**
 * Handles scheduling exact alarms for fast usage checks.
 * Uses AlarmManager.setExactAndAllowWhileIdle() to ensure
 * alarms fire reliably even during Doze mode.
 */
object FastCheckAlarmScheduler {

    private const val TAG = "FastCheckAlarmScheduler"
    private const val REQUEST_CODE = 1001
    private const val LOW_BATTERY_THRESHOLD = 20 // Use inexact alarms below 20%

    /**
     * Schedules a fast check alarm to fire after the specified delay.
     * Uses exact alarms for reliable execution, but falls back to inexact alarms
     * when battery is low to conserve power.
     *
     * @param context The application context
     * @param delayMinutes Delay in minutes before the alarm fires
     */
    fun schedule(context: Context, delayMinutes: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, FastCheckAlarmReceiver::class.java).apply {
            action = FastCheckAlarmReceiver.ACTION_FAST_CHECK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = System.currentTimeMillis() + (delayMinutes * 60 * 1000)

        // Check battery level - use inexact alarms when battery is low
        val batteryLevel = getBatteryLevel(context)
        val useBatterySaverMode = batteryLevel in 1 until LOW_BATTERY_THRESHOLD

        if (useBatterySaverMode) {
            // Battery is low - use inexact alarms to save power
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.d(TAG, "Scheduled inexact alarm fast check in $delayMinutes minutes (battery: $batteryLevel%)")
            return
        }

        // Normal mode - use exact alarms for reliable execution during Doze
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires SCHEDULE_EXACT_ALARM permission
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact alarm fast check in $delayMinutes minutes")
            } else {
                // Fallback to inexact alarm if permission not granted
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled inexact alarm fast check in $delayMinutes minutes (no exact alarm permission)")
            }
        } else {
            // Pre-Android 12: can use exact alarms without permission
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.d(TAG, "Scheduled exact alarm fast check in $delayMinutes minutes")
        }
    }

    /**
     * Gets the current battery level as a percentage (0-100).
     * Returns -1 if unable to determine.
     */
    private fun getBatteryLevel(context: Context): Int {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                (level * 100) / scale
            } else {
                -1
            }
        } ?: -1
    }

    /**
     * Cancels any pending fast check alarm.
     */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, FastCheckAlarmReceiver::class.java).apply {
            action = FastCheckAlarmReceiver.ACTION_FAST_CHECK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled fast check alarm")
    }
}
