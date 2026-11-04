package com.glassescompanion.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.glassescompanion.app.audio.ClassicBluetoothAudioController
import com.glassescompanion.app.audio.OutputTarget
import com.glassescompanion.app.cloud.CloudClient
import com.glassescompanion.app.data.SettingsStore
import com.glassescompanion.app.lang.Language
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Playback echo gate: while the app is speaking (and for a short tail), no
 * capture is started, and recognized text that matches recent output is dropped,
 * so the app never translates its own voice.
 */
class EchoGate(private val tailMs: Long = 600) {
    @Volatile private var speakingUntil = 0L
    @Volatile var speaking = false
        private set
    private val recent = ArrayDeque<String>()

    fun begin(text: String) {
        speaking = true
        synchronized(recent) {
            recent.addLast(normalize(text))
            while (recent.size > 6) recent.removeFirst()
        }
    }

    fun end() {
        speaking = false
        speakingUntil = SystemClock.elapsedRealtime() + tailMs
    }

    fun isOpen(): Boolean = !speaking && SystemClock.elapsedRealtime() >= speakingUntil

    suspend fun awaitOpen() {
        while (!isOpen()) kotlinx.coroutines.delay(50)
    }

    fun isEcho(heard: String): Boolean {
        val h = normalize(heard)
        if (h.length < 3) return false
        return synchronized(recent) { recent.any { similar(it, h) } }
    }

    companion object {
        fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(Regex("\\s+"), " ")

        fun similar(a: String, b: String): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            if (a.contains(b) || b.contains(a)) return true
            val wa = a.split(' ').toSet()
            val wb = b.split(' ').toSet()
            val inter = wa.intersect(wb).size.toDouble()
            return inter / maxOf(wa.size, wb.size) >= 0.7
        }
    }
}

/**
 * Speaks text on an explicit output device. Android TTS (or cloud TTS) renders
 * to a file, then a MediaPlayer pinned to the requested device plays it, so the
 * phone speaker vs glasses speakers choice is enforced, not left to Android.
 */
class SpeechOutput(
    private val context: Context,
    private val audio: ClassicBluetoothAudioController,
    private val cloud: CloudClient,
    private val settings: SettingsStore,
    val echoGate: EchoGate,
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = CompletableDeferred<Boolean>()
    private var player: MediaPlayer? = null

    init {
        initTts()
    }

    private fun initTts() {
        ttsReady = CompletableDeferred()
        tts = TextToSpeech(context.applicationContext) { status -> ttsReady.complete(status == TextToSpeech.SUCCESS) }
    }

    fun ttsSupports(lang: Language): Boolean {
        val t = tts ?: return false
        if (!ttsReady.isCompleted) return true
        return t.isLanguageAvailable(lang.locale) >= TextToSpeech.LANG_AVAILABLE
    }

    /** Returns null on success or an error message. */
    suspend fun speak(text: String, lang: Language, target: OutputTarget): String? {
        if (text.isBlank()) return null
        audio.checkOutput(target)?.let { return it }
        val file = File(context.cacheDir, "tts-${UUID.randomUUID()}")
        try {
            val rendered = render(text, lang, file) ?: return "No voice available for ${lang.name}. Install it in Android Text-to-speech settings or enable cloud voice."
            return play(rendered, target, text)
        } finally {
            file.delete()
            File(file.path + ".mp3").delete()
        }
    }

    private suspend fun render(text: String, lang: Language, file: File): File? {
        if (settings.current.useCloudTts && cloud.ready) {
            val mp3 = runCatching { cloud.speech(text) }.getOrNull()
            if (mp3 != null) {
                val out = File(file.path + ".mp3")
                withContext(Dispatchers.IO) { out.writeBytes(mp3) }
                return out
            }
        }
        val ok = withTimeoutOrNull(5_000) { ttsReady.await() } ?: false
        val t = tts ?: return null
        if (!ok) return null
        if (t.isLanguageAvailable(lang.locale) < TextToSpeech.LANG_AVAILABLE) {
            if (cloud.ready) {
                val mp3 = runCatching { cloud.speech(text) }.getOrNull() ?: return null
                val out = File(file.path + ".mp3")
                withContext(Dispatchers.IO) { out.writeBytes(mp3) }
                return out
            }
            return null
        }
        t.language = lang.locale
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) done.complete(false) }
        })
        if (t.synthesizeToFile(text, null, file, id) != TextToSpeech.SUCCESS) return null
        val success = withTimeoutOrNull(20_000) { done.await() } ?: false
        return if (success && file.length() > 44) file else null
    }

    private suspend fun play(file: File, target: OutputTarget, text: String): String? = withContext(Dispatchers.Main) {
        val device = audio.outputDevice(target)
        val mp = MediaPlayer()
        player = mp
        echoGate.begin(text)
        try {
            val usage = if (target == OutputTarget.GLASSES_SPEAKERS && device?.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.path)
            if (Build.VERSION.SDK_INT >= 28) {
                if (device != null) mp.setPreferredDevice(device)
            } else {
                audio.legacyForceSpeaker(target == OutputTarget.PHONE_SPEAKER)
            }
            mp.prepare()
            suspendCancellableCoroutine<Unit> { cont ->
                mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                cont.invokeOnCancellation { runCatching { mp.stop() } }
                mp.start()
            }
            null
        } catch (e: Exception) {
            "Playback failed: ${e.message}"
        } finally {
            runCatching { mp.release() }
            player = null
            audio.legacyForceSpeaker(false)
            echoGate.end()
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
