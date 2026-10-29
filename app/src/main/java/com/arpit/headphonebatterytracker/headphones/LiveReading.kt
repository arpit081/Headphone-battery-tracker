package com.arpit.headphonebatterytracker.headphones

import com.arpit.headphonebatterytracker.sony.SonyNcMode
import com.arpit.headphonebatterytracker.sony.SonyState
import com.arpit.headphonebatterytracker.tracking.Sample

/** Everything the UI and the tracker need about one connected headphone right now. */
data class LiveReading(
    val battery: Int?,
    /** Human-readable source for the UI. */
    val batterySource: String,
    /** Source key stored with samples (Sample.SOURCE_*). */
    val batterySourceKey: String,
    val charging: Boolean,
    /** Mode read from the headphones, if available. */
    val detectedMode: TrackingMode?,
    /** Mode to track: OFF = not tracking, null = auto-detect on but no reading yet. */
    val effectiveMode: TrackingMode?,
    val supportsAuto: Boolean,
    val autoDetect: Boolean,
    /** Sony connection state for this headphone, or null if not a Sony-protocol model. */
    val sony: SonyState?,
)

object LiveReadings {
    fun resolve(hp: Headphone, prefs: TrackingPreferences, sony: SonyState): LiveReading {
        val supportsAuto = hp.model?.supportsSonyProtocol == true
        val sonyForThis = if (supportsAuto && sony.address == hp.primaryAddress) sony else null
        val live = sonyForThis?.takeIf { it.status == SonyState.Status.CONNECTED }

        val battery = live?.battery ?: hp.batteryPercent
        val source = when {
            live?.battery != null -> "from headphones, 1% steps"
            hp.batteryPercent != null -> "from Android, 10% steps"
            else -> "not reported"
        }
        val sourceKey = when {
            live?.battery != null -> Sample.SOURCE_HEADPHONES
            hp.batteryPercent != null -> Sample.SOURCE_ANDROID
            else -> Sample.SOURCE_NONE
        }
        val detected = when (live?.ncMode) {
            SonyNcMode.NOISE_CANCELLING -> TrackingMode.ANC
            SonyNcMode.AMBIENT -> TrackingMode.AMBIENT
            SonyNcMode.OFF -> TrackingMode.NORMAL
            null -> null
        }
        val manual = prefs.get(hp.name)
        val auto = supportsAuto && manual != TrackingMode.OFF && prefs.getAutoDetect(hp.name)
        val effective = when {
            manual == TrackingMode.OFF -> TrackingMode.OFF
            auto -> detected
            else -> manual
        }
        return LiveReading(
            battery = battery,
            batterySource = source,
            batterySourceKey = sourceKey,
            charging = live?.charging == true,
            detectedMode = detected,
            effectiveMode = effective,
            supportsAuto = supportsAuto,
            autoDetect = auto,
            sony = sonyForThis,
        )
    }
}
