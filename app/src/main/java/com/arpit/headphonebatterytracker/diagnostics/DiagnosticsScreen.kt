package com.arpit.headphonebatterytracker.diagnostics

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arpit.headphonebatterytracker.sony.SonyManager
import com.arpit.headphonebatterytracker.tracking.SampleStore
import com.arpit.headphonebatterytracker.ui.AppVisibility
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun hasPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Headphone diagnostics", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (!granted) {
            Text("Bluetooth permission is needed to see your headphones.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                Text("Grant permission")
            }
        } else {
            DiagnosticsContent()
        }
    }
}

@Composable
private fun DiagnosticsContent() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val diag = remember { BluetoothDiagnostics(appContext) }
    val log = remember { mutableStateListOf<String>() }
    val gatt = remember { GattDumper(appContext) { log.add(it) } }
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var volume by remember { mutableStateOf(diag.readVolume()) }
    val listState = rememberLazyListState()
    val sonyLog by SonyManager.log.collectAsState()
    val sonyState by SonyManager.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        diag.loadConnectedProfiles { connected -> devices = diag.bondedDevices(connected) }
    }

    // Volume updates every second; connection status and battery every 3 seconds.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            AppVisibility.awaitVisible() // paused while the app is in the background
            volume = diag.readVolume()
            if (tick % 3 == 0) refresh()
            tick++
            delay(1000)
        }
    }
    DisposableEffect(Unit) { onDispose { gatt.close() } }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { VolumeCard(volume) }
        item { LogCard(log, onCopy = { copyToClipboard(context, log.joinToString("\n")) }, onClear = { log.clear() }) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Sony headphone link", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Status: ${sonyState.status}  ${sonyState.message}\n" +
                            "Protocol: ${sonyState.protocol ?: "-"}  |  Battery: ${sonyState.battery?.let { "$it%" } ?: "-"}" +
                            "${if (sonyState.charging) " (charging)" else ""}  |  Mode: ${sonyState.ncMode ?: "-"}",
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (sonyLog.isEmpty()) {
                        Text("No Sony messages yet. Open the home screen with the XM5 connected.", fontSize = 12.sp)
                    } else {
                        for (line in sonyLog.takeLast(40)) {
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { copyToClipboard(context, sonyLog.joinToString("\n")) }) { Text("Copy Sony log") }
                        Button(onClick = { SonyManager.refresh() }) { Text("Ask again") }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Tracking data", style = MaterialTheme.typography.titleMedium)
                    Text("Deletes every logged sample for all headphones. Cannot be undone.", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (confirmDelete) {
                            SampleStore.get(appContext).deleteAll()
                            confirmDelete = false
                            deleteMessage = "All tracking data deleted."
                        } else {
                            confirmDelete = true
                            deleteMessage = null
                        }
                    }) { Text(if (confirmDelete) "Tap again to delete everything" else "Delete all tracking data") }
                    deleteMessage?.let { Text(it, fontSize = 12.sp) }
                }
            }
        }
        item { Button(onClick = { refresh() }) { Text("Refresh devices") } }
        items(devices) { device ->
            DeviceCard(device) {
                log.clear()
                log.add("Dump requested for ${device.name} (${device.address})")
                gatt.dump(device.address)
                scope.launch { listState.animateScrollToItem(1) }
            }
        }
    }
}

@Composable
private fun LogCard(log: List<String>, onCopy: () -> Unit, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("GATT log", style = MaterialTheme.typography.titleMedium)
            if (log.isEmpty()) {
                Text("Empty. Tap \"Dump GATT\" on a device below.", fontSize = 12.sp)
            } else {
                for (line in log) {
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy) { Text("Copy log") }
                Button(onClick = onClear) { Text("Clear log") }
            }
        }
    }
}

@Composable
private fun VolumeCard(volume: VolumeInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Phone media volume", style = MaterialTheme.typography.titleMedium)
            Text("${volume.current} / ${volume.max}  (${volume.percent}%)")
            Text(
                "Bluetooth audio outputs: " +
                    if (volume.bluetoothOutputs.isEmpty()) "none active" else volume.bluetoothOutputs.joinToString(),
            )
            Text(
                "Test: press the volume buttons on the headphones. If the number above changes, " +
                    "the headphones report absolute volume.",
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceInfo, onDump: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(device.name, style = MaterialTheme.typography.titleMedium)
            Text(device.address, fontSize = 12.sp)
            Text("Class: ${device.deviceClass}  |  Type: ${device.type}")
            Text(
                "Connected: " +
                    if (device.connectedProfiles.isEmpty()) "no" else device.connectedProfiles.joinToString(),
            )
            Text(
                "Battery (Android system value): " +
                    (device.hiddenBatteryPercent?.let { "$it%" } ?: "not available"),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDump) { Text("Dump GATT (services + battery)") }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("headphone-log", text))
}
