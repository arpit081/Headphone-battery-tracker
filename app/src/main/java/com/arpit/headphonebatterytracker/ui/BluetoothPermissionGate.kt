package com.arpit.headphonebatterytracker.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** Shows [content] only once BLUETOOTH_CONNECT is granted; otherwise asks for it. */
@Composable
fun BluetoothPermissionGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    Box(modifier.fillMaxSize()) {
        if (granted) {
            content()
        } else {
            Column(Modifier.padding(16.dp)) {
                Text("Headphone Tracker", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Bluetooth permission is needed to see your headphones and their battery.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                    Text("Grant permission")
                }
            }
        }
    }
}
