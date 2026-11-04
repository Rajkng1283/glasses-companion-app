package com.glassescompanion.app.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.glassescompanion.app.audio.PcmCapture
import com.glassescompanion.app.audio.SAMPLE_RATE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream

class RecognitionException(message: String) : Exception(message)

/** Thin coroutine wrapper over Android's built-in SpeechRecognizer. */
class AndroidRecognizer(private val context: Context) {

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Listens on the current default capture route (the caller sets SCO or the
     * phone mic beforehand). Returns null when nothing was said.
     */
    suspend fun listenOnce(languageTag: String, onPartial: (String) -> Unit = {}, onLevel: (Float) -> Unit = {}): String? {
        if (!available) throw RecognitionException("No speech recognition service on this phone. Install/enable Google speech services or switch to Cloud speech-to-text.")
        return run(buildIntent(languageTag), onPartial, onLevel)
    }

    /**
     * Recognizes already captured PCM (16 kHz mono) via EXTRA_AUDIO_SOURCE.
     * Android 13+ only; returns null if the platform cannot take an audio source.
     */
    suspend fun recognizePcm(pcm: ShortArray, languageTag: String): String? {
        if (Build.VERSION.SDK_INT < 33 || !available) return null
        val pipe = ParcelFileDescriptor.createPipe()
        val intent = buildIntent(languageTag).apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
        }
        return coroutineScope {
            launch(Dispatchers.IO) {
                runCatching {
                    FileOutputStream(pipe[1].fileDescriptor).use { out ->
                        out.write(PcmCapture.shortsToBytes(pcm, pcm.size))
                        // trailing silence lets the endpointer close the utterance
                        out.write(ByteArray(SAMPLE_RATE))
                    }
                }
                runCatching { pipe[1].close() }
            }
            try {
                run(intent, {}, {})
            } finally {
                runCatching { pipe[0].close() }
            }
        }
    }

    private fun buildIntent(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private suspend fun run(intent: Intent, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): String? {
        val result = CompletableDeferred<Result<String?>>()
        val recognizer = withContext(Dispatchers.Main) { SpeechRecognizer.createSpeechRecognizer(context) }
        try {
            withContext(Dispatchers.Main) {
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = onLevel(rmsdB)
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        result.complete(Result.success(text?.takeIf { it.isNotBlank() }))
                    }

                    override fun onError(error: Int) {
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                result.complete(Result.success(null))
                            else -> result.complete(Result.failure(RecognitionException(describe(error))))
                        }
                    }
                })
                recognizer.startListening(intent)
            }
            val r = withTimeoutOrNull(30_000) { result.await() } ?: Result.success(null)
            return r.getOrThrow()
        } finally {
            withContext(Dispatchers.Main) {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
        }
    }

    companion object {
        fun describe(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs network (or the offline language pack)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error"
            12 -> "This language is not supported by the phone recognizer; use Cloud speech-to-text"
            13 -> "Language pack not downloaded on this phone"
            else -> "Speech recognition error $code"
        }
    }
}
