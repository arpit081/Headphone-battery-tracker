package com.arpit.headphonebatterytracker.sony

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** App-wide owner of the Sony connection, shared by the home screen and the tracking service. */
object SonyManager {
    private const val RETRY_AFTER_FAILURE_MS = 30_000L
    private const val MAX_LOG_LINES = 200
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _state = MutableStateFlow(SonyState())
    val state: StateFlow<SonyState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var link: SonyLink? = null
    private var lastFailureAt = 0L

    /** Connects to the Sony headphones at [address] unless already connected (retries 30 s after a failure). */
    @Synchronized
    fun ensureConnected(context: Context, address: String) {
        val current = link
        if (current != null && current.isAlive && current.address == address) return
        if (current?.address == address && System.currentTimeMillis() - lastFailureAt < RETRY_AFTER_FAILURE_MS) return
        current?.close()
        val newLink = SonyLink(
            context = context.applicationContext,
            address = address,
            updateState = { transform -> _state.update(transform) },
            log = ::appendLog,
            onFailed = { lastFailureAt = System.currentTimeMillis() },
        )
        link = newLink
        newLink.start()
    }

    /** Asks the connected headphones for fresh battery and mode values. */
    fun refresh() {
        link?.requestStatus()
    }

    private fun appendLog(line: String) {
        val stamped = "${LocalTime.now().format(timeFormat)} $line"
        _log.update { (it + stamped).takeLast(MAX_LOG_LINES) }
    }
}
