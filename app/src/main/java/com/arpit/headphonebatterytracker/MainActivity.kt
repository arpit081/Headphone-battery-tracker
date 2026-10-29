package com.arpit.headphonebatterytracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.arpit.headphonebatterytracker.diagnostics.DiagnosticsScreen
import com.arpit.headphonebatterytracker.ui.AppVisibility
import com.arpit.headphonebatterytracker.ui.BluetoothPermissionGate
import com.arpit.headphonebatterytracker.ui.home.HomeScreen
import com.arpit.headphonebatterytracker.ui.stats.StatsScreen
import com.arpit.headphonebatterytracker.ui.theme.HeadphoneBatteryTrackerTheme

class MainActivity : ComponentActivity() {
    /**
     * Asks for the display's fastest refresh rate (e.g. 120 Hz) at the current resolution. Android may
     * still lower it, for example in battery saver.
     */
    private fun requestHighestRefreshRate() {
        val display = display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.set(true)
    }

    override fun onStop() {
        AppVisibility.set(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        requestHighestRefreshRate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighestRefreshRate()
        setContent {
            HeadphoneBatteryTrackerTheme {
                // "home", "diagnostics" or "stats:<headphone name>"
                var screen by rememberSaveable { mutableStateOf("home") }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BluetoothPermissionGate(modifier = Modifier.padding(innerPadding)) {
                        when {
                            screen == "diagnostics" -> {
                                BackHandler { screen = "home" }
                                Column {
                                    TextButton(onClick = { screen = "home" }) { Text("← Back") }
                                    DiagnosticsScreen()
                                }
                            }
                            screen.startsWith("stats:") -> {
                                BackHandler { screen = "home" }
                                StatsScreen(
                                    headphoneName = screen.removePrefix("stats:"),
                                    onBack = { screen = "home" },
                                )
                            }
                            else -> HomeScreen(
                                onOpenDiagnostics = { screen = "diagnostics" },
                                onOpenStats = { name -> screen = "stats:$name" },
                            )
                        }
                    }
                }
            }
        }
    }
}
