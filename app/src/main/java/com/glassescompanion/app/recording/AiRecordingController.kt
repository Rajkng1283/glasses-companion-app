package com.glassescompanion.app.recording

import com.glassescompanion.app.audio.ClassicBluetoothAudioController
import com.glassescompanion.app.audio.PcmCapture
import com.glassescompanion.app.audio.SAMPLE_RATE
import com.glassescompanion.app.ble.BleControlManager
import com.glassescompanion.app.ble.EyevueBleProtocol.Cmd
import com.glassescompanion.app.cloud.CloudClient
import com.glassescompanion.app.data.SettingsStore
import com.glassescompanion.app.media.MediaItem
import com.glassescompanion.app.media.MediaLibrary
import com.glassescompanion.app.media.MediaType
import com.glassescompanion.app.speech.MicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingState(
    val recording: Boolean = false,
    val source: MicSource = MicSource.PHONE,
    val elapsedMs: Long = 0,
    val lastFile: String = "",
    val transcript: String = "",
    val summary: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val glassesRecording: Boolean = false,
)

/**
 * AI recording. The usage counter is the real measured length of audio this
 * phone captured (persisted); there is no invented minute allowance.
 */
class AiRecordingController(
    private val audio: ClassicBluetoothAudioController,
    private val ble: BleControlManager,
    private val cloud: CloudClient,
    private val library: MediaLibrary,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var stopFlag = false

    fun setSource(s: MicSource) {
        if (!_state.value.recording) _state.value = _state.value.copy(source = s)
    }

    fun start() {
        if (job?.isActive == true) return
        stopFlag = false
        val source = _state.value.source
        _state.value = _state.value.copy(recording = true, elapsedMs = 0, error = null, transcript = "", summary = "")
        job = scope.launch {
            val routeErr = if (source == MicSource.GLASSES) audio.routeCaptureToGlassesMic() else audio.routeCaptureToPhoneMic()
            if (routeErr != null) {
                _state.value = _state.value.copy(recording = false, error = routeErr)
                return@launch
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = library.newFile("recording_$stamp.wav")
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(0)
            raf.write(ByteArray(44))
            val ticker = launch {
                val t0 = System.currentTimeMillis()
                while (isActive) {
                    _state.value = _state.value.copy(elapsedMs = System.currentTimeMillis() - t0)
                    delay(250)
                }
            }
            val measured = try {
                PcmCapture().captureContinuous(
                    device = if (source == MicSource.GLASSES) audio.scoInputDevice() else audio.phoneMicDevice(),
                    onChunk = { s, n -> raf.write(PcmCapture.shortsToBytes(s, n)) },
                    shouldStop = { stopFlag },
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
                0L
            } finally {
                ticker.cancel()
                if (source == MicSource.GLASSES) audio.releaseCaptureRoute()
            }
            val dataLen = raf.length() - 44
            raf.seek(0)
            raf.write(PcmCapture.wav(ShortArray(0)).copyOfRange(0, 44).also { header -> patchHeader(header, dataLen) })
            raf.close()
            settings.update { it.copy(measuredRecordingMs = it.measuredRecordingMs + measured) }
            library.add(
                MediaItem(
                    id = "rec_$stamp", fileName = file.name, localPath = file.path, type = MediaType.AUDIO,
                    size = file.length(), sha256 = "", importedAt = System.currentTimeMillis(),
                    source = if (source == MicSource.GLASSES) "glasses mic" else "phone mic", durationMs = measured,
                ),
            )
            _state.value = _state.value.copy(recording = false, elapsedMs = measured, lastFile = file.path)
        }
    }

    fun stop() {
        stopFlag = true
    }

    /** Starts/stops recording on the glasses' own storage (0x34), imported later over Wi-Fi. */
    suspend fun setGlassesRecording(on: Boolean): Boolean {
        val ok = ble.send(Cmd.RECORD_AUDIO, byteArrayOf(if (on) 1 else 0))
        if (ok) _state.value = _state.value.copy(glassesRecording = on)
        return ok
    }

    fun transcribeAndSummarize(path: String = _state.value.lastFile) {
        if (path.isBlank()) return
        if (!cloud.ready) {
            _state.value = _state.value.copy(error = "Transcription and summary need the cloud AI key (Settings → Cloud AI)")
            return
        }
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching {
                val bytes = File(path).readBytes()
                val pcm = PcmCapture.bytesToShorts(bytes.copyOfRange(44.coerceAtMost(bytes.size), bytes.size))
                val chunk = SAMPLE_RATE * 60 * 10
                val text = StringBuilder()
                var i = 0
                while (i < pcm.size) {
                    val part = pcm.copyOfRange(i, minOf(pcm.size, i + chunk))
                    text.append(cloud.transcribe(PcmCapture.wav(part), null)).append(' ')
                    i += chunk
                }
                val transcript = text.toString().trim()
                _state.value = _state.value.copy(transcript = transcript)
                val summary = if (transcript.isBlank()) "" else cloud.chat(
                    "Summarize this recording as short bullet points with action items. Answer in the transcript's language.",
                    transcript,
                )
                _state.value = _state.value.copy(summary = summary)
            }.onFailure { e -> _state.value = _state.value.copy(error = e.message) }
            _state.value = _state.value.copy(busy = false)
        }
    }

    private fun patchHeader(h: ByteArray, dataLen: Long) {
        fun putInt(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte(); h[off + 1] = ((v shr 8) and 0xFF).toByte()
            h[off + 2] = ((v shr 16) and 0xFF).toByte(); h[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        putInt(4, (36 + dataLen).toInt())
        putInt(40, dataLen.toInt())
    }
}
