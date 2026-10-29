package com.arpit.headphonebatterytracker.headphones

import android.bluetooth.BluetoothClass
import android.content.Context
import com.arpit.headphonebatterytracker.diagnostics.BluetoothDiagnostics
import com.arpit.headphonebatterytracker.diagnostics.DeviceInfo
import com.arpit.headphonebatterytracker.diagnostics.VolumeInfo

/** One physical pair of headphones (several Bluetooth entries with the same name are merged). */
data class Headphone(
    val name: String,
    val addresses: List<String>,
    /** The Classic audio address (the one connected over A2DP), used for the Sony control channel. */
    val primaryAddress: String,
    val connected: Boolean,
    val profiles: List<String>,
    val batteryPercent: Int?,
    val model: HeadphoneModel?,
)

/** Reads paired/connected headphones and the phone's media volume. Call only with BLUETOOTH_CONNECT granted. */
class HeadphoneMonitor(context: Context) {
    private val diag = BluetoothDiagnostics(context)

    fun load(onResult: (List<Headphone>) -> Unit) {
        diag.loadConnectedProfiles { connected -> onResult(merge(diag.bondedDevices(connected))) }
    }

    fun readVolume(): VolumeInfo = diag.readVolume()

    /** Volume without the audio-device list; for frequent UI updates. */
    fun readMediaVolume(): VolumeInfo = diag.readMediaVolume()

    companion object {
        private val EXCLUDED_MAJOR = setOf(
            BluetoothClass.Device.Major.COMPUTER,
            BluetoothClass.Device.Major.PHONE,
        )

        /**
         * Groups entries by name (e.g. the XM5 shows up once as a Classic audio device and once as an
         * uncategorized LE device), drops computers and phones, and keeps groups that look like audio.
         */
        fun merge(devices: List<DeviceInfo>): List<Headphone> =
            devices.groupBy { it.name }
                .filter { (_, group) ->
                    group.none { it.majorClass in EXCLUDED_MAJOR } &&
                        group.any {
                            it.majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                                "A2DP" in it.connectedProfiles
                        }
                }
                .map { (name, group) ->
                    Headphone(
                        name = name,
                        addresses = group.map { it.address },
                        primaryAddress = (
                            group.firstOrNull { "A2DP" in it.connectedProfiles }
                                ?: group.firstOrNull { it.majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO }
                                ?: group.first()
                            ).address,
                        connected = group.any { it.connectedProfiles.isNotEmpty() },
                        profiles = group.flatMap { it.connectedProfiles }.distinct(),
                        batteryPercent = group.firstNotNullOfOrNull { it.hiddenBatteryPercent },
                        model = HeadphoneCatalog.match(name),
                    )
                }
                .sortedWith(compareByDescending<Headphone> { it.connected }.thenBy { it.name })
    }
}
