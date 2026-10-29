package com.arpit.headphonebatterytracker.sony

enum class SonyNcMode { NOISE_CANCELLING, AMBIENT, OFF }

/** Live data read from Sony headphones over their control channel. */
data class SonyState(
    val address: String? = null,
    val status: Status = Status.IDLE,
    val message: String = "",
    val protocol: String? = null,
    /** 1% precision battery, null until the headphones answer. */
    val battery: Int? = null,
    val charging: Boolean = false,
    val ncMode: SonyNcMode? = null,
    /** Ambient sound level (Sony's 0–20 scale), when reported. */
    val ambientLevel: Int? = null,
) {
    enum class Status { IDLE, CONNECTING, CONNECTED, FAILED }
}
