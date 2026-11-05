package com.glassescompanion.app.speech

import android.media.AudioDeviceInfo
import com.glassescompanion.app.audio.ClassicBluetoothAudioController
import com.glassescompanion.app.audio.OpusBleMicStream
import com.glassescompanion.app.audio.PcmCapture
import com.glassescompanion.app.audio.VoiceActivityDetector
import com.glassescompanion.app.ble.BleControlManager
import com.glassescompanion.app.ble.BleEvent
import com.glassescompanion.app.ble.EyevueBleProtocol.Cmd
import com.glassescompanion.app.cloud.CloudClient
import com.glassescompanion.app.data.GlassesMicTransport
import com.glassescompanion.app.data.SettingsStore
import com.glassescompanion.app.data.SttEngine
import com.glassescompanion.app.lang.Language
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

enum class MicSource { GLASSES, PHONE }

class MicUnavailable(message: String) : Exception(message)

/**
 * One utterance from an explicit microphone. GLASSES never falls back to the
 * phone microphone: if the glasses mic cannot be opened, [MicUnavailable] is thrown.
 */
class VoiceInput(
    private val audio: ClassicBluetoothAudioController,
    private val ble: BleControlManager,
    private val recognizer: AndroidRecognizer,
    private val cloud: CloudClient,
    private val settings: SettingsStore,
) {
    private val capture = PcmCapture()

    suspend fun listen(source: MicSource, lang: Language, onPartial: (String) -> Unit = {}, onLevel: (Float) -> Unit = {}): String? {
        val s = settings.current
        if (source == MicSource.GLASSES && s.glassesMic == GlassesMicTransport.BLE_OPUS) return listenBleOpus(lang)
        val routeError = when (source) {
            MicSource.GLASSES -> audio.routeCaptureToGlassesMic()
            MicSource.PHONE -> audio.routeCaptureToPhoneMic()
        }
        if (routeError != null) throw MicUnavailable(routeError)
        try {
            val useCloud = s.sttEngine == SttEngine.CLOUD && cloud.ready
            return if (useCloud) {
                val device = if (source == MicSource.GLASSES) audio.scoInputDevice() else audio.phoneMicDevice()
                val requireType = if (source == MicSource.GLASSES) AudioDeviceInfo.TYPE_BLUETOOTH_SCO else AudioDeviceInfo.TYPE_BUILTIN_MIC
                val pcm = try {
                    capture.captureUtterance(device, requireType, onLevel = { onLevel(it.toFloat() / 100f) })
                } catch (e: PcmCapture.RouteMismatch) {
                    throw MicUnavailable(e.message ?: "Microphone route mismatch")
                } ?: return null
                cloud.transcribe(PcmCapture.wav(pcm), lang.code).takeIf { it.isNotBlank() }
            } else {
                recognizer.listenOnce(lang.speechTag, onPartial, onLevel)
            }
        } finally {
            if (source == MicSource.GLASSES) audio.releaseCaptureRoute()
        }
    }

    /** Transcribes already-captured PCM with whichever engine is available. */
    suspend fun transcribePcm(pcm: ShortArray, lang: Language): String? {
        val s = settings.current
        if (s.sttEngine == SttEngine.CLOUD && cloud.ready) {
            val cloudText = runCatching { cloud.transcribe(PcmCapture.wav(pcm), lang.code) }.getOrNull()
            if (!cloudText.isNullOrBlank()) return cloudText.trim()
        }
        return recognizer.recognizePcm(pcm, lang.speechTag) ?: recognizer.listenOnce(lang.speechTag, {}, {})
    }

    private suspend fun listenBleOpus(lang: Language): String? {
        if (!ble.isReady) throw MicUnavailable("Glasses BLE link not ready for the BLE microphone stream")
        if (!cloud.ready && android.os.Build.VERSION.SDK_INT < 33) {
            throw MicUnavailable("BLE Opus microphone needs Cloud speech-to-text or Android 13+")
        }
        val decoder = OpusBleMicStream()
        decoder.start()?.let { throw MicUnavailable(it) }
        val vad = VoiceActivityDetector()
        val pcm = ArrayList<Short>(16_000 * 8)
        try {
            if (!ble.send(Cmd.APP_START_VOICE_RECOGNITION)) throw MicUnavailable("Could not start the glasses BLE microphone (0x57)")
            withTimeoutOrNull(15_000) {
                ble.events
                    .filter { it is BleEvent.Frame && it.frame.command == Cmd.APP_RECEIVE_VOICE_DATA }
                    .first { ev ->
                        val chunk = decoder.decode((ev as BleEvent.Frame).frame.payload)
                        chunk.forEach { pcm.add(it) }
                        vad.accept(chunk, chunk.size)
                    }
            }
        } finally {
            ble.send(Cmd.APP_STOP_VOICE_RECOGNITION)
            decoder.stop()
        }
        if (pcm.isEmpty() || !vad.speechStarted) return null
        return transcribePcm(pcm.toShortArray(), lang)
    }
}
