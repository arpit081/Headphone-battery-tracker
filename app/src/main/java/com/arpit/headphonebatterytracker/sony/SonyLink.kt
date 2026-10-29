package com.arpit.headphonebatterytracker.sony

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One connection to Sony headphones over their Bluetooth Classic (RFCOMM) control channel.
 * Runs on its own thread: opens the channel, sends the init request, then asks for battery and
 * noise-control state every minute. The headphones also push updates when the user changes mode.
 */
@SuppressLint("MissingPermission") // Only started after BLUETOOTH_CONNECT is granted.
internal class SonyLink(
    private val context: Context,
    val address: String,
    private val updateState: ((SonyState) -> SonyState) -> Unit,
    private val log: (String) -> Unit,
    private val onFailed: () -> Unit,
) : Thread("sony-link") {

    @Volatile private var closed = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    private val writeLock = Any()
    private val lock = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    // Guarded by [lock]
    private val queue = ArrayDeque<ByteArray>()
    private var waitingForAck = false
    private var ackTimeout: ScheduledFuture<*>? = null
    private var sequence = 0

    @Volatile private var initDone = false
    private var initTries = 0
    @Volatile private var ncType = NC_TYPE_DEFAULT // the variant these headphones answered
    @Volatile private var ncReplied = false

    override fun run() {
        updateState {
            SonyState(address = address, status = SonyState.Status.CONNECTING, message = "Connecting to headphones…")
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (device == null) {
            fail("Bluetooth device not found")
            return
        }

        val known = device.uuids?.map { it.uuid }.orEmpty()
        val candidates = mutableListOf<UUID>()
        if (UUID_V2 in known) candidates += UUID_V2
        if (UUID_V1 in known) candidates += UUID_V1
        if (candidates.isEmpty()) candidates += listOf(UUID_V2, UUID_V1)

        var opened: BluetoothSocket? = null
        for (uuid in candidates) {
            if (closed) return
            try {
                log("Opening Sony channel ${label(uuid)}…")
                val s = device.createRfcommSocketToServiceRecord(uuid)
                s.connect()
                opened = s
                log("Sony channel ${label(uuid)} open")
                break
            } catch (e: IOException) {
                log("Sony channel ${label(uuid)} failed: ${e.message}")
            }
        }

        val s = opened
        if (s == null) {
            fail("Could not open the Sony channel. If the Sony app is open, close it and try again.")
            return
        }
        socket = s
        try {
            output = s.outputStream
            updateState { it.copy(status = SonyState.Status.CONNECTED, message = "Connected, waiting for reply…") }
            sendInit()
            readLoop(s.inputStream)
        } catch (e: IOException) {
            if (!closed) fail("Connection lost (${e.message})")
        } finally {
            scheduler.shutdownNow()
            try {
                s.close()
            } catch (_: IOException) {
            }
        }
    }

    fun close() {
        closed = true
        scheduler.shutdownNow()
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    /** Ask for battery and noise-control state. */
    fun requestStatus() {
        if (!initDone) return
        enqueue(byteArrayOf(CMD_BATTERY_GET.toByte(), BATTERY_TYPE_SINGLE.toByte()))
        if (ncReplied) {
            enqueue(byteArrayOf(CMD_NC_GET.toByte(), ncType.toByte()))
        } else {
            // Until we know which variant these headphones answer, ask both.
            enqueue(byteArrayOf(CMD_NC_GET.toByte(), NC_TYPE_DEFAULT.toByte()))
            enqueue(byteArrayOf(CMD_NC_GET.toByte(), NC_TYPE_ALT.toByte()))
        }
    }

    // ---- sending ----

    private fun sendInit() {
        initTries++
        enqueue(byteArrayOf(CMD_INIT.toByte(), 0x00))
        schedule(INIT_RETRY_MS) {
            if (!initDone && !closed) {
                if (initTries < 3) {
                    log("No init reply, retrying")
                    sendInit()
                } else {
                    fail("Headphones did not answer the Sony protocol")
                    close()
                }
            }
        }
    }

    private fun enqueue(payload: ByteArray) {
        synchronized(lock) {
            queue.addLast(payload)
            pump()
        }
    }

    /** Sends the next queued command if we're not waiting for an ACK. Call with [lock] held. */
    private fun pump() {
        if (waitingForAck) return
        val next = queue.removeFirstOrNull() ?: return
        waitingForAck = true
        send(SonyFrame.TYPE_COMMAND_1, sequence, next)
        ackTimeout = schedule(ACK_TIMEOUT_MS) {
            synchronized(lock) {
                if (waitingForAck) {
                    log("No ACK, moving on")
                    waitingForAck = false
                    pump()
                }
            }
        }
    }

    private fun onAck(ackSequence: Int) {
        synchronized(lock) {
            sequence = ackSequence
            waitingForAck = false
            ackTimeout?.cancel(false)
            pump()
        }
    }

    private fun send(type: Int, seq: Int, payload: ByteArray, quiet: Boolean = false) {
        val bytes = SonyFrame.encode(type, seq, payload)
        try {
            synchronized(writeLock) {
                output?.write(bytes)
                output?.flush()
            }
            if (!quiet) log("TX ${payload.toHex()}")
        } catch (e: IOException) {
            log("Write failed: ${e.message}")
        }
    }

    // ---- receiving ----

    private fun readLoop(input: InputStream) {
        val frame = ByteArrayOutputStream()
        var inFrame = false
        while (!closed) {
            val v = input.read()
            if (v < 0) throw IOException("stream closed")
            when {
                v == SonyFrame.START -> {
                    frame.reset()
                    inFrame = true
                }
                v == SonyFrame.END && inFrame -> {
                    inFrame = false
                    val raw = frame.toByteArray()
                    val message = SonyFrame.decode(raw)
                    if (message == null) log("RX bad frame ${raw.toHex()}") else onMessage(message)
                }
                inFrame -> frame.write(v)
            }
        }
    }

    private fun onMessage(message: SonyFrame.Message) {
        when (message.type) {
            SonyFrame.TYPE_ACK -> onAck(message.sequence)
            SonyFrame.TYPE_COMMAND_1, SonyFrame.TYPE_COMMAND_2 -> {
                log("RX ${message.payload.toHex()}")
                send(SonyFrame.TYPE_ACK, 1 - message.sequence, ByteArray(0), quiet = true)
                if (message.payload.isNotEmpty()) handlePayload(message.payload)
            }
            else -> log("RX unknown message type ${message.type}")
        }
    }

    private fun handlePayload(p: ByteArray) {
        when (p[0].toInt() and 0xFF) {
            CMD_INIT_REPLY -> onInitReply(p)
            CMD_BATTERY_REPLY, CMD_BATTERY_NOTIFY -> parseBattery(p)
            CMD_NC_REPLY, CMD_NC_NOTIFY -> parseNoiseControl(p)
        }
    }

    private fun onInitReply(p: ByteArray) {
        if (initDone) return
        initDone = true
        if (p.size == 4) {
            updateState { it.copy(protocol = "v1", message = "Older Sony protocol (v1) is not supported yet") }
            log("Protocol v1 detected: not supported yet")
            return
        }
        updateState { it.copy(protocol = "v2", message = "Live from headphones") }
        log("Protocol v2 ready")
        requestStatus()
        // Ask again soon if the mode didn't come back on the first try.
        schedule(QUICK_RETRY_MS) {
            if (!ncReplied) {
                log("No mode reply yet, asking again")
                requestStatus()
            }
        }
        try {
            scheduler.scheduleWithFixedDelay(
                Runnable { requestStatus() },
                POLL_MS,
                POLL_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Reply: [cmd, type 0x00, level 0–100, charging 0/1] */
    private fun parseBattery(p: ByteArray) {
        if (p.size < 4 || p[1].toInt() != BATTERY_TYPE_SINGLE) {
            log("Battery message not understood")
            return
        }
        val level = p[2].toInt() and 0xFF
        val charging = p[3].toInt() == 1
        if (level in 0..100) updateState { it.copy(battery = level, charging = charging) }
    }

    /** Reply: [cmd, type 0x15/0x17, change status, on/off, mode (0 = NC, 1 = ambient), …, ambient level] */
    private fun parseNoiseControl(p: ByteArray) {
        val type = if (p.size > 1) p[1].toInt() and 0xFF else -1
        if (p.size < 5 || (type != NC_TYPE_DEFAULT && type != NC_TYPE_ALT)) {
            log("Noise-control message type $type not used")
            return
        }
        ncReplied = true
        ncType = type
        val enabled = p[3].toInt() == 1
        val mode = when {
            !enabled -> SonyNcMode.OFF
            p[4].toInt() == 0 -> SonyNcMode.NOISE_CANCELLING
            p[4].toInt() == 1 -> SonyNcMode.AMBIENT
            else -> null
        }
        if (mode == null) {
            log("Noise-control mode not understood")
            return
        }
        val level = if (p.size >= 7) p.last().toInt() and 0xFF else null
        updateState { it.copy(ncMode = mode, ambientLevel = level) }
    }

    // ---- helpers ----

    private fun fail(message: String) {
        log(message)
        updateState { it.copy(address = address, status = SonyState.Status.FAILED, message = message) }
        onFailed()
    }

    private fun schedule(delayMs: Long, block: () -> Unit): ScheduledFuture<*>? =
        try {
            scheduler.schedule(
                Runnable {
                    try {
                        block()
                    } catch (e: Exception) {
                        log("Error: ${e.message}")
                    }
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            null
        }

    private fun label(uuid: UUID) = if (uuid == UUID_V2) "v2" else "v1"

    private companion object {
        val UUID_V2: UUID = UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2")
        val UUID_V1: UUID = UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA")

        const val CMD_INIT = 0x00
        const val CMD_INIT_REPLY = 0x01
        const val CMD_BATTERY_GET = 0x22
        const val CMD_BATTERY_REPLY = 0x23
        const val CMD_BATTERY_NOTIFY = 0x25
        const val CMD_NC_GET = 0x66
        const val CMD_NC_REPLY = 0x67
        const val CMD_NC_NOTIFY = 0x69

        const val BATTERY_TYPE_SINGLE = 0x00
        const val NC_TYPE_DEFAULT = 0x15 // used by the WH-1000XM5
        const val NC_TYPE_ALT = 0x17 // models with wind-noise reduction

        const val INIT_RETRY_MS = 2_000L
        const val ACK_TIMEOUT_MS = 2_000L
        const val QUICK_RETRY_MS = 5_000L
        const val POLL_MS = 60_000L
    }
}
