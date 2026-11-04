package com.glassescompanion.app.ble

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * BLE control protocol of the G6-208B / TK80201 glasses (EYEVUE-family firmware).
 *
 * Command frame (both directions):
 *   SOF(2) | length(2, big endian) | command(1) | payload(n) | checksum(1)
 *   - App -> Glasses SOF = 0xAB 0x55 (written to AA13)
 *   - Glasses -> App SOF = 0xAC 0x55 (notified on AA14)
 *   - length = command + payload + checksum byte count
 *   - checksum = (sum of every byte from SOF through the last payload byte) mod 256
 */
object EyevueBleProtocol {

    val SERVICE: UUID = UUID.fromString("0000aa12-0000-1000-8000-00805f9b34fb")
    val CMD_WRITE: UUID = UUID.fromString("0000aa13-0000-1000-8000-00805f9b34fb")
    val CMD_NOTIFY: UUID = UUID.fromString("0000aa14-0000-1000-8000-00805f9b34fb")
    val PHOTO_NOTIFY: UUID = UUID.fromString("0000aa15-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val SOF_APP_HI = 0xAB
    const val SOF_DEVICE_HI = 0xAC
    const val SOF_LO = 0x55

    /** Glasses Wi-Fi access point (media import channel). */
    const val GLASSES_AP_HOST = "192.168.169.1"
    const val FILE_LIST_PATH = "/app/getfilelist"

    object Cmd {
        const val VOICE_COMMAND = 0x06
        const val DEVICE_PHOTO_RECOGNITION = 0x0A
        const val GET_CAPACITY = 0x16
        const val GET_BATTERY = 0x17
        const val TAKE_PHOTO = 0x22
        /** Video start/stop ids come from public E09-family research; unverified on G6-208B. */
        const val RECORD_VIDEO = 0x23
        const val STOP_VIDEO = 0x24
        const val APP_RECEIVE_WIFI_INFO = 0x25
        const val MUSIC_CONTROL = 0x31
        const val CALL_CONTROL = 0x33
        const val RECORD_AUDIO = 0x34
        const val OPEN_WIFI = 0x39
        /** Only ever sent after the user explicitly confirms deleting the glasses originals. */
        const val FILE_DOWNLOAD_FINISH = 0x44
        const val ACTION_SYNC = 0x45
        const val APP_RECEIVE_VOICE_DATA = 0x46
        const val GET_DEVICE_INFO = 0x55
        const val APP_STOP_VOICE_RECOGNITION = 0x56
        const val APP_START_VOICE_RECOGNITION = 0x57
        const val CUSTOMER_PID_VID = 0x64
        const val GET_SUPPORT_FUNCTION = 0x95

        fun name(id: Int): String = when (id) {
            VOICE_COMMAND -> "VOICE_COMMAND"
            DEVICE_PHOTO_RECOGNITION -> "DEVICE_PHOTO_RECOGNITION"
            GET_CAPACITY -> "GET_CAPACITY"
            GET_BATTERY -> "GET_BATTERY"
            TAKE_PHOTO -> "TAKE_PHOTO"
            RECORD_VIDEO -> "RECORD_VIDEO"
            STOP_VIDEO -> "STOP_VIDEO"
            APP_RECEIVE_WIFI_INFO -> "APP_RECEIVE_WIFI_INFO"
            MUSIC_CONTROL -> "MUSIC_CONTROL"
            CALL_CONTROL -> "CALL_CONTROL"
            RECORD_AUDIO -> "RECORD_AUDIO"
            OPEN_WIFI -> "OPEN_WIFI"
            FILE_DOWNLOAD_FINISH -> "FILE_DOWNLOAD_FINISH"
            ACTION_SYNC -> "ACTION_SYNC"
            APP_RECEIVE_VOICE_DATA -> "APP_RECEIVE_VOICE_DATA"
            GET_DEVICE_INFO -> "GET_DEVICE_INFO"
            APP_STOP_VOICE_RECOGNITION -> "APP_STOP_VOICE_RECOGNITION"
            APP_START_VOICE_RECOGNITION -> "APP_START_VOICE_RECOGNITION"
            CUSTOMER_PID_VID -> "CUSTOMER_PID_VID"
            GET_SUPPORT_FUNCTION -> "GET_SUPPORT_FUNCTION"
            else -> "CMD_0x%02X".format(id)
        }
    }

    /** AA15 photo preview chunk markers (first byte of each notification). */
    object Photo {
        const val PHOTO_START = 0x01
        const val PHOTO_TRANS = 0x02
        const val PHOTO_END = 0x03
    }

    data class Frame(val command: Int, val payload: ByteArray, val checksumOk: Boolean = true) {
        override fun equals(other: Any?): Boolean =
            other is Frame && other.command == command && other.payload.contentEquals(payload)

        override fun hashCode(): Int = 31 * command + payload.contentHashCode()
        override fun toString(): String = "${Cmd.name(command)}[${payload.toHex()}]"
    }

    fun checksum(bytes: ByteArray, endExclusive: Int = bytes.size): Int {
        var sum = 0
        for (i in 0 until endExclusive) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFF
    }

    /** Builds an App -> Glasses frame for the AA13 write characteristic. */
    fun encode(command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val length = 1 + payload.size + 1
        val out = ByteArrayOutputStream(payload.size + 6)
        out.write(SOF_APP_HI)
        out.write(SOF_LO)
        out.write((length shr 8) and 0xFF)
        out.write(length and 0xFF)
        out.write(command and 0xFF)
        out.write(payload)
        val body = out.toByteArray()
        out.write(checksum(body))
        return out.toByteArray()
    }

    /**
     * Incremental parser for AA14 notifications: frames may be split across or
     * packed into notifications. Garbage before a SOF is skipped.
     */
    class StreamParser {
        private val buffer = ByteArrayOutputStream(512)
        var checksumMismatches = 0
            private set

        fun feed(chunk: ByteArray): List<Frame> {
            buffer.write(chunk)
            val data = buffer.toByteArray()
            val frames = ArrayList<Frame>(2)
            var i = 0
            while (true) {
                while (i + 1 < data.size && !isSof(data, i)) i++
                if (i + 4 > data.size) break
                val length = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
                if (length < 2 || length > 4096) {
                    i++
                    continue
                }
                val total = 4 + length
                if (i + total > data.size) break
                val frameBytes = data.copyOfRange(i, i + total)
                val command = frameBytes[4].toInt() and 0xFF
                val payload = frameBytes.copyOfRange(5, total - 1)
                val expected = checksum(frameBytes, total - 1)
                val ok = (frameBytes[total - 1].toInt() and 0xFF) == expected
                if (!ok) checksumMismatches++
                frames.add(Frame(command, payload, ok))
                i += total
            }
            buffer.reset()
            if (i < data.size) buffer.write(data, i, data.size - i)
            return frames
        }

        fun reset() = buffer.reset()

        private fun isSof(d: ByteArray, i: Int): Boolean {
            val hi = d[i].toInt() and 0xFF
            return (hi == SOF_DEVICE_HI || hi == SOF_APP_HI) && (d[i + 1].toInt() and 0xFF) == SOF_LO
        }
    }

    /**
     * Reassembles an AA15 photo preview: PHOTO_START carries the announced size
     * (4 bytes big endian, falling back to 2 bytes), PHOTO_TRANS carries data,
     * PHOTO_END closes it. Firmware is known to under-report the size, so the
     * buffer keeps headroom and grows instead of truncating.
     */
    class PhotoAssembler {
        private var buffer: ByteArrayOutputStream? = null
        var announcedSize: Int = 0
            private set

        fun feed(raw: ByteArray): ByteArray? {
            if (raw.isEmpty()) return null
            val chunk = unwrap(raw)
            if (chunk.isEmpty()) return null
            when (chunk[0].toInt() and 0xFF) {
                Photo.PHOTO_START -> {
                    announcedSize = when {
                        chunk.size >= 5 -> readBe(chunk, 1, 4)
                        chunk.size >= 3 -> readBe(chunk, 1, 2)
                        else -> 0
                    }
                    val headroom = if (announcedSize > 0) announcedSize + announcedSize / 4 + 4096 else 64 * 1024
                    buffer = ByteArrayOutputStream(headroom.coerceAtMost(4 * 1024 * 1024))
                }
                Photo.PHOTO_TRANS -> buffer?.write(chunk, 1, chunk.size - 1)
                Photo.PHOTO_END -> {
                    if (chunk.size > 1) buffer?.write(chunk, 1, chunk.size - 1)
                    val bytes = buffer?.toByteArray()
                    buffer = null
                    return bytes?.takeIf { it.isNotEmpty() }
                }
            }
            return null
        }

        val receivedBytes: Int get() = buffer?.size() ?: 0
        val inProgress: Boolean get() = buffer != null

        private fun unwrap(raw: ByteArray): ByteArray {
            // Some firmware wraps AA15 chunks in the 0xAC55 command envelope.
            if (raw.size >= 6 && (raw[0].toInt() and 0xFF) == SOF_DEVICE_HI && (raw[1].toInt() and 0xFF) == SOF_LO) {
                val length = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
                if (4 + length <= raw.size && length >= 2) return raw.copyOfRange(4, 4 + length - 1)
            }
            return raw
        }

        private fun readBe(b: ByteArray, off: Int, n: Int): Int {
            var v = 0
            for (k in 0 until n) v = (v shl 8) or (b[off + k].toInt() and 0xFF)
            return v
        }
    }

    // ---- Payload helpers ----

    data class WifiCredentials(val ssid: String, val password: String)

    /**
     * Parses the 0x25 APP_RECEIVE_WIFI_INFO payload. Supported layouts:
     * length-prefixed (ssidLen, ssid, pwdLen, pwd) or text separated by NUL, comma,
     * newline or ';'.
     */
    fun parseWifiInfo(payload: ByteArray): WifiCredentials? {
        if (payload.isEmpty()) return null
        val l1 = payload[0].toInt() and 0xFF
        if (l1 in 1..32 && 1 + l1 < payload.size) {
            val l2 = payload[1 + l1].toInt() and 0xFF
            if (2 + l1 + l2 == payload.size) {
                val ssid = String(payload, 1, l1, Charsets.UTF_8)
                val pwd = String(payload, 2 + l1, l2, Charsets.UTF_8)
                if (ssid.all { !it.isISOControl() }) return WifiCredentials(ssid, pwd)
            }
        }
        val text = String(payload, Charsets.UTF_8).trim('\u0000', ' ')
        val parts = text.split('\u0000', ',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 -> WifiCredentials(parts[0], parts[1])
            parts.size == 1 -> WifiCredentials(parts[0], "")
            else -> null
        }
    }

    /** Battery: first byte is the percentage (BCD encoded on some units); second byte, if any, is charging. */
    fun parseBattery(payload: ByteArray): Pair<Int, Boolean>? {
        if (payload.isEmpty()) return null
        var level = payload[0].toInt() and 0xFF
        if (level > 100) {
            val bcd = (level shr 4) * 10 + (level and 0x0F)
            if ((level shr 4) <= 9 && (level and 0x0F) <= 9 && bcd <= 100) level = bcd
        }
        val charging = payload.size > 1 && payload[1].toInt() != 0
        return level.coerceIn(0, 100) to charging
    }

    fun printable(payload: ByteArray): String =
        String(payload, Charsets.UTF_8).filter { !it.isISOControl() && it != '�' }.trim()
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
