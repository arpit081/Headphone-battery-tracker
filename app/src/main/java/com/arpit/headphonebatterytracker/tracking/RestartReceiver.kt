package com.arpit.headphonebatterytracker.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.arpit.headphonebatterytracker.headphones.TrackingPreferences

/**
 * Brings background tracking back after the phone restarts, after an app update, after the app was
 * swiped away (alarm set in TrackingService.onTaskRemoved), and whenever a Bluetooth device connects.
 * Only acts when both "Record in the background" and "Keep running after closing the app" are on.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = TrackingPreferences(context)
        if (!prefs.trackingEnabled || !prefs.keepAlive) return
        try {
            TrackingService.start(context)
        } catch (e: Exception) {
            // Android may refuse to start a foreground service from the background
            // (usually because battery optimisation is still on for the app).
            Log.w("RestartReceiver", "Could not restart tracking: ${e.message}")
        }
    }

    companion object {
        const val ACTION_RESTART = "com.arpit.headphonebatterytracker.RESTART_TRACKING"
    }
}
