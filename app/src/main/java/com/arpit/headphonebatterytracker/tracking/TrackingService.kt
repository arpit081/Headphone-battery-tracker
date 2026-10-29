package com.arpit.headphonebatterytracker.tracking

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arpit.headphonebatterytracker.MainActivity
import com.arpit.headphonebatterytracker.headphones.HeadphoneMonitor
import com.arpit.headphonebatterytracker.headphones.LiveReadings
import com.arpit.headphonebatterytracker.headphones.TrackingMode
import com.arpit.headphonebatterytracker.headphones.TrackingPreferences
import com.arpit.headphonebatterytracker.sony.SonyManager

/**
 * Foreground service that samples battery, volume and the mode (detected from the headphones, or
 * selected by hand) every minute while headphones are connected. Skips samples while charging. A sample is written when something changed, or every 5 minutes.
 */
class TrackingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var monitor: HeadphoneMonitor
    private lateinit var prefs: TrackingPreferences
    private lateinit var store: SampleStore
    private val lastWritten = mutableMapOf<String, Sample>()
    private var lastNotificationText: String? = null

    /** One sample; sampleNow() schedules the next one only while headphones are connected. */
    private val tick = Runnable { sampleNow() }

    /** Wakes the service when a Bluetooth device connects or disconnects (instead of polling while idle). */
    private val bluetoothEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, EVENT_SETTLE_MS) // give the audio profiles a moment to settle
        }
    }

    override fun onCreate() {
        super.onCreate()
        monitor = HeadphoneMonitor(this)
        prefs = TrackingPreferences(this)
        store = SampleStore.get(this)
        createChannel()
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, bluetoothEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasBluetooth = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (intent?.action == ACTION_STOP || !hasBluetooth) {
            prefs.trackingEnabled = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Waiting for headphones…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    /** Called when the app is swiped away from recents. Schedules a quick restart if the user asked for it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.trackingEnabled && prefs.keepAlive) {
            val restart = PendingIntent.getBroadcast(
                this,
                2,
                Intent(this, RestartReceiver::class.java).setAction(RestartReceiver.ACTION_RESTART),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 3_000L,
                restart,
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try {
            unregisterReceiver(bluetoothEvents)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sampleNow() {
        monitor.load { headphones ->
            val volume = monitor.readVolume()
            val now = System.currentTimeMillis()
            val status = mutableListOf<String>()

            for (hp in headphones.filter { it.connected }) {
                if (hp.model?.supportsSonyProtocol == true) {
                    SonyManager.ensureConnected(this, hp.primaryAddress)
                }
                val reading = LiveReadings.resolve(hp, prefs, SonyManager.state.value)
                val mode = reading.effectiveMode
                val batteryText = reading.battery?.let { "$it%" } ?: "battery ?"
                when {
                    mode == TrackingMode.OFF -> {
                        status += "${hp.name}: tracking off"
                        continue
                    }
                    mode == null -> {
                        status += "${hp.name}: $batteryText · waiting for mode"
                        continue
                    }
                    reading.charging -> {
                        status += "${hp.name}: $batteryText · charging"
                        continue
                    }
                }
                val sample = Sample(
                    timestamp = now,
                    device = hp.name,
                    battery = reading.battery,
                    batterySource = reading.batterySourceKey,
                    mode = mode!!,
                    volume = volume.current,
                    volumeMax = volume.max,
                    volumeSource = VOLUME_SOURCE_STREAM,
                )
                val last = lastWritten[hp.name]
                if (last == null || sample.differsFrom(last) || now - last.timestamp >= FORCE_WRITE_MS) {
                    store.insert(sample)
                    lastWritten[hp.name] = sample
                }
                val how = if (reading.autoDetect) "${mode.label} (auto)" else mode.label
                status += "${hp.name}: $batteryText · $how"
            }

            // Keep sampling only while headphones are connected; otherwise sleep until a Bluetooth event.
            handler.removeCallbacks(tick)
            if (headphones.any { it.connected }) handler.postDelayed(tick, SAMPLE_INTERVAL_MS)

            val text = if (status.isEmpty()) "Waiting for headphones…" else status.joinToString("  |  ")
            if (text != lastNotificationText) {
                lastNotificationText = text
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Battery tracking", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while headphone battery tracking is running"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Headphone tracking on")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                "Stop",
                PendingIntent.getService(
                    this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.arpit.headphonebatterytracker.STOP_TRACKING"
        private const val SAMPLE_INTERVAL_MS = 60_000L
        private const val FORCE_WRITE_MS = 5 * 60_000L
        private const val EVENT_SETTLE_MS = 2_000L
        const val VOLUME_SOURCE_STREAM = "stream"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
