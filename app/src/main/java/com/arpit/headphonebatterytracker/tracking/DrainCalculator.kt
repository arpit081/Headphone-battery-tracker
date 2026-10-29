package com.arpit.headphonebatterytracker.tracking

import com.arpit.headphonebatterytracker.headphones.TrackingMode
import kotlin.math.roundToInt

/** Battery use in one mode, from real logged data. */
data class DrainStats(
    val hours: Double,
    val percentUsed: Int,
    val avgVolumePercent: Int,
) {
    val percentPerHour: Double get() = if (hours > 0) percentUsed / hours else 0.0

    /** How long 0–100% would last at this drain rate. */
    val projectedFullChargeHours: Double? get() = if (percentPerHour > 0) 100 / percentPerHour else null

    /** Battery is reported in ~10% steps, so short runs are noisy. */
    val isEarlyEstimate: Boolean get() = hours < 2.0 || percentUsed < 20
}

data class UsageSummary(
    val sampleCount: Int,
    val perMode: Map<TrackingMode, DrainStats>,
)

object DrainCalculator {
    /** Consecutive samples further apart than this are treated as a break (disconnected / app stopped). */
    private const val MAX_GAP_MS = 7 * 60 * 1000L

    /**
     * Adds up listening time and battery used per mode, using only pairs of consecutive samples that are
     * close together, in the same mode, from the same battery source, and where the battery did not go up (charging).
     */
    fun compute(samples: List<Sample>): Map<TrackingMode, DrainStats> {
        val timeMs = mutableMapOf<TrackingMode, Long>()
        val used = mutableMapOf<TrackingMode, Int>()
        val volumeWeighted = mutableMapOf<TrackingMode, Double>()

        for ((a, b) in samples.zipWithNext()) {
            val dt = b.timestamp - a.timestamp
            if (dt <= 0 || dt > MAX_GAP_MS) continue
            if (a.mode != b.mode || a.mode == TrackingMode.OFF) continue
            // Never compare an Android reading (10% steps) with a headphone reading (1% steps).
            if (a.batterySource != b.batterySource) continue
            val before = a.battery ?: continue
            val after = b.battery ?: continue
            if (after > before) continue

            timeMs[a.mode] = (timeMs[a.mode] ?: 0L) + dt
            used[a.mode] = (used[a.mode] ?: 0) + (before - after)
            volumeWeighted[a.mode] = (volumeWeighted[a.mode] ?: 0.0) + a.volumePercent * dt.toDouble()
        }

        return timeMs.mapValues { (mode, ms) ->
            DrainStats(
                hours = ms / 3_600_000.0,
                percentUsed = used[mode] ?: 0,
                avgVolumePercent = ((volumeWeighted[mode] ?: 0.0) / ms).roundToInt(),
            )
        }
    }
}
