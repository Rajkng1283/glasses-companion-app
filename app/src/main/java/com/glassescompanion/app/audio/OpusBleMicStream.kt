package com.glassescompanion.app.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the glasses' BLE microphone stream (Opus packets carried in
 * APP_RECEIVE_VOICE_DATA 0x46 frames on AA14) into 16 kHz mono PCM.
 * Packet framing is not documented: payloads whose size is a multiple of the
 * common 40-byte CBR frame are split, otherwise a payload is one packet.
 * This path is marked experimental in Settings.
 */
class OpusBleMicStream(private val inputRate: Int = 16_000) {
    private var codec: MediaCodec? = null
    private var outRate = 48_000
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L

    fun start(): String? = runCatching {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, inputRate, 1)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
        format.setByteBuffer("csd-1", longLe(0))
        format.setByteBuffer("csd-2", longLe(80_000_000))
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        c.configure(format, null, null, 0)
        c.start()
        codec = c
        null
    }.getOrElse { "Opus decoder unavailable: ${it.message}" }

    fun stop() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    /** Feeds one 0x46 payload and returns the decoded 16 kHz PCM (possibly empty). */
    fun decode(payload: ByteArray): ShortArray {
        val c = codec ?: return ShortArray(0)
        val packets = if (payload.size > 40 && payload.size % 40 == 0) payload.toList().chunked(40).map { it.toByteArray() } else listOf(payload)
        val pcm = ArrayList<Short>(960)
        for (p in packets) {
            val inIndex = c.dequeueInputBuffer(5_000)
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex) ?: continue
                buf.clear()
                buf.put(p)
                c.queueInputBuffer(inIndex, 0, p.size, pts, 0)
                pts += 20_000
            }
            while (true) {
                val outIndex = c.dequeueOutputBuffer(info, 2_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outRate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    continue
                }
                if (outIndex < 0) break
                val out = c.getOutputBuffer(outIndex)
                if (out != null && info.size > 0) {
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val sb = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val tmp = ShortArray(sb.remaining())
                    sb.get(tmp)
                    for (s in resample(tmp, outRate, SAMPLE_RATE)) pcm.add(s)
                }
                c.releaseOutputBuffer(outIndex, false)
            }
        }
        return pcm.toShortArray()
    }

    private fun opusHead(): ByteArray {
        val bb = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("OpusHead".toByteArray())
        bb.put(1) // version
        bb.put(1) // channels
        bb.putShort(0) // pre-skip
        bb.putInt(inputRate)
        bb.putShort(0) // gain
        bb.put(0) // mapping family
        return bb.array()
    }

    private fun longLe(v: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(v).also { it.flip() }

    companion object {
        fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
            if (from == to || input.isEmpty()) return input
            val outLen = (input.size.toLong() * to / from).toInt()
            val out = ShortArray(outLen)
            val ratio = from.toDouble() / to
            for (i in 0 until outLen) {
                val src = i * ratio
                val i0 = src.toInt().coerceAtMost(input.size - 1)
                val i1 = (i0 + 1).coerceAtMost(input.size - 1)
                val f = src - i0
                out[i] = (input[i0] * (1 - f) + input[i1] * f).toInt().toShort()
            }
            return out
        }
    }
}
