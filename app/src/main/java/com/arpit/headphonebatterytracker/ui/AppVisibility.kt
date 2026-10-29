package com.arpit.headphonebatterytracker.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Whether the app is on screen. Screen refresh loops wait on this, so nothing polls Bluetooth,
 * volume or the database while the app is in the background (the tracking service does that job).
 */
object AppVisibility {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun set(visible: Boolean) {
        _visible.value = visible
    }

    /** Suspends until the app is visible again (returns immediately if it already is). */
    suspend fun awaitVisible() {
        _visible.first { it }
    }
}
