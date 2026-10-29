package com.arpit.headphonebatterytracker.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arpit.headphonebatterytracker.diagnostics.VolumeInfo
import com.arpit.headphonebatterytracker.headphones.Headphone
import com.arpit.headphonebatterytracker.headphones.HeadphoneMonitor
import com.arpit.headphonebatterytracker.headphones.Illustration
import com.arpit.headphonebatterytracker.headphones.LiveReading
import com.arpit.headphonebatterytracker.headphones.LiveReadings
import com.arpit.headphonebatterytracker.headphones.TRACKED_MODES
import com.arpit.headphonebatterytracker.headphones.TrackingMode
import com.arpit.headphonebatterytracker.headphones.TrackingPreferences
import com.arpit.headphonebatterytracker.sony.SonyManager
import com.arpit.headphonebatterytracker.sony.SonyState
import com.arpit.headphonebatterytracker.tracking.DischargeCycle
import com.arpit.headphonebatterytracker.tracking.DrainCalculator
import com.arpit.headphonebatterytracker.tracking.DrainStats
import com.arpit.headphonebatterytracker.tracking.HealthCalculator
import com.arpit.headphonebatterytracker.tracking.SampleStore
import com.arpit.headphonebatterytracker.tracking.TrackingService
import com.arpit.headphonebatterytracker.ui.AppVisibility
import com.arpit.headphonebatterytracker.ui.stats.modeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Logged-data summary for one headphone, used on the home screen. */
private data class HomeStats(
    val perMode: Map<TrackingMode, DrainStats>,
    val sinceCharge: DischargeCycle?,
    val sampleCount: Int,
)

@Composable
fun HomeScreen(
    onOpenDiagnostics: () -> Unit,
    onOpenStats: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val monitor = remember { HeadphoneMonitor(appContext) }
    val prefs = remember { TrackingPreferences(appContext) }
    val store = remember { SampleStore.get(appContext) }
    var headphones by remember { mutableStateOf<List<Headphone>?>(null) }
    var volume by remember { mutableStateOf(monitor.readMediaVolume()) }
    var stats by remember { mutableStateOf<Map<String, HomeStats>>(emptyMap()) }
    // Last calculated stats per headphone, with the data fingerprint they were calculated from.
    val statsCache = remember { mutableMapOf<String, Pair<Pair<Long, Long>, HomeStats>>() }
    var trackingOn by remember { mutableStateOf(prefs.trackingEnabled) }
    var keepAlive by remember { mutableStateOf(prefs.keepAlive) }
    var unrestricted by remember { mutableStateOf(isBatteryUnrestricted(appContext)) }
    val sony by SonyManager.state.collectAsState()

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Tracking works either way; without the permission the notification is just hidden.
        TrackingService.start(appContext)
    }

    fun setTracking(on: Boolean) {
        trackingOn = on
        prefs.trackingEnabled = on
        if (!on) {
            TrackingService.stop(appContext)
        } else if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TrackingService.start(appContext)
        }
    }

    // Restart the service if tracking was on but the service was killed (e.g. after a reboot).
    LaunchedEffect(Unit) { if (prefs.trackingEnabled) TrackingService.start(appContext) }

    // Volume every second; headphones, battery and logged stats every 3 seconds. Paused while the app
    // isn't on screen.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            if (!AppVisibility.visible.value) {
                AppVisibility.awaitVisible()
                tick = 0 // refresh everything straight away on return
            }
            volume = monitor.readMediaVolume()
            if (tick % 3 == 0) {
                monitor.load { list ->
                    headphones = list
                    list.filter { it.connected && it.model?.supportsSonyProtocol == true }
                        .forEach { SonyManager.ensureConnected(appContext, it.primaryAddress) }
                }
                trackingOn = prefs.trackingEnabled // picks up "Stop" pressed in the notification
                unrestricted = isBatteryUnrestricted(appContext) // picks up changes made in system settings
                val connectedNow = headphones.orEmpty().filter { it.connected }
                stats = withContext(Dispatchers.IO) {
                    connectedNow.associate { hp ->
                        val fingerprint = store.fingerprint(hp.name)
                        val cached = statsCache[hp.name]
                        val fresh = if (cached != null && cached.first == fingerprint) {
                            cached.second
                        } else {
                            // Only reread and recalculate when a new sample was logged.
                            val samples = store.samples(hp.name)
                            HomeStats(
                                perMode = DrainCalculator.compute(samples),
                                sinceCharge = HealthCalculator.compute(samples, hp.model).cycles.lastOrNull(),
                                sampleCount = samples.size,
                            ).also { statsCache[hp.name] = fingerprint to it }
                        }
                        hp.name to fresh
                    }
                }
            }
            tick++
            delay(1000)
        }
    }

    val connected = headphones.orEmpty().filter { it.connected }
    val paired = headphones.orEmpty().filter { !it.connected }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                "Headphone Tracker",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        when {
            headphones == null -> item { Text("Looking for your headphones…") }
            connected.isEmpty() -> item { NoHeadphones() }
            else -> items(connected) { hp ->
                HeadphoneHome(hp, volume, prefs, sony, stats[hp.name], onOpenStats = { onOpenStats(hp.name) })
            }
        }

        item {
            SettingsSection(
                backgroundOn = trackingOn,
                onBackgroundChange = ::setTracking,
                keepAlive = keepAlive,
                onKeepAliveChange = { on ->
                    keepAlive = on
                    prefs.keepAlive = on
                    if (on && !trackingOn) setTracking(true)
                    if (on && !unrestricted) openBatteryOptimisationRequest(context)
                },
                unrestricted = unrestricted,
                onFixBattery = { openBatteryOptimisationRequest(context) },
                onOpenAppSettings = { openAppSettings(context) },
                onOpenDiagnostics = onOpenDiagnostics,
            )
        }

        if (paired.isNotEmpty()) {
            item {
                Panel {
                    Text("Other headphones", style = MaterialTheme.typography.titleSmall)
                    for (hp in paired) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(hp.name)
                                Text("Not connected", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onOpenStats(hp.name) }) { Text("Stats") }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun NoHeadphones() {
    Panel {
        Text("No headphones connected", style = MaterialTheme.typography.titleMedium)
        Text(
            "Turn on your headphones and connect them in Bluetooth settings. They'll show up here on their own.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeadphoneHome(
    hp: Headphone,
    volume: VolumeInfo,
    prefs: TrackingPreferences,
    sony: SonyState,
    stats: HomeStats?,
    onOpenStats: () -> Unit,
) {
    // Bumped after a change so the reading is recomputed from the saved preferences.
    var prefsVersion by remember(hp.name) { mutableIntStateOf(0) }
    val reading = remember(hp, sony, prefsVersion) { LiveReadings.resolve(hp, prefs, sony) }
    val manual = remember(hp.name, prefsVersion) { prefs.get(hp.name) }
    val tracked = reading.effectiveMode != TrackingMode.OFF
    val shownMode = reading.effectiveMode?.takeIf { it != TrackingMode.OFF }
        ?: reading.detectedMode
        ?: manual.takeIf { it != TrackingMode.OFF }
    val accent = shownMode?.let { modeColor(it) } ?: MaterialTheme.colorScheme.outline
    val timeLeft = remember(reading, stats, shownMode) { estimateTimeLeft(hp, reading, shownMode, stats) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // --- Hero: the headphones (or a plain gauge for models without a drawing) ---
        val battery = reading.battery
        val description = battery?.let { "Battery $it percent" } ?: "Battery not reported"
        val percentText: @Composable () -> Unit = {
            Text(
                battery?.let { "$it%" } ?: "–",
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-1).sp,
            )
        }
        val photoSpec = hp.model?.photo
        val photo = rememberHeadphonePhoto(photoSpec)
        if (photo != null && photoSpec != null) {
            HeadphonePhoto(
                photo = photo,
                spec = photoSpec,
                glowColor = accent,
                description = "${hp.name}, $description",
                modifier = Modifier.padding(top = 8.dp),
            )
            // With a photo, battery is a large number and a slim bar in the mode colour.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                percentText()
                BatteryBar(battery, accent, reading.charging)
            }
        } else if (hp.model?.illustration == Illustration.OVER_EAR) {
            HeadphoneIllustration(
                level = battery,
                color = accent,
                waves = WaveStyle.NONE,
                charging = reading.charging,
                description = description,
                modifier = Modifier.padding(top = 8.dp),
            ) { percentText() }
        } else {
            HeadbandGauge(
                level = battery,
                color = accent,
                description = description,
                modifier = Modifier.padding(top = 8.dp),
            ) { percentText() }
        }

        // --- Time left ---
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    reading.charging -> "Charging"
                    timeLeft != null -> "About ${hoursText(timeLeft.hours)} left"
                    battery == null -> "Battery not reported"
                    else -> "Time left appears after some listening"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            timeLeft?.takeIf { !reading.charging }?.let {
                Text(it.basis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // --- Name + mode line ---
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(hp.name, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shownMode != null && tracked) {
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(modeLine(reading, manual), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            linkProblem(reading)?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }

        // --- Mode switch ---
        if (tracked) {
            val options = buildList {
                if (reading.supportsAuto) add("Auto" to reading.autoDetect)
                for (m in TRACKED_MODES) add(m.label to (!reading.autoDetect && manual == m))
            }
            ModeSwitch(options) { index ->
                val autoOffset = if (reading.supportsAuto) 1 else 0
                if (reading.supportsAuto && index == 0) {
                    prefs.setAutoDetect(hp.name, true)
                } else {
                    prefs.setAutoDetect(hp.name, false)
                    prefs.set(hp.name, TRACKED_MODES[index - autoOffset])
                }
                prefsVersion++
            }
        }

        // --- Since last charge ---
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Since last charge", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenStats) { Text("Graphs and health") }
            }
            val cycle = stats?.sinceCharge
            Row(Modifier.fillMaxWidth()) {
                Stat(cycle?.let { hoursText(it.trackedHours) } ?: "0 min", "listened", Modifier.weight(1f))
                Stat(cycle?.let { "${it.percentUsed}%" } ?: "0%", "battery used", Modifier.weight(1f))
                Stat("${volume.percent}%", "volume now", Modifier.weight(1f))
            }
            ClaimLine(hp, shownMode, stats)
        }

        // --- Per-headphone tracking switch ---
        Panel {
            SwitchRow(
                title = "Track these headphones",
                subtitle = if (tracked) "Battery, mode and volume are recorded" else "Nothing is recorded for these headphones",
                checked = tracked,
            ) { on ->
                if (on) {
                    if (prefs.get(hp.name) == TrackingMode.OFF) prefs.set(hp.name, TrackingMode.NORMAL)
                } else {
                    prefs.setAutoDetect(hp.name, false)
                    prefs.set(hp.name, TrackingMode.OFF)
                }
                if (on && reading.supportsAuto) prefs.setAutoDetect(hp.name, true)
                prefsVersion++
            }
        }
    }
}

/** Slim battery bar under the photo; fills in the mode colour and animates when the level changes. */
@Composable
private fun BatteryBar(level: Int?, color: androidx.compose.ui.graphics.Color, charging: Boolean) {
    val progress by androidx.compose.animation.core.animateFloatAsState((level ?: 0) / 100f, label = "bar")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    androidx.compose.foundation.Canvas(Modifier.width(200.dp).height(8.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (level != null && level > 0) {
            drawRoundRect(
                color.copy(alpha = if (charging) 0.7f else 1f),
                size = androidx.compose.ui.geometry.Size(size.width * progress, size.height),
                cornerRadius = r,
            )
        }
    }
}

/** Segmented control: one row, the selected segment raised. */
@Composable
private fun ModeSwitch(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, (label, selected) ->
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One sentence comparing real usage with the company's claim for the current mode. */
@Composable
private fun ClaimLine(hp: Headphone, mode: TrackingMode?, stats: HomeStats?) {
    val model = hp.model ?: return
    if (mode == null) return
    val claim = model.claimedHours[mode]
    val st = stats?.perMode?.get(mode)
    val full = st?.takeIf { !it.isEarlyEstimate }?.projectedFullChargeHours
    val text = when {
        claim == null && full != null -> "At your rate a full charge lasts about ${hoursText(full)} in ${mode.label}. ${model.brand} gives no figure for this mode."
        claim == null -> "${model.brand} gives no battery figure for ${mode.label} mode."
        full != null -> "${model.brand} claims ${hoursText(claim)} in ${mode.label}. At your rate a full charge lasts about ${hoursText(full)} (${(full / claim * 100).roundToInt()}% of the claim)."
        else -> "${model.brand} claims ${hoursText(claim)} in ${mode.label}. Your own figure appears after about 2 hours of listening in this mode."
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsSection(
    backgroundOn: Boolean,
    onBackgroundChange: (Boolean) -> Unit,
    keepAlive: Boolean,
    onKeepAliveChange: (Boolean) -> Unit,
    unrestricted: Boolean,
    onFixBattery: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Panel {
        SwitchRow(
            title = "Record in the background",
            subtitle = if (backgroundOn) "Keeps recording with the app closed. A notification shows while it runs." else "Turn on to collect your real battery data.",
            checked = backgroundOn,
            onChange = onBackgroundChange,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SwitchRow(
            title = "Keep running after closing the app",
            subtitle = "Restarts recording if the app is swiped away from recent apps, after a phone restart, and when headphones connect.",
            checked = keepAlive,
            onChange = onKeepAliveChange,
        )
        if (keepAlive) {
            if (unrestricted) {
                Text(
                    "Battery optimisation is off for this app, so Android lets it restart.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Battery optimisation is still on for this app. Android may stop it and block the restart.",
                    fontSize = 12.sp,
                )
                TextButton(onClick = onFixBattery) { Text("Turn off battery optimisation") }
            }
            Text(
                "Some phones (Xiaomi, OnePlus, Samsung, Oppo, Vivo) close apps more aggressively. In the app's system settings, allow Autostart and set Battery to \"No restrictions\". You can also lock the app in the recent apps screen.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenAppSettings) { Text("Open app settings") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Diagnostics")
                Text("Bluetooth details and the headphone link log", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpenDiagnostics) { Text("Open") }
        }
    }
}

private fun isBatteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/** Shows Android's "Let app always run in background?" prompt; falls back to the battery settings list. */
private fun openBatteryOptimisationRequest(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
        }
    }
}

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    } catch (_: Exception) {
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A quiet raised area. Only used to group related controls, not around every item. */
@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

// ---- text helpers ----

private fun modeLine(reading: LiveReading, manual: TrackingMode): String {
    val detected = reading.detectedMode
    return when {
        reading.effectiveMode == TrackingMode.OFF -> "Not being tracked"
        reading.autoDetect && detected != null -> "${detected.label}, read from the headphones"
        reading.autoDetect -> "Reading the mode from the headphones…"
        else -> "${manual.label}, set by you"
    }
}

/** Only says something when the live link isn't working, so a healthy screen stays quiet. */
private fun linkProblem(reading: LiveReading): String? {
    if (!reading.supportsAuto) return null
    val sony = reading.sony ?: return "Connecting to the headphones…"
    return when (sony.status) {
        SonyState.Status.CONNECTED -> null
        SonyState.Status.CONNECTING, SonyState.Status.IDLE -> "Connecting to the headphones…"
        SonyState.Status.FAILED -> sony.message
    }
}

private class TimeLeft(val hours: Double, val basis: String)

/** Time left from your own drain rate in this mode when there's enough data, otherwise from the claim. */
private fun estimateTimeLeft(hp: Headphone, reading: LiveReading, mode: TrackingMode?, stats: HomeStats?): TimeLeft? {
    val battery = reading.battery ?: return null
    if (mode == null) return null
    val own = stats?.perMode?.get(mode)?.takeIf { !it.isEarlyEstimate && it.percentPerHour > 0 }
    if (own != null) return TimeLeft(battery / own.percentPerHour, "at your usual ${mode.label} rate")
    val model = hp.model ?: return null
    val claim = model.claimedHours[mode] ?: return null
    return TimeLeft(claim * battery / 100, "based on ${model.brand}'s ${mode.label} claim")
}

private fun hoursText(hours: Double): String {
    val totalMinutes = (hours * 60).roundToInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0 -> "$m min"
        h >= 10 || m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}
