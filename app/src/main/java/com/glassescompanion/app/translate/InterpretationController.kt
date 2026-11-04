package com.glassescompanion.app.translate

import com.glassescompanion.app.audio.OutputTarget
import com.glassescompanion.app.lang.LanguageRegistry
import com.glassescompanion.app.speech.MicSource
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

data class InterpretationLine(val original: String, val translated: String, val engine: String)

data class InterpretationState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val from: String = "en",
    val to: String = "hi",
    val mic: MicSource = MicSource.PHONE,
    val speakOut: Boolean = true,
    val output: OutputTarget = OutputTarget.GLASSES_SPEAKERS,
    val partial: String = "",
    val lines: List<InterpretationLine> = emptyList(),
    val error: String? = null,
)

/** Real-time one-way interpretation (e.g. a lecture or guide), continuous until stopped. */
class InterpretationController(
    private val input: VoiceInput,
    private val output: SpeechOutput,
    private val translator: Translator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(InterpretationState())
    val state: StateFlow<InterpretationState> = _state.asStateFlow()
    private var job: Job? = null

    fun configure(block: (InterpretationState) -> InterpretationState) {
        if (_state.value.running) return
        _state.value = block(_state.value)
    }

    fun togglePause() {
        _state.value = _state.value.copy(paused = !_state.value.paused)
    }

    fun start() {
        if (job?.isActive == true) return
        val from = LanguageRegistry.require(_state.value.from)
        val to = LanguageRegistry.require(_state.value.to)
        _state.value = _state.value.copy(running = true, paused = false, error = null)
        job = scope.launch {
            translator.prepare(from, to)?.let { msg -> _state.value = _state.value.copy(error = msg) }
            while (true) {
                if (_state.value.paused) {
                    delay(200)
                    continue
                }
                output.echoGate.awaitOpen()
                val st = _state.value
                val heard = try {
                    input.listen(st.mic, from, onPartial = { p -> _state.value = _state.value.copy(partial = p) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = _state.value.copy(error = e.message)
                    delay(1_500)
                    null
                }
                val text = heard?.trim().orEmpty()
                if (text.isEmpty() || output.echoGate.isEcho(text)) continue
                val result = runCatching { translator.translate(text, from, to) }
                result.onSuccess { r ->
                    _state.value = _state.value.copy(partial = "", error = null, lines = (_state.value.lines + InterpretationLine(text, r.text, r.engine)).takeLast(200))
                    if (st.speakOut) output.speak(r.text, to, st.output)?.let { err -> _state.value = _state.value.copy(error = err) }
                }.onFailure { e -> _state.value = _state.value.copy(error = e.message) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        output.stop()
        _state.value = _state.value.copy(running = false, partial = "")
    }
}
