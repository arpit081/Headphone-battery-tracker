package com.arpit.headphonebatterytracker.diagnostics

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Phase 1 spike helper: connects to a paired device over BLE GATT, lists every service and
 * characteristic it exposes, and reads the standard Battery Level and Device Information values.
 */
@SuppressLint("MissingPermission") // The UI only calls this after BLUETOOTH_CONNECT is granted.
class GattDumper(
    private val context: Context,
    private val onLog: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()

    private fun log(message: String) {
        main.post { onLog(message) }
    }

    fun dump(address: String) {
        close()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (device == null) {
            log("Could not find device $address")
            return
        }
        log("Connecting over BLE GATT to $address ...")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        readQueue.clear()
        gatt?.close()
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected (status $status). Discovering services ...")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val reason = when (status) {
                        0 -> "normal disconnect"
                        133 -> "generic error: device refused or is busy"
                        147 -> "connection timed out: device did not answer over BLE"
                        62 -> "connection failed to establish"
                        8 -> "link supervision timeout: device went out of range"
                        else -> "see Android GATT status codes"
                    }
                    log("Disconnected (status $status: $reason).")
                    g.close()
                    if (gatt == g) gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed (status $status)")
                return
            }
            if (g.services.isEmpty()) log("No services found.")
            for (service in g.services) {
                log("SERVICE ${describe(service.uuid)}")
                for (c in service.characteristics) {
                    log("  CHAR ${describe(c.uuid)} [${props(c.properties)}]")
                    val readable = (c.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                    if (readable && c.uuid in READ_TARGETS) readQueue.addLast(c)
                }
            }
            readNext(g)
        }

        // Android 13+ calls this overload.
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleRead(g, characteristic, value, status)
        }

        // Android 12 / 12L call this older overload.
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            handleRead(g, characteristic, characteristic.value ?: ByteArray(0), status)
        }
    }

    private fun handleRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        val where = "${describe(c.uuid)} in ${describe(c.service.uuid)}"
        if (status == BluetoothGatt.GATT_SUCCESS) {
            log("READ $where = ${format(c.uuid, value)}")
        } else {
            log("READ $where failed (status $status)")
        }
        readNext(g)
    }

    private fun readNext(g: BluetoothGatt) {
        val next = readQueue.removeFirstOrNull()
        if (next == null) {
            log("Done.")
            return
        }
        if (!g.readCharacteristic(next)) {
            log("Could not start read of ${describe(next.uuid)}")
            readNext(g)
        }
    }

    private fun format(uuid: UUID, value: ByteArray): String =
        if (uuid == BATTERY_LEVEL) {
            "${value.firstOrNull()?.toInt()?.and(0xFF)}%"
        } else {
            String(value, Charsets.UTF_8)
        }

    private fun props(p: Int): String {
        val names = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) names.add("read")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names.add("write")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) names.add("write-no-resp")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names.add("notify")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names.add("indicate")
        return names.joinToString("/")
    }

    private fun shortId(uuid: UUID): String? {
        val s = uuid.toString().lowercase()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8) else null
    }

    private fun describe(uuid: UUID): String {
        val id = shortId(uuid)
        val name = id?.let { KNOWN_NAMES[it] }
        return when {
            name != null -> "$name ($id)"
            id != null -> "0x$id"
            else -> "$uuid (vendor-specific)"
        }
    }

    private companion object {
        fun fromShort(id: String): UUID = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")

        val BATTERY_LEVEL: UUID = fromShort("2a19")

        val READ_TARGETS: Set<UUID> = setOf("2a19", "2a29", "2a24", "2a25", "2a26", "2a27", "2a28")
            .map { fromShort(it) }
            .toSet()

        val KNOWN_NAMES = mapOf(
            "1800" to "Generic Access",
            "1801" to "Generic Attribute",
            "180a" to "Device Information",
            "180f" to "Battery Service",
            "2a19" to "Battery Level",
            "2a24" to "Model Number",
            "2a25" to "Serial Number",
            "2a26" to "Firmware Revision",
            "2a27" to "Hardware Revision",
            "2a28" to "Software Revision",
            "2a29" to "Manufacturer Name",
        )
    }
}
