package com.glassescompanion.app.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000

/** Fixed-size ring of the most recent PCM, used as a pre-roll so opening words are kept. */
class PreRollBuffer(durationMs: Int) {
    private val capacity = SAMPLE_RATE * durationMs / 1000
    private val ring = ShortArray(capacity)
    private var pos = 0
    private var filled = 0

    @Synchronized
    fun write(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            ring[pos] = samples[i]
            pos = (pos + 1) % capacity
        }
        filled = (filled + count).coerceAtMost(capacity)
    }

    @Synchronized
    fun snapshot(): ShortArray {
        val out = ShortArray(filled)
        val start = (pos - filled + capacity) % capacity
        for (i in 0 until filled) out[i] = ring[(start + i) % capacity]
        return out
    }

    @Synchronized
    fun clear() {
        pos = 0
        filled = 0
    }
}

/** Energy based end-of-utterance detector (pure Kotlin, unit tested). */
class VoiceActivityDetector(
    private val startThreshold: Double = 900.0,
    private val silenceMsToEnd: Int = 900,
    private val maxUtteranceMs: Int = 20_000,
) {
    var speechStarted = false
        private set
    private var silenceMs = 0
    private var totalMs = 0

    /** Returns true when the utterance should be closed. */
    fun accept(frame: ShortArray, count: Int): Boolean {
        val ms = count * 1000 / SAMPLE_RATE
        totalMs += ms
        val loud = rms(frame, count) >= startThreshold
        if (loud) {
            speechStarted = true
            silenceMs = 0
        } else if (speechStarted) {
            silenceMs += ms
        }
        return (speechStarted && silenceMs >= silenceMsToEnd) || totalMs >= maxUtteranceMs
    }

    fun reset() {
        speechStarted = false
        silenceMs = 0
        totalMs = 0
    }

    companion object {
        fun rms(frame: ShortArray, count: Int): Double {
            if (count == 0) return 0.0
            var sum = 0.0
            for (i in 0 until count) sum += frame[i].toDouble() * frame[i]
            return sqrt(sum / count)
        }
    }
}

/**
 * AudioRecord capture pinned to a specific input device. After starting, the
 * actually routed device is verified so a glasses-mic request can never be
 * silently served by the phone microphone.
 */
class PcmCapture {
    class RouteMismatch(msg: String) : Exception(msg)

    @SuppressLint("MissingPermission")
    suspend fun captureUtterance(
        device: AudioDeviceInfo?,
        requireType: Int?,
        preRoll: ShortArray = ShortArray(0),
        vad: VoiceActivityDetector = VoiceActivityDetector(),
        noSpeechTimeoutMs: Int = 8_000,
        onLevel: (Double) -> Unit = {},
        shouldStop: () -> Boolean = { false },
    ): ShortArray? = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val source = if (requireType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val rec = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, (min * 4).coerceAtLeast(6400))
        try {
            if (device != null) rec.setPreferredDevice(device)
            rec.startRecording()
            if (requireType != null) {
                Thread.sleep(150)
                val routed = rec.routedDevice
                if (routed != null && routed.type != requireType) {
                    throw RouteMismatch("Capture was routed to ${routed.productName} (type ${routed.type}) instead of the requested microphone")
                }
            }
            val out = ByteArrayOutputStream()
            if (preRoll.isNotEmpty()) out.write(shortsToBytes(preRoll, preRoll.size))
            val frame = ShortArray(SAMPLE_RATE / 50) // 20 ms
            var waitedMs = 0
            vad.reset()
            while (coroutineContext.isActive && !shouldStop()) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                out.write(shortsToBytes(frame, n))
                onLevel(VoiceActivityDetector.rms(frame, n))
                if (vad.accept(frame, n)) break
                if (!vad.speechStarted) {
                    waitedMs += n * 1000 / SAMPLE_RATE
                    if (waitedMs >= noSpeechTimeoutMs) return@withContext null
                }
            }
            if (!vad.speechStarted) null else bytesToShorts(out.toByteArray())
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** Continuous capture for recordings; returns the total captured PCM length in ms. */
    @SuppressLint("MissingPermission")
    suspend fun captureContinuous(
        device: AudioDeviceInfo?,
        onChunk: (ShortArray, Int) -> Unit,
        shouldStop: () -> Boolean,
    ): Long = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, (min * 4).coerceAtLeast(6400))
        var samples = 0L
        try {
            if (device != null) rec.setPreferredDevice(device)
            rec.startRecording()
            val frame = ShortArray(SAMPLE_RATE / 10)
            while (coroutineContext.isActive && !shouldStop()) {
                val n = rec.read(frame, 0, frame.size)
                if (n > 0) {
                    samples += n
                    onChunk(frame, n)
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        samples * 1000 / SAMPLE_RATE
    }

    companion object {
        fun shortsToBytes(s: ShortArray, n: Int): ByteArray {
            val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) bb.putShort(s[i])
            return bb.array()
        }

        fun bytesToShorts(b: ByteArray): ShortArray {
            val out = ShortArray(b.size / 2)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
            return out
        }

        fun wav(pcm: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val data = shortsToBytes(pcm, pcm.size)
            val bb = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray()); bb.putInt(36 + data.size); bb.put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
            bb.putInt(sampleRate); bb.putInt(sampleRate * 2); bb.putShort(2); bb.putShort(16)
            bb.put("data".toByteArray()); bb.putInt(data.size); bb.put(data)
            return bb.array()
        }
    }
}
