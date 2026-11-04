package com.glassescompanion.app.gesture

import com.glassescompanion.app.ble.EyevueBleProtocol
import com.glassescompanion.app.ble.EyevueBleProtocol.Cmd
import com.glassescompanion.app.ble.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the app is doing; decides what a glasses button/touchpad event means. */
enum class AppMode { IDLE, DIALOGUE, INTERPRETATION, ASSISTANT, RECORDING_VIDEO, RECORDING_AUDIO }

enum class ControlAction {
    PERSON_A_TURN, TOGGLE_INTERPRETATION_PAUSE, ASSISTANT_LISTEN, STOP_VIDEO, STOP_AUDIO, VISUAL_RECOGNITION, NONE,
}

data class ControlDecision(val action: ControlAction, val reason: String, val guarded: Boolean = false)

data class ControlEventLog(val at: Long, val event: String, val decision: String)

/**
 * State-dependent dispatch of physical glasses controls. Only events whose
 * meaning is established are acted on: VOICE_COMMAND (0x06), ACTION_SYNC (0x45)
 * and DEVICE_PHOTO_RECOGNITION (0x0A). Unknown/unconfirmed gesture payloads are
 * logged and ignored unless the user confirmed them in the Control Guide or
 * allowed unconfirmed gestures; recording-ending actions are never taken from
 * an unconfirmed gesture.
 */
class PhysicalControlDispatcher {

    private val _log = MutableStateFlow<List<ControlEventLog>>(emptyList())
    val log: StateFlow<List<ControlEventLog>> = _log.asStateFlow()

    fun signature(frame: EyevueBleProtocol.Frame): String =
        if (frame.payload.isEmpty()) Cmd.name(frame.command) else "${Cmd.name(frame.command)}:${frame.payload.toHex()}"

    fun decide(
        frame: EyevueBleProtocol.Frame,
        mode: AppMode,
        confirmed: Set<String>,
        allowUnconfirmed: Boolean,
    ): ControlDecision {
        val sig = signature(frame)
        val isConfirmed = sig in confirmed || frame.command == Cmd.VOICE_COMMAND || frame.command == Cmd.DEVICE_PHOTO_RECOGNITION ||
            (frame.command == Cmd.ACTION_SYNC && (frame.payload.isEmpty() || Cmd.name(Cmd.ACTION_SYNC) in confirmed))
        val decision = when (frame.command) {
            Cmd.VOICE_COMMAND -> when (mode) {
                AppMode.DIALOGUE -> ControlDecision(ControlAction.PERSON_A_TURN, "Voice button during dialogue → Person A speaks")
                else -> ControlDecision(ControlAction.ASSISTANT_LISTEN, "Voice button → assistant listens")
            }
            Cmd.DEVICE_PHOTO_RECOGNITION -> ControlDecision(ControlAction.VISUAL_RECOGNITION, "Glasses photo recognition")
            Cmd.ACTION_SYNC -> {
                val guarded = !isConfirmed && !allowUnconfirmed
                when (mode) {
                    // ACTION_SYNC is the documented Person A trigger, so it is always honoured here.
                    AppMode.DIALOGUE -> ControlDecision(ControlAction.PERSON_A_TURN, "Tap during dialogue → Person A speaks")
                    AppMode.INTERPRETATION -> ControlDecision(if (guarded) ControlAction.NONE else ControlAction.TOGGLE_INTERPRETATION_PAUSE, "Tap → pause/resume interpretation", guarded)
                    AppMode.ASSISTANT -> ControlDecision(if (guarded) ControlAction.NONE else ControlAction.ASSISTANT_LISTEN, "Tap → assistant listens", guarded)
                    AppMode.RECORDING_VIDEO -> ControlDecision(if (isConfirmed) ControlAction.STOP_VIDEO else ControlAction.NONE, "Tap while recording video → stop video", !isConfirmed)
                    AppMode.RECORDING_AUDIO -> ControlDecision(if (isConfirmed) ControlAction.STOP_AUDIO else ControlAction.NONE, "Tap while recording audio → stop audio", !isConfirmed)
                    AppMode.IDLE -> ControlDecision(ControlAction.NONE, "Tap while idle: handled by the glasses firmware")
                }
            }
            else -> ControlDecision(ControlAction.NONE, "Not a control event")
        }
        if (frame.command in listOf(Cmd.VOICE_COMMAND, Cmd.ACTION_SYNC, Cmd.DEVICE_PHOTO_RECOGNITION)) {
            val text = decision.reason + if (decision.guarded) " — ignored (unconfirmed gesture)" else ""
            _log.value = (_log.value + ControlEventLog(System.currentTimeMillis(), sig, text)).takeLast(50)
        }
        return decision
    }

    companion object {
        /** Reference table shown in the Physical Control Guide. */
        val GUIDE: List<Triple<String, String, String>> = listOf(
            Triple("Touchpad tap (ACTION_SYNC 0x45)", "Dialogue Translation running", "Person A (wearer) speaks next"),
            Triple("Touchpad tap (ACTION_SYNC 0x45)", "Real-time Interpretation running", "Pause / resume"),
            Triple("Touchpad tap (ACTION_SYNC 0x45)", "Assistant open", "Assistant starts listening"),
            Triple("Touchpad tap (ACTION_SYNC 0x45)", "Video recording (started by app)", "Stop video (confirmed gesture only)"),
            Triple("Touchpad tap (ACTION_SYNC 0x45)", "Audio recording (started by app)", "Stop recording (confirmed gesture only)"),
            Triple("Touchpad tap (ACTION_SYNC 0x45)", "Idle", "Firmware default (photo / music); app does nothing"),
            Triple("Voice button (VOICE_COMMAND 0x06)", "Dialogue running", "Person A speaks next"),
            Triple("Voice button (VOICE_COMMAND 0x06)", "Anything else", "Assistant listens (600 ms pre-roll)"),
            Triple("Photo recognition (0x0A)", "Any", "Preview over AA15 → visual answer"),
        )
    }
}
