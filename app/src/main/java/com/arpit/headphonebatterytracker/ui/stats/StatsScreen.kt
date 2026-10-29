package com.arpit.headphonebatterytracker.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arpit.headphonebatterytracker.headphones.HeadphoneCatalog
import com.arpit.headphonebatterytracker.headphones.HeadphoneModel
import com.arpit.headphonebatterytracker.headphones.TRACKED_MODES
import com.arpit.headphonebatterytracker.headphones.TrackingMode
import com.arpit.headphonebatterytracker.tracking.DrainCalculator
import com.arpit.headphonebatterytracker.tracking.DrainStats
import com.arpit.headphonebatterytracker.tracking.HealthCalculator
import com.arpit.headphonebatterytracker.tracking.HealthReport
import com.arpit.headphonebatterytracker.tracking.Sample
import com.arpit.headphonebatterytracker.tracking.SampleStore
import com.arpit.headphonebatterytracker.ui.AppVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class StatsRange(val label: String, val millis: Long?) {
    DAY("24 h", 24 * 60 * 60 * 1000L),
    WEEK("7 days", 7 * 24 * 60 * 60 * 1000L),
    MONTH("30 days", 30 * 24 * 60 * 60 * 1000L),
    ALL("All", null),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(headphoneName: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { SampleStore.get(appContext) }
    val model = remember(headphoneName) { HeadphoneCatalog.match(headphoneName) }
    var samples by remember { mutableStateOf<List<Sample>?>(null) }
    var loadedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var range by rememberSaveable { mutableStateOf(StatsRange.DAY) }

    LaunchedEffect(headphoneName) {
        var lastFingerprint: Pair<Long, Long>? = null
        while (true) {
            AppVisibility.awaitVisible() // nothing to refresh while the app is in the background
            val fingerprint = withContext(Dispatchers.IO) { store.fingerprint(headphoneName) }
            if (fingerprint != lastFingerprint) {
                samples = withContext(Dispatchers.IO) { store.samples(headphoneName) }
                lastFingerprint = fingerprint
            }
            loadedAt = System.currentTimeMillis()
            delay(30_000)
        }
    }

    val all = samples.orEmpty()
    val rangeStart = range.millis?.let { loadedAt - it } ?: (all.firstOrNull()?.timestamp ?: loadedAt)
    val inRange = remember(all, rangeStart) { all.filter { it.timestamp >= rangeStart } }
    val drain = remember(inRange) { DrainCalculator.compute(inRange) }
    val health = remember(all, model) { HealthCalculator.compute(all, model) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(headphoneName, style = MaterialTheme.typography.titleLarge)
            }
        }

        if (samples == null) {
            item { Text("Loading…") }
            return@LazyColumn
        }
        if (all.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "No data yet. Turn on background tracking on the home screen and listen for a while.",
                        Modifier.padding(16.dp),
                    )
                }
            }
            return@LazyColumn
        }

        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in StatsRange.entries) {
                    FilterChip(selected = option == range, onClick = { range = option }, label = { Text(option.label) })
                }
            }
        }

        item {
            ChartCard("Battery over time") {
                ModeLegend(TRACKED_MODES)
                Spacer(Modifier.height(8.dp))
                if (inRange.none { it.battery != null }) {
                    Text("No readings in this period.")
                } else {
                    BatteryTimelineChart(inRange, rangeStart, loadedAt)
                }
            }
        }

        item { BatteryUsedCard(drain) }
        item { DrainRateCard(drain, model) }
        item { HealthCard(health, model) }
        item { SummaryTable(drain, model) }
        item {
            Text(
                "${all.size} samples logged since ${formatTime(all.first().timestamp, true)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Total battery used in each mode during the selected period. */
@Composable
private fun BatteryUsedCard(drain: Map<TrackingMode, DrainStats>) {
    ChartCard("Battery used per mode", "Total battery used in each mode in this period") {
        if (drain.isEmpty()) {
            Text("No listening time in this period.")
            return@ChartCard
        }
        val max = drain.values.maxOf { it.percentUsed }.toDouble()
        for (mode in TRACKED_MODES) {
            val st = drain[mode]
            BarRow(
                label = mode.label,
                value = (st?.percentUsed ?: 0).toDouble(),
                max = max,
                valueText = st?.let { "${it.percentUsed}% · ${hours(it.hours)} h" } ?: "no data",
                color = modeColor(mode),
            )
        }
    }
}

/** Drain speed in each mode, with the company's claimed rate as a tick. */
@Composable
private fun DrainRateCard(drain: Map<TrackingMode, DrainStats>, model: HeadphoneModel?) {
    val brand = model?.brand ?: "Company"
    ChartCard(
        "Drain rate per mode",
        "Battery % used per hour of listening. Lower is better. | = $brand's claimed rate.",
    ) {
        if (drain.isEmpty()) {
            Text("No listening time in this period.")
            return@ChartCard
        }
        val claimedRates = TRACKED_MODES.associateWith { m -> model?.claimedHours?.get(m)?.let { 100 / it } }
        val max = (drain.values.map { it.percentPerHour } + claimedRates.values.filterNotNull()).maxOrNull() ?: 1.0
        for (mode in TRACKED_MODES) {
            val st = drain[mode]
            BarRow(
                label = mode.label,
                value = st?.percentPerHour ?: 0.0,
                max = max * 1.1,
                valueText = st?.let { "${oneDecimal(it.percentPerHour)} %/h" } ?: "no data",
                color = modeColor(mode),
                reference = claimedRates[mode],
            )
        }
        if (drain.values.any { it.isEarlyEstimate }) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Modes with under 2 h or under 20% used are early estimates.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HealthCard(health: HealthReport, model: HeadphoneModel?) {
    ChartCard("Battery health (estimated)", "Based on how long a full charge lasts, across all your data") {
        val big = health.healthVsBaseline?.let { "$it%" } ?: "—"
        Row(verticalAlignment = Alignment.Bottom) {
            Text(big, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                if (health.healthVsBaseline != null) "of your first measured charge" else "needs 2 measured charges",
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        health.recentHours?.let {
            Text("A full charge currently lasts about ${hours(it)} h (in Normal-mode hours).")
        }
        if (health.healthVsClaim != null && health.claimNormalHours != null) {
            Text("That is ${health.healthVsClaim}% of ${model?.brand ?: "the company"}'s ${hours(health.claimNormalHours)} h claim.")
        }
        Text(
            "Charges tracked: ${health.cycles.size} · equal to ${oneDecimal(health.equivalentFullCycles)} full charges",
        )

        val usable = health.cycles.filter { it.projectedNormalHours != null }
        if (usable.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Full-charge runtime per charge", style = MaterialTheme.typography.titleSmall)
            val claim = health.claimNormalHours
            val max = (usable.mapNotNull { it.projectedNormalHours } + listOfNotNull(claim)).max() * 1.1
            for (cycle in usable.takeLast(10)) {
                BarRow(
                    label = "#${cycle.number} ${formatTime(cycle.start, true).substringBeforeLast(' ')}",
                    value = cycle.projectedNormalHours ?: 0.0,
                    max = max,
                    valueText = "${hours(cycle.projectedNormalHours ?: 0.0)} h",
                    color = MaterialTheme.colorScheme.primary,
                    reference = claim,
                )
            }
            if (claim != null) {
                Text(
                    "| = ${model?.brand ?: "company"} claim (${hours(claim)} h)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                "A charge is measured once it has used at least ${HealthCalculator.MIN_USED_FOR_CYCLE}% battery.",
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
        val notes = listOf(
            "This is an estimate: headphones don't report real battery health. A drop over months means the battery is wearing.",
            "Company claims are measured in lab conditions, so real use is often somewhat lower even when new.",
        ) + health.notes
        for (note in notes) {
            Text("• $note", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The same numbers as the charts, as a table. */
@Composable
private fun SummaryTable(drain: Map<TrackingMode, DrainStats>, model: HeadphoneModel?) {
    ChartCard("Summary table", "For the selected period") {
        TableRow(listOf("Mode", "Hours", "Used", "%/h", "Full charge", "vs claim"), header = true)
        HorizontalDivider()
        for (mode in TRACKED_MODES) {
            val st = drain[mode]
            val claim = model?.claimedHours?.get(mode)
            val full = st?.projectedFullChargeHours
            TableRow(
                listOf(
                    mode.label,
                    st?.let { hours(it.hours) } ?: "–",
                    st?.let { "${it.percentUsed}%" } ?: "–",
                    st?.let { oneDecimal(it.percentPerHour) } ?: "–",
                    full?.let { "${hours(it)} h" } ?: "–",
                    if (full != null && claim != null) "${(full / claim * 100).roundToInt()}%" else "–",
                ),
            )
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        cells.forEachIndexed { i, cell ->
            Text(
                cell,
                modifier = Modifier.weight(if (i == 0) 1.3f else 1f),
                fontSize = 12.sp,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

private fun hours(value: Double): String =
    if (value >= 10) value.roundToInt().toString() else oneDecimal(value)

private fun oneDecimal(value: Double): String = String.format("%.1f", value)
