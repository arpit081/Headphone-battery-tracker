package com.arpit.headphonebatterytracker.headphones

/**
 * What the user is tracking right now. NORMAL = ANC and ambient both off on the headphones.
 * OFF = don't track these headphones at all (shown as "Don't track").
 */
enum class TrackingMode(val label: String, val description: String) {
    ANC("ANC", "Noise cancelling on"),
    AMBIENT("Ambient", "Ambient sound on"),
    NORMAL("Normal", "Noise cancelling and ambient sound off"),
    OFF("Don't track", "No battery tracking for these headphones"),
}

/** The modes that produce battery data (everything except OFF). */
val TRACKED_MODES: List<TrackingMode> = listOf(TrackingMode.ANC, TrackingMode.AMBIENT, TrackingMode.NORMAL)
