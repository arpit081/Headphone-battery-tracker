package com.arpit.headphonebatterytracker.tracking

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.arpit.headphonebatterytracker.headphones.TrackingMode

/** Local SQLite database of battery samples (framework SQLite, no extra library). */
class SampleStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "tracking.db", null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                device TEXT NOT NULL,
                battery INTEGER,
                battery_source TEXT NOT NULL,
                mode TEXT NOT NULL,
                volume INTEGER NOT NULL,
                volume_max INTEGER NOT NULL,
                volume_source TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_samples_device_time ON samples(device, timestamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 did not record where the battery value came from, so its rows mixed Android's
        // 10%-step value with the headphones' exact value and produced fake drain. It only held
        // test data, so it is cleared instead of migrated.
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS samples")
            onCreate(db)
        }
    }

    /** Deletes all logged data (all headphones). */
    fun deleteAll() {
        writableDatabase.delete("samples", null, null)
    }

    fun insert(sample: Sample) {
        val values = ContentValues().apply {
            put("timestamp", sample.timestamp)
            put("device", sample.device)
            val battery = sample.battery
            if (battery != null) put("battery", battery) else putNull("battery")
            put("battery_source", sample.batterySource)
            put("mode", sample.mode.name)
            put("volume", sample.volume)
            put("volume_max", sample.volumeMax)
            put("volume_source", sample.volumeSource)
        }
        writableDatabase.insert("samples", null, values)
    }

    fun samples(device: String): List<Sample> {
        val out = mutableListOf<Sample>()
        readableDatabase.query(
            "samples",
            arrayOf("timestamp", "battery", "mode", "volume", "volume_max", "volume_source", "battery_source"),
            "device = ?",
            arrayOf(device),
            null,
            null,
            "timestamp ASC",
        ).use { c ->
            while (c.moveToNext()) {
                out += Sample(
                    timestamp = c.getLong(0),
                    device = device,
                    battery = if (c.isNull(1)) null else c.getInt(1),
                    batterySource = c.getString(6),
                    mode = TrackingMode.entries.firstOrNull { it.name == c.getString(2) } ?: TrackingMode.NORMAL,
                    volume = c.getInt(3),
                    volumeMax = c.getInt(4),
                    volumeSource = c.getString(5),
                )
            }
        }
        return out
    }

    /**
     * Number of samples and the newest timestamp for [device]. Uses the (device, timestamp) index, so it
     * is cheap; callers use it to skip recalculating when nothing new was logged.
     */
    fun fingerprint(device: String): Pair<Long, Long> =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(MAX(timestamp), 0) FROM samples WHERE device = ?",
            arrayOf(device),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else 0L to 0L }

    fun summary(device: String): UsageSummary {
        val all = samples(device)
        return UsageSummary(sampleCount = all.size, perMode = DrainCalculator.compute(all))
    }

    companion object {
        private const val DB_VERSION = 2

        @Volatile
        private var instance: SampleStore? = null

        fun get(context: Context): SampleStore =
            instance ?: synchronized(this) {
                instance ?: SampleStore(context.applicationContext).also { instance = it }
            }
    }
}
