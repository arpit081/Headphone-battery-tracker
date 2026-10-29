package com.arpit.headphonebatterytracker.diagnostics

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

data class DeviceInfo(
    val name: String,
    val address: String,
    val type: String,
    val deviceClass: String,
    /** BluetoothClass major class (e.g. AUDIO_VIDEO, COMPUTER), or null if unknown. */
    val majorClass: Int?,
    val connectedProfiles: List<String>,
    /** From the hidden BluetoothDevice.getBatteryLevel(); null when Android doesn't allow or report it. */
    val hiddenBatteryPercent: Int?,
)

data class VolumeInfo(
    val current: Int,
    val max: Int,
    val bluetoothOutputs: List<String>,
) {
    val percent: Int get() = if (max > 0) (current * 100) / max else 0
}

/** Phase 1 spike helper: reads paired/connected devices and the phone's media volume. */
@SuppressLint("MissingPermission") // The UI only calls this after BLUETOOTH_CONNECT is granted.
class BluetoothDiagnostics(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    /**
     * Finds which paired devices are currently connected over A2DP (audio) and HFP (calls).
     * The profile connections are opened once and reused, so repeated calls are cheap.
     */
    fun loadConnectedProfiles(onResult: (Map<String, List<String>>) -> Unit) {
        val btAdapter = adapter
        if (btAdapter == null) {
            onResult(emptyMap())
            return
        }
        ProfileProxies.ensure(context, btAdapter, PROFILES.map { it.first }) {
            val result = mutableMapOf<String, MutableList<String>>()
            for ((profileId, label) in PROFILES) {
                val proxy = ProfileProxies.get(profileId) ?: continue
                try {
                    for (device in proxy.connectedDevices) {
                        result.getOrPut(device.address) { mutableListOf() }.add(label)
                    }
                } catch (e: SecurityException) {
                    // Permission revoked while running; report what we have.
                }
            }
            onResult(result)
        }
    }

    fun bondedDevices(connected: Map<String, List<String>>): List<DeviceInfo> =
        adapter?.bondedDevices.orEmpty()
            .map { d ->
                DeviceInfo(
                    name = d.name ?: "(unnamed)",
                    address = d.address,
                    type = typeLabel(d.type),
                    deviceClass = classLabel(d),
                    majorClass = d.bluetoothClass?.majorDeviceClass,
                    connectedProfiles = connected[d.address].orEmpty(),
                    hiddenBatteryPercent = hiddenBattery(d),
                )
            }
            .sortedWith(compareByDescending<DeviceInfo> { it.connectedProfiles.isNotEmpty() }.thenBy { it.name })

    /** Media volume only (no device list); cheap enough to call every second. */
    fun readMediaVolume(): VolumeInfo {
        val audio = context.getSystemService(AudioManager::class.java)
        return VolumeInfo(
            current = audio.getStreamVolume(AudioManager.STREAM_MUSIC), 
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            bluetoothOutputs = emptyList(),
        )
    }

    fun readVolume(): VolumeInfo {
        val audio = context.getSystemService(AudioManager::class.java)
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            .map { "${it.productName} (audio type ${it.type})" }
        return VolumeInfo(
            current = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            bluetoothOutputs = outputs,
        )
    }

    private fun hiddenBattery(device: BluetoothDevice): Int? = try {
        val method = BATTERY_METHOD ?: throw NoSuchMethodException()
        val level = method.invoke(device) as Int
        if (level in 0..100) level else null
    } catch (e: Throwable) {
        null
    }

    private fun typeLabel(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "LE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual (Classic + LE)"
        else -> "Unknown"
    }

    private fun classLabel(device: BluetoothDevice): String {
        val btClass = device.bluetoothClass ?: return "unknown"
        return when (btClass.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> "Headphones"
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> "Wearable headset"
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> "Hands-free"
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> "Loudspeaker"
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> "Portable audio"
            else -> "0x" + btClass.deviceClass.toString(16)
        }
    }
}

private val PROFILES = listOf(BluetoothProfile.A2DP to "A2DP", BluetoothProfile.HEADSET to "HFP")

/** Looked up once: the hidden BluetoothDevice.getBatteryLevel(), or null if Android doesn't allow it. */
private val BATTERY_METHOD: java.lang.reflect.Method? =
    try {
        BluetoothDevice::class.java.getMethod("getBatteryLevel")
    } catch (e: Throwable) {
        null
    }

/** App-wide, long-lived connections to Android's A2DP and HFP services (opening them is the slow part). */
@SuppressLint("MissingPermission")
private object ProfileProxies {
    private val proxies = java.util.concurrent.ConcurrentHashMap<Int, BluetoothProfile>()
    private val binding = mutableSetOf<Int>()
    private val waiting = mutableListOf<() -> Unit>()

    fun get(profileId: Int): BluetoothProfile? = proxies[profileId]

    /** Runs [then] once each profile in [ids] is connected (or failed to connect). */
    fun ensure(context: Context, adapter: BluetoothAdapter, ids: List<Int>, then: () -> Unit) {
        var runNow: Boolean
        synchronized(this) {
            val missing = ids.filter { !proxies.containsKey(it) }
            runNow = missing.isEmpty()
            if (!runNow) {
                waiting += then
                for (id in missing) {
                    if (!binding.add(id)) continue
                    val started = adapter.getProfileProxy(
                        context.applicationContext,
                        object : BluetoothProfile.ServiceListener {
                            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                                proxies[profile] = proxy
                                finished(profile)
                            }

                            override fun onServiceDisconnected(profile: Int) {
                                proxies.remove(profile)
                            }
                        },
                        id,
                    )
                    if (!started) binding.remove(id)
                }
                if (binding.isEmpty()) {
                    // Nothing could be bound; don't leave the caller waiting.
                    waiting.remove(then)
                    runNow = true
                }
            }
        }
        if (runNow) then()
    }

    private fun finished(profileId: Int) {
        val callbacks: List<() -> Unit>
        synchronized(this) {
            binding.remove(profileId)
            if (binding.isNotEmpty()) return
            callbacks = waiting.toList()
            waiting.clear()
        }
        callbacks.forEach { it() }
    }
}

