package com.arpit.headphonebatterytracker.sony

import java.io.ByteArrayOutputStream

/**
 * Framing for Sony's headphone control channel (written from publicly documented protocol facts).
 *
 * Frame: 0x3E | type | sequence | payload length (4 bytes, big endian) | payload | checksum | 0x3C
 * - checksum = sum of all bytes from type to the end of the payload, lowest 8 bits
 * - bytes 0x3C, 0x3D, 0x3E between start and end are escaped as 0x3D followed by (byte AND 0xEF)
 */
object SonyFrame {
    const val START = 0x3E
    const val END = 0x3C
    private const val ESCAPE = 0x3D

    const val TYPE_ACK = 0x01
    const val TYPE_COMMAND_1 = 0x0C
    const val TYPE_COMMAND_2 = 0x0E

    class Message(val type: Int, val sequence: Int, val payload: ByteArray)

    fun encode(type: Int, sequence: Int, payload: ByteArray): ByteArray {
        val body = ByteArray(6 + payload.size)
        body[0] = type.toByte()
        body[1] = sequence.toByte()
        val n = payload.size
        body[2] = (n ushr 24).toByte()
        body[3] = (n ushr 16).toByte()
        body[4] = (n ushr 8).toByte()
        body[5] = n.toByte()
        payload.copyInto(body, 6)

        var sum = 0
        for (b in body) sum += b.toInt() and 0xFF

        val out = ByteArrayOutputStream(body.size + 4)
        out.write(START)
        for (b in body) writeEscaped(out, b.toInt() and 0xFF)
        writeEscaped(out, sum and 0xFF)
        out.write(END)
        return out.toByteArray()
    }

    /** [inner] = the bytes between START and END (still escaped). Returns null if invalid. */
    fun decode(inner: ByteArray): Message? {
        val raw = ByteArrayOutputStream(inner.size)
        var i = 0
        while (i < inner.size) {
            val v = inner[i].toInt() and 0xFF
            if (v == ESCAPE) {
                i++
                if (i >= inner.size) return null
                raw.write((inner[i].toInt() and 0xFF) or 0x10)
            } else {
                raw.write(v)
            }
            i++
        }
        val b = raw.toByteArray()
        if (b.size < 7) return null

        val length = ((b[2].toInt() and 0xFF) shl 24) or
            ((b[3].toInt() and 0xFF) shl 16) or
            ((b[4].toInt() and 0xFF) shl 8) or
            (b[5].toInt() and 0xFF)
        if (length != b.size - 7) return null

        var sum = 0
        for (k in 0 until b.size - 1) sum += b[k].toInt() and 0xFF
        if ((sum and 0xFF) != (b.last().toInt() and 0xFF)) return null

        return Message(
            type = b[0].toInt() and 0xFF,
            sequence = b[1].toInt() and 0xFF,
            payload = b.copyOfRange(6, 6 + length),
        )
    }

    private fun writeEscaped(out: ByteArrayOutputStream, v: Int) {
        if (v == START || v == END || v == ESCAPE) {
            out.write(ESCAPE)
            out.write(v and 0xEF)
        } else {
            out.write(v)
        }
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { String.format("%02x", it) }
