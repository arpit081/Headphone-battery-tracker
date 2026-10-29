package com.arpit.headphonebatterytracker.headphones

import android.content.Context

/** Remembers the chosen tracking mode per headphone (keyed by device name) and the background tracking switch. */
class TrackingPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("tracking", Context.MODE_PRIVATE)

    fun get(headphoneName: String): TrackingMode =
        prefs.getString(key(headphoneName), null)
            ?.let { saved -> TrackingMode.entries.firstOrNull { it.name == saved } }
            ?: TrackingMode.NORMAL

    fun set(headphoneName: String, mode: TrackingMode) {
        prefs.edit().putString(key(headphoneName), mode.name).apply()
    }

    /** Whether the mode is detected from the headphones (only for models that support it). Default on. */
    fun getAutoDetect(headphoneName: String): Boolean = prefs.getBoolean("auto_$headphoneName", true)

    fun setAutoDetect(headphoneName: String, auto: Boolean) {
        prefs.edit().putBoolean("auto_$headphoneName", auto).apply()
    }

    /** Whether tracking should come back after the app is swiped away, after a reboot, etc. */
    var keepAlive: Boolean
        get() = prefs.getBoolean("keep_alive", false)
        set(value) {
            prefs.edit().putBoolean("keep_alive", value).apply()
        }

    /** Whether the background tracking service should run. */
    var trackingEnabled: Boolean
        get() = prefs.getBoolean("tracking_enabled", false)
        set(value) {
            prefs.edit().putBoolean("tracking_enabled", value).apply()
        }

    private fun key(name: String) = "mode_$name"
}
