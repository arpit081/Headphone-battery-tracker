package com.arpit.headphonebatterytracker.tracking

import com.arpit.headphonebatterytracker.headphones.HeadphoneModel
import com.arpit.headphonebatterytracker.headphones.TRACKED_MODES
import com.arpit.headphonebatterytracker.headphones.TrackingMode
import kotlin.math.roundToInt

/** One stretch of use between two charges. */
data class DischargeCycle(
    val number: Int,
    val start: Long,
    val end: Long,
    val startBattery: Int,
    val endBattery: Int,
    val percentUsed: Int,
    val trackedHours: Double,
    val perMode: Map<TrackingMode, DrainStats>,
    /** How long 0–100% would last, expressed in Normal-mode hours. Null when the cycle is too short to judge. */
    val projectedNormalHours: Double?,
)

data class HealthReport(
    val cycles: List<DischargeCycle>,
    /** Total battery used / 100, e.g. 2.4 = the equivalent of 2.4 full charges. */
    val equivalentFullCycles: Double,
    /** Runtime of the first cycle that had enough data (the baseline). */
    val baselineHours: Double?,
    /** Average runtime of the last (up to) 3 cycles with enough data. */
    val recentHours: Double?,
    /** recentHours vs baselineHours, in %. Needs 2+ usable cycles. */
    val healthVsBaseline: Int?,
    /** recentHours vs the company's Normal-mode claim, in %. */
    val healthVsClaim: Int?,
    val claimNormalHours: Double?,
    val usableCycles: Int,
    /** Assumptions the numbers rely on, shown to the user. */
    val notes: List<String>,
)

/**
 * Estimates battery health from logged samples. There is no real health reading from the headphones,
 * so this compares how long a full charge lasts now with how long it lasted at first.
 *
 * Time in different modes is converted to "Normal-mode equivalent" hours using the company claims
 * (e.g. Sony 40 h Normal / 30 h ANC: one ANC hour counts as 1.33 Normal hours), so cycles with
 * different mode mixes are comparable.
 */
object HealthCalculator {
    /** A cycle must use at least this much battery to be judged; less is too noisy. */
    const val MIN_USED_FOR_CYCLE = 30

    /** A rise of at least this many % between samples means the headphones were charged. */
    private const val CHARGE_JUMP = 3

    fun compute(samples: List<Sample>, model: HeadphoneModel?): HealthReport {
        val notes = mutableListOf<String>()
        val weights = modeWeights(model, notes)
        val withBattery = samples.filter { it.battery != null }

        // Split into cycles wherever the battery went up (= a charge happened between samples).
        val groups = mutableListOf<MutableList<Sample>>()
        for (s in withBattery) {
            val current = groups.lastOrNull()
            val prev = current?.lastOrNull()
            val charged = prev != null && prev.batterySource == s.batterySource &&
                s.battery!! >= prev.battery!! + CHARGE_JUMP
            if (current == null || prev == null || charged) {
                groups += mutableListOf(s)
            } else {
                current += s
            }
        }

        val cycles = groups.filter { it.size >= 2 }.mapIndexed { i, group ->
            val perMode = DrainCalculator.compute(group)
            val used = perMode.values.sumOf { it.percentUsed }
            val hours = perMode.values.sumOf { it.hours }
            val normalHours = perMode.entries.sumOf { (mode, st) -> st.hours * (weights[mode] ?: 1.0) }
            DischargeCycle(
                number = i + 1,
                start = group.first().timestamp,
                end = group.last().timestamp,
                startBattery = group.first().battery!!,
                endBattery = group.last().battery!!,
                percentUsed = used,
                trackedHours = hours,
                perMode = perMode,
                projectedNormalHours = if (used >= MIN_USED_FOR_CYCLE) normalHours * 100 / used else null,
            )
        }

        val usable = cycles.mapNotNull { it.projectedNormalHours }
        val baseline = usable.firstOrNull()
        val recent = if (usable.isEmpty()) null else usable.takeLast(3).average()
        val claim = model?.claimedHours?.get(TrackingMode.NORMAL)

        return HealthReport(
            cycles = cycles,
            equivalentFullCycles = cycles.sumOf { it.percentUsed } / 100.0,
            baselineHours = baseline,
            recentHours = recent,
            healthVsBaseline = if (usable.size >= 2 && baseline != null && recent != null && baseline > 0) {
                (recent / baseline * 100).roundToInt()
            } else {
                null
            },
            healthVsClaim = if (claim != null && recent != null) (recent / claim * 100).roundToInt() else null,
            claimNormalHours = claim,
            usableCycles = usable.size,
            notes = notes,
        )
    }

    /** How many Normal-mode hours one hour in each mode is worth, based on the company's claims. */
    private fun modeWeights(model: HeadphoneModel?, notes: MutableList<String>): Map<TrackingMode, Double> {
        val claims = model?.claimedHours.orEmpty()
        val normal = claims[TrackingMode.NORMAL]
        if (normal == null) {
            notes += "No company claim for Normal mode, so all modes are weighted equally."
            return TRACKED_MODES.associateWith { 1.0 }
        }
        return TRACKED_MODES.associateWith { mode ->
            val claim = claims[mode] ?: claims[TrackingMode.ANC]?.also {
                if (mode == TrackingMode.AMBIENT) {
                    notes += "No official Ambient figure, so Ambient is assumed to use battery like ANC."
                }
            }
            if (claim != null && claim > 0) normal / claim else 1.0
        }
    }
}
