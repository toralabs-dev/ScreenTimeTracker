package com.screentimetracker.app.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that handles fast check alarms.
 * Uses AlarmManager to ensure reliable execution during Doze mode.
 */
class FastCheckAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FastCheckAlarmReceiver"
        const val ACTION_FAST_CHECK = "com.screentimetracker.app.ACTION_FAST_CHECK"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_FAST_CHECK) {
            Log.d(TAG, "Fast check alarm received, triggering usage check")
            // Run an immediate check via WorkManager
            UsageCheckWorker.runImmediateCheck(context)
        }
    }
}
