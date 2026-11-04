package com.glassescompanion.app.translate

import com.glassescompanion.app.audio.OutputTarget
import com.glassescompanion.app.data.SettingsStore
import com.glassescompanion.app.data.TurnPolicy
import com.glassescompanion.app.lang.Language
import com.glassescompanion.app.lang.LanguageRegistry
import com.glassescompanion.app.speech.MicSource
import com.glassescompanion.app.speech.MicUnavailable
import com.glassescompanion.app.speech.SpeechOutput
import com.glassescompanion.app.speech.VoiceInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Speaker { A, B }

data class DialogueLine(
    val speaker: Speaker,
    val original: String,
    val translated: String,
    val engine: String,
    val at: Long = System.currentTimeMillis(),
    val error: String? = null,
)

enum class TurnPhase { IDLE, LISTENING, TRANSLATING, SPEAKING }

data class DialogueState(
    val running: Boolean = false,
    val activeSpeaker: Speaker? = null,
    val phase: TurnPhase = TurnPhase.IDLE,
    val partial: String = "",
    val lines: List<DialogueLine> = emptyList(),
    val status: String = "Press Start once; the session then runs continuously.",
    val error: String? = null,
    val aLang: String = "hi",
    val bLang: String = "en",
)

/**
 * Pure turn scheduling (unit tested): half-duplex, one speaker at a time.
 * A's turn is requested by the glasses tap (ACTION_SYNC 0x45) and pre-empts B's
 * idle listening; in ALTERNATE mode turns switch automatically after each line.
 */
class TurnController(var policy: TurnPolicy) {
    private var pendingA = false

    fun requestA() { pendingA = true }

    fun next(previous: Speaker?, previousHadSpeech: Boolean): Speaker {
        if (pendingA) {
            pendingA = false
            return Speaker.A
        }
        return when (policy) {
            TurnPolicy.GLASSES_TAP -> Speaker.B
            TurnPolicy.ALTERNATE -> when (previous) {
                null -> Speaker.A
                Speaker.A -> if (previousHadSpeech) Speaker.B else Speaker.A
                Speaker.B -> if (previousHadSpeech) Speaker.A else Speaker.B
            }
        }
    }

    val hasPendingA: Boolean get() = pendingA

    companion object {
        /** A = glasses wearer: glasses mic in, phone speaker out. B = other person: phone mic in, glasses speakers out. */
        fun micFor(s: Speaker) = if (s == Speaker.A) MicSource.GLASSES else MicSource.PHONE
        fun outputFor(s: Speaker) = if (s == Speaker.A) OutputTarget.PHONE_SPEAKER else OutputTarget.GLASSES_SPEAKERS
    }
}

class DialogueTranslationController(
    private val input: VoiceInput,
    private val output: SpeechOutput,
    private val translator: Translator,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(DialogueState(aLang = settings.current.personALang, bLang = settings.current.personBLang))
    val state: StateFlow<DialogueState> = _state.asStateFlow()
    private val turns = TurnController(settings.current.turnPolicy)
    private var loop: Job? = null
    private var listenJob: Job? = null

    fun setLanguages(a: String, b: String) {
        settings.update { it.copy(personALang = a, personBLang = b) }
        _state.value = _state.value.copy(aLang = a, bLang = b)
    }

    fun swapLanguages() = setLanguages(_state.value.bLang, _state.value.aLang)

    fun start() {
        if (loop?.isActive == true) return
        turns.policy = settings.current.turnPolicy
        val a = LanguageRegistry.require(_state.value.aLang)
        val b = LanguageRegistry.require(_state.value.bLang)
        if (!translator.canTranslate(a, b)) {
            _state.value = _state.value.copy(error = "${a.name} ↔ ${b.name} needs the cloud translator. Add a key in Settings → Cloud AI.")
            return
        }
        _state.value = _state.value.copy(running = true, error = null, status = "Preparing languages…")
        loop = scope.launch {
            translator.prepare(a, b)?.let { _state.value = _state.value.copy(error = it) }
            var previous: Speaker? = null
            var hadSpeech = false
            while (true) {
                output.echoGate.awaitOpen()
                val speaker = turns.next(previous, hadSpeech)
                hadSpeech = runTurn(speaker, a, b)
                previous = speaker
                if (!hadSpeech) delay(150)
            }
        }
    }

    /** Glasses tap / button (ACTION_SYNC) or on-screen button: A speaks next. */
    fun requestPersonA() {
        if (!_state.value.running) return
        turns.requestA()
        // Pre-empt B's idle listening if B has not produced anything yet.
        val st = _state.value
        if (st.activeSpeaker == Speaker.B && st.phase == TurnPhase.LISTENING && st.partial.isBlank()) listenJob?.cancel()
        _state.value = _state.value.copy(status = "Person A (glasses) speaks next")
    }

    fun stop() {
        loop?.cancel()
        loop = null
        output.stop()
        _state.value = _state.value.copy(running = false, activeSpeaker = null, phase = TurnPhase.IDLE, partial = "", status = "Session stopped")
    }

    fun clear() {
        _state.value = _state.value.copy(lines = emptyList())
    }

    private suspend fun runTurn(speaker: Speaker, a: Language, b: Language): Boolean {
        val from = if (speaker == Speaker.A) a else b
        val to = if (speaker == Speaker.A) b else a
        val who = if (speaker == Speaker.A) "Person A · glasses mic" else "Person B · phone mic"
        mutate { it.copy(activeSpeaker = speaker, phase = TurnPhase.LISTENING, partial = "", status = "Listening: $who (${from.name})") }
        var heard: String? = null
        val job = scope.launch {
            heard = try {
                input.listen(TurnController.micFor(speaker), from, onPartial = { p -> mutate { it.copy(partial = p) } })
            } catch (e: CancellationException) {
                throw e
            } catch (e: MicUnavailable) {
                mutate { it.copy(error = e.message) }
                null
            } catch (e: Exception) {
                mutate { it.copy(error = e.message ?: e.toString()) }
                null
            }
        }
        listenJob = job
        job.join()
        listenJob = null
        val text = heard?.trim().orEmpty()
        if (text.isEmpty()) {
            if (speaker == Speaker.A && _state.value.error != null) delay(1_500)
            return false
        }
        if (output.echoGate.isEcho(text)) {
            mutate { it.copy(status = "Ignored the app's own voice", partial = "") }
            return false
        }
        mutate { it.copy(phase = TurnPhase.TRANSLATING, partial = text, status = "Translating ${from.name} → ${to.name}") }
        val result = runCatching { translator.translate(text, from, to) }
        val translated = result.getOrNull()
        if (translated == null) {
            val err = result.exceptionOrNull()?.message ?: "Translation failed"
            mutate { it.copy(lines = it.lines + DialogueLine(speaker, text, "", "", error = err), error = err, partial = "") }
            return true
        }
        val target = TurnController.outputFor(speaker)
        set {
            it.copy(
                phase = TurnPhase.SPEAKING,
                partial = "",
                error = null,
                lines = it.lines + DialogueLine(speaker, text, translated.text, translated.engine),
                status = if (target == OutputTarget.PHONE_SPEAKER) "Speaking ${to.name} on the phone speaker" else "Speaking ${to.name} on the glasses speakers",
            )
        }
        output.speak(translated.text, to, target)?.let { err -> mutate { it.copy(error = err) } }
        return true
    }

    private fun mutate(block: (DialogueState) -> DialogueState) {
        _state.value = block(_state.value)
    }
}
