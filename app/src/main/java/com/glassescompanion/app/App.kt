package com.glassescompanion.app

import android.app.Application
import com.glassescompanion.app.assistant.AssistantController
import com.glassescompanion.app.audio.ClassicBluetoothAudioController
import com.glassescompanion.app.ble.BleControlManager
import com.glassescompanion.app.ble.BleEvent
import com.glassescompanion.app.call.CallTranslationSession
import com.glassescompanion.app.cloud.CloudClient
import com.glassescompanion.app.data.SettingsStore
import com.glassescompanion.app.gesture.AppMode
import com.glassescompanion.app.gesture.ControlAction
import com.glassescompanion.app.gesture.PhysicalControlDispatcher
import com.glassescompanion.app.media.MediaLibrary
import com.glassescompanion.app.recording.AiRecordingController
import com.glassescompanion.app.reminders.ReminderStore
import com.glassescompanion.app.speech.AndroidRecognizer
import com.glassescompanion.app.speech.EchoGate
import com.glassescompanion.app.speech.SpeechOutput
import com.glassescompanion.app.speech.VoiceInput
import com.glassescompanion.app.translate.DialogueTranslationController
import com.glassescompanion.app.translate.InterpretationController
import com.glassescompanion.app.translate.Translator
import com.glassescompanion.app.wifi.GlassesWifiMediaImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppGraph(app: Application) {
    val settings = SettingsStore(app)
    val ble = BleControlManager(app, settings)
    val audio = ClassicBluetoothAudioController(app)
    val cloud = CloudClient(settings)
    val translator = Translator(cloud)
    val recognizer = AndroidRecognizer(app)
    val echoGate = EchoGate()
    val speech = SpeechOutput(app, audio, cloud, settings, echoGate)
    val voice = VoiceInput(audio, ble, recognizer, cloud, settings)
    val library = MediaLibrary(app)
    val importer = GlassesWifiMediaImporter(app, ble, library)
    val reminders = ReminderStore(app)
    val recorder = AiRecordingController(audio, ble, cloud, library, settings)
    val dialogue = DialogueTranslationController(voice, speech, translator, settings)
    val interpretation = InterpretationController(voice, speech, translator)
    val assistant = AssistantController(app, ble, audio, cloud, recognizer, voice, speech, reminders, recorder, settings)
    val call = CallTranslationSession(app, settings, voice, translator, speech)
    val controls = PhysicalControlDispatcher()

    /** Last raw control event, for the Control Guide's "test my glasses" panel. */
    private val _lastControl = MutableStateFlow("")
    val lastControl = _lastControl.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun mode(): AppMode = when {
        dialogue.state.value.running -> AppMode.DIALOGUE
        interpretation.state.value.running -> AppMode.INTERPRETATION
        assistant.activity.value.videoRecording -> AppMode.RECORDING_VIDEO
        assistant.activity.value.audioRecording || recorder.state.value.recording -> AppMode.RECORDING_AUDIO
        assistant.state.value.armed -> AppMode.ASSISTANT
        else -> AppMode.IDLE
    }

    init {
        scope.launch {
            ble.events.collect { ev ->
                if (ev !is BleEvent.Frame) return@collect
                val s = settings.current
                val decision = controls.decide(ev.frame, mode(), s.confirmedGestures, s.allowUnconfirmedGestures)
                if (decision.reason != "Not a control event") _lastControl.value = controls.signature(ev.frame)
                when (decision.action) {
                    ControlAction.PERSON_A_TURN -> dialogue.requestPersonA()
                    ControlAction.TOGGLE_INTERPRETATION_PAUSE -> interpretation.togglePause()
                    ControlAction.ASSISTANT_LISTEN -> assistant.trigger()
                    ControlAction.STOP_VIDEO -> assistant.stopVideoFromControl()
                    ControlAction.STOP_AUDIO -> assistant.handleText("stop audio recording")
                    ControlAction.VISUAL_RECOGNITION -> assistant.onDevicePhotoRecognition()
                    ControlAction.NONE -> Unit
                }
            }
        }
    }
}

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        ReminderStore.ensureChannel(this)
    }
}
