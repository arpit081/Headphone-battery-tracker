package com.arpit.headphonebatterytracker.tracking

import com.arpit.headphonebatterytracker.headphones.TrackingMode

/** One logged reading for one pair of headphones. */
data class Sample(
    val timestamp: Long,
    val device: String,
    val battery: Int?,
    /** Where [battery] came from: [SOURCE_HEADPHONES] (1% steps) or [SOURCE_ANDROID] (10% steps). */
    val batterySource: String,
    val mode: TrackingMode,
    val volume: Int,
    val volumeMax: Int,
    /** Where the volume came from. "stream" = the phone's media volume (accurate when the headphones use absolute volume). */
    val volumeSource: String,
) {
    val volumePercent: Int get() = if (volumeMax > 0) volume * 100 / volumeMax else 0

    /** True when battery, its source, mode or volume differ from [other]. */
    fun differsFrom(other: Sample): Boolean =
        battery != other.battery || batterySource != other.batterySource ||
            mode != other.mode || volume != other.volume

    companion object {
        const val SOURCE_HEADPHONES = "headphones"
        const val SOURCE_ANDROID = "android"
        const val SOURCE_NONE = "none"
    }
}
