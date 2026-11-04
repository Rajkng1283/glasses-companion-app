package com.glassescompanion.app.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.KeyEvent
import com.glassescompanion.app.audio.ClassicBluetoothAudioController
import com.glassescompanion.app.audio.OutputTarget
import com.glassescompanion.app.audio.PreRollBuffer
import com.glassescompanion.app.audio.SAMPLE_RATE
import com.glassescompanion.app.audio.VoiceActivityDetector
import com.glassescompanion.app.ble.BleControlManager
import com.glassescompanion.app.ble.BleEvent
import com.glassescompanion.app.ble.EyevueBleProtocol.Cmd
import com.glassescompanion.app.cloud.CloudClient
import com.glassescompanion.app.data.SettingsStore
import com.glassescompanion.app.lang.LanguageRegistry
import com.glassescompanion.app.recording.AiRecordingController
import com.glassescompanion.app.reminders.ReminderStore
import com.glassescompanion.app.speech.AndroidRecognizer
import com.glassescompanion.app.speech.SpeechOutput
import com.glassescompanion.app.speech.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date

data class ChatTurn(val fromUser: Boolean, val text: String, val action: String? = null, val image: ByteArray? = null)

data class AssistantState(
    val armed: Boolean = false,
    val listening: Boolean = false,
    val thinking: Boolean = false,
    val preRollActive: Boolean = false,
    val micLabel: String = "",
    val level: Float = 0f,
    val turns: List<ChatTurn> = emptyList(),
    val error: String? = null,
)

/** What the glasses are doing right now, as far as the app has commanded it. */
data class DeviceActivity(
    val videoRecording: Boolean = false,
    val videoStartedAt: Long = 0,
    val audioRecording: Boolean = false,
    val audioStartedAt: Long = 0,
)

/**
 * Voice assistant (English, Hindi, Hinglish). A 600 ms pre-roll ring buffer
 * runs while armed, so the opening words before the trigger are not lost.
 * Device intents execute real actions and report the actual result.
 */
@SuppressLint("MissingPermission")
class AssistantController(
    private val context: Context,
    private val ble: BleControlManager,
    private val audio: ClassicBluetoothAudioController,
    private val cloud: CloudClient,
    private val recognizer: AndroidRecognizer,
    private val voice: VoiceInput,
    private val output: SpeechOutput,
    private val reminders: ReminderStore,
    private val recorder: AiRecordingController,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = _state.asStateFlow()
    private val _activity = MutableStateFlow(DeviceActivity())
    val activity: StateFlow<DeviceActivity> = _activity.asStateFlow()

    private val preRoll = PreRollBuffer(PRE_ROLL_MS)
    private var armJob: Job? = null
    @Volatile private var capturing = false
    @Volatile private var utterance: ArrayList<Short>? = null
    private var videoTimer: Job? = null

    // ---------------- pre-roll capture ----------------

    /** Starts the always-listening ring buffer (only while the assistant is open). */
    fun arm() {
        if (armJob?.isActive == true) return
        armJob = scope.launch(Dispatchers.IO) {
            val useGlasses = audio.state.value.glassesMicScoAvailable && audio.routeCaptureToGlassesMic() == null
            _state.value = _state.value.copy(armed = true, micLabel = if (useGlasses) "Glasses mic (HFP)" else "Phone mic (glasses mic not connected)")
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord(
                    if (useGlasses) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, (min * 4).coerceAtLeast(6400),
                )
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                _state.value = _state.value.copy(armed = false, error = "Microphone unavailable for the assistant")
                return@launch
            }
            (if (useGlasses) audio.scoInputDevice() else audio.phoneMicDevice())?.let { rec.setPreferredDevice(it) }
            val vad = VoiceActivityDetector(silenceMsToEnd = 1_000, maxUtteranceMs = 15_000)
            val frame = ShortArray(SAMPLE_RATE / 50)
            var noSpeechMs = 0
            try {
                rec.startRecording()
                _state.value = _state.value.copy(preRollActive = true)
                while (isActive) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    if (!output.echoGate.isOpen()) continue
                    val buf = utterance
                    if (capturing && buf != null) {
                        for (i in 0 until n) buf.add(frame[i])
                        _state.value = _state.value.copy(level = (VoiceActivityDetector.rms(frame, n) / 3000.0).toFloat().coerceIn(0f, 1f))
                        val end = vad.accept(frame, n)
                        if (!vad.speechStarted) noSpeechMs += 20
                        if (end || noSpeechMs > 6_000) {
                            capturing = false
                            utterance = null
                            vad.reset()
                            noSpeechMs = 0
                            val pcm = buf.toShortArray()
                            scope.launch { onUtterance(pcm) }
                        }
                    } else {
                        preRoll.write(frame, n)
                    }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                if (useGlasses) audio.releaseCaptureRoute()
                _state.value = _state.value.copy(armed = false, preRollActive = false, listening = false)
            }
        }
    }

    fun disarm() {
        armJob?.cancel()
        armJob = null
    }

    /** Trigger from the glasses voice button (0x06), a tap, or the on-screen mic. */
    fun trigger() {
        if (_state.value.listening || _state.value.thinking) return
        val canUsePcm = cloud.ready || Build.VERSION.SDK_INT >= 33
        if (armJob?.isActive == true && canUsePcm) {
            val buf = ArrayList<Short>(SAMPLE_RATE * 10)
            preRoll.snapshot().forEach { buf.add(it) } // the 600 ms before the trigger
            preRoll.clear()
            utterance = buf
            capturing = true
            _state.value = _state.value.copy(listening = true, error = null)
        } else {
            // No PCM-capable recognizer: Android's live recognizer, pre-roll not applicable.
            disarm()
            scope.launch {
                _state.value = _state.value.copy(listening = true, error = null, preRollActive = false)
                val text = runCatching { recognizer.listenOnce(langTag()) }.onFailure { e ->
                    _state.value = _state.value.copy(error = e.message)
                }.getOrNull()
                _state.value = _state.value.copy(listening = false)
                if (!text.isNullOrBlank()) handleText(text)
            }
        }
    }

    private fun langTag(): String = when (settings.current.assistantLang) {
        "en" -> "en-IN"
        "hi" -> "hi-IN"
        else -> "hi-IN" // Hindi-India recognizer also handles English words (Hinglish)
    }

    private suspend fun onUtterance(pcm: ShortArray) {
        _state.value = _state.value.copy(listening = false, thinking = true, level = 0f)
        val text = runCatching {
            val lang = LanguageRegistry.require(if (settings.current.assistantLang == "en") "en" else "hi")
            if (cloud.ready) voice.transcribePcm(pcm, if (settings.current.assistantLang == "auto") LanguageRegistry.require("en").copy(code = "") else lang)
            else recognizer.recognizePcm(pcm, langTag())
        }.onFailure { e -> _state.value = _state.value.copy(error = e.message) }.getOrNull()
        _state.value = _state.value.copy(thinking = false)
        if (!text.isNullOrBlank()) handleText(text)
    }

    // ---------------- intent execution ----------------

    fun handleText(text: String) {
        scope.launch {
            addTurn(ChatTurn(true, text))
            _state.value = _state.value.copy(thinking = true)
            val style = AssistantIntentParser.replyStyle(text)
            val intent = AssistantIntentParser.parse(text)
            val (reply, action, image) = runCatching { execute(intent, style) }.getOrElse { e ->
                Triple(say(style, "Sorry, that failed: ${e.message}", "माफ़ कीजिए, यह नहीं हो पाया: ${e.message}", "Sorry, ye nahi ho paya: ${e.message}"), null, null)
            }
            _state.value = _state.value.copy(thinking = false)
            addTurn(ChatTurn(false, reply, action, image))
            val replyLang = LanguageRegistry.require(if (style == ReplyStyle.HINDI) "hi" else "en")
            val target = if (audio.outputDevice(OutputTarget.GLASSES_SPEAKERS) != null) OutputTarget.GLASSES_SPEAKERS else OutputTarget.PHONE_SPEAKER
            output.speak(reply, replyLang, target)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun execute(intent: AssistantIntent, style: ReplyStyle): Triple<String, String?, ByteArray?> {
        fun r(en: String, hi: String, hing: String, action: String? = null) = Triple(say(style, en, hi, hing), action, null)
        val needBle = intent in listOf(AssistantIntent.TakePhoto, AssistantIntent.StartVideo, AssistantIntent.StopVideo)
        if (needBle && !ble.isReady) return r("The glasses are not connected over Bluetooth.", "चश्मा ब्लूटूथ से जुड़ा नहीं है।", "Glasses Bluetooth se connected nahi hai.")
        return when (intent) {
            AssistantIntent.TakePhoto -> {
                val ok = ble.send(Cmd.TAKE_PHOTO)
                if (ok) r("Photo taken on the glasses.", "चश्मे से फोटो ले ली गई।", "Glasses se photo le li.", "0x22 TAKE_PHOTO")
                else r("The glasses did not accept the photo command.", "चश्मे ने फोटो कमांड स्वीकार नहीं की।", "Glasses ne photo command accept nahi ki.")
            }
            AssistantIntent.StartVideo -> startVideo(style)
            AssistantIntent.StopVideo -> stopVideo(style)
            AssistantIntent.StartAudioRecording -> {
                if (_activity.value.audioRecording) return r("Audio recording is already running.", "ऑडियो रिकॉर्डिंग पहले से चल रही है।", "Audio recording pehle se chal rahi hai.")
                recorder.start()
                if (ble.isReady) recorder.setGlassesRecording(true)
                _activity.value = _activity.value.copy(audioRecording = true, audioStartedAt = SystemClock.elapsedRealtime())
                r("Audio recording started.", "ऑडियो रिकॉर्डिंग शुरू हो गई।", "Audio recording shuru ho gayi.", "RECORD_AUDIO start")
            }
            AssistantIntent.StopAudioRecording -> stopAudio(style)
            AssistantIntent.StopRecordingGeneric -> {
                val a = _activity.value
                when {
                    a.videoRecording && a.audioRecording -> if (a.videoStartedAt >= a.audioStartedAt) stopVideo(style) else stopAudio(style)
                    a.videoRecording -> stopVideo(style)
                    a.audioRecording -> stopAudio(style)
                    else -> r("Nothing is recording right now.", "अभी कोई रिकॉर्डिंग नहीं चल रही।", "Abhi koi recording nahi chal rahi.")
                }
            }
            AssistantIntent.MusicPlay -> media(KeyEvent.KEYCODE_MEDIA_PLAY).let { r("Playing music.", "संगीत चला दिया।", "Music chala diya.", "MEDIA_PLAY") }
            AssistantIntent.MusicPause -> media(KeyEvent.KEYCODE_MEDIA_PAUSE).let { r("Music paused.", "संगीत रोक दिया।", "Music rok diya.", "MEDIA_PAUSE") }
            AssistantIntent.MusicNext -> media(KeyEvent.KEYCODE_MEDIA_NEXT).let { r("Next track.", "अगला गाना।", "Agla gaana.", "MEDIA_NEXT") }
            AssistantIntent.MusicPrevious -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS).let { r("Previous track.", "पिछला गाना।", "Pichla gaana.", "MEDIA_PREVIOUS") }
            AssistantIntent.VolumeUp -> { audio.adjustVolume(true); r("Volume up.", "आवाज़ बढ़ा दी।", "Volume badha diya.", "VOLUME_UP") }
            AssistantIntent.VolumeDown -> { audio.adjustVolume(false); r("Volume down.", "आवाज़ कम कर दी।", "Volume kam kar diya.", "VOLUME_DOWN") }
            AssistantIntent.AnswerCall -> {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val ok = runCatching { tm.acceptRingingCall(); true }.getOrDefault(false)
                if (ok) r("Answering the call.", "कॉल उठा रहा हूँ।", "Call utha raha hoon.", "ACCEPT_CALL")
                else r("I could not answer: allow the Phone calls permission.", "कॉल नहीं उठा सका: फ़ोन कॉल अनुमति दें।", "Call nahi utha paya: phone call permission do.")
            }
            AssistantIntent.HangUpCall -> {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val ok = Build.VERSION.SDK_INT >= 28 && runCatching { tm.endCall() }.getOrDefault(false)
                if (ok) r("Call ended.", "कॉल काट दी।", "Call kaat di.", "END_CALL")
                else r("I could not end the call (needs Android 9+ and the Phone calls permission).", "कॉल नहीं काट सका।", "Call nahi kaat paya.")
            }
            is AssistantIntent.Reminder -> {
                val rem = reminders.add(intent.title, intent.atMs)
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(rem.atMs))
                val note = if (intent.timeUnderstood) "" else " (time not understood, set for 30 minutes from now)"
                r("Reminder set for $time$note.", "$time के लिए रिमाइंडर लगा दिया।", "$time ka reminder laga diya.", "REMINDER")
            }
            is AssistantIntent.VisualQuestion -> visual(intent.question, style)
            is AssistantIntent.Chat -> {
                if (!cloud.ready) return r(
                    "Device commands work offline. For general questions add a cloud AI key in Settings.",
                    "डिवाइस कमांड ऑफ़लाइन चलती हैं। सामान्य सवालों के लिए Settings में cloud AI key डालें।",
                    "Device commands offline chalti hain. General sawalon ke liye Settings mein cloud AI key daalo.",
                )
                val history = _state.value.turns.takeLast(10).map { (if (it.fromUser) "user" else "assistant") to it.text }
                val answer = cloud.chatHistory(systemPrompt(style), history)
                Triple(answer, null, null)
            }
        }
    }

    private fun systemPrompt(style: ReplyStyle) = "You are the voice assistant of smart glasses. Answer briefly (1-3 sentences), spoken style. " +
        when (style) {
            ReplyStyle.HINDI -> "Reply in Hindi (Devanagari)."
            ReplyStyle.HINGLISH -> "Reply in natural Hinglish (Hindi in Latin script mixed with English)."
            ReplyStyle.ENGLISH -> "Reply in English."
        }

    private suspend fun startVideo(style: ReplyStyle): Triple<String, String?, ByteArray?> {
        if (_activity.value.videoRecording) return Triple(say(style, "Video is already recording.", "वीडियो पहले से रिकॉर्ड हो रहा है।", "Video pehle se record ho raha hai."), null, null)
        if (!ble.send(Cmd.RECORD_VIDEO)) return Triple(say(style, "The glasses did not accept the video command.", "चश्मे ने वीडियो कमांड नहीं ली।", "Glasses ne video command nahi li."), null, null)
        _activity.value = _activity.value.copy(videoRecording = true, videoStartedAt = SystemClock.elapsedRealtime())
        val minutes = settings.current.videoDurationMin
        videoTimer?.cancel()
        videoTimer = scope.launch {
            delay(minutes * 60_000L)
            if (_activity.value.videoRecording) stopVideo(style)
        }
        return Triple(say(style, "Video recording started for up to $minutes min.", "वीडियो रिकॉर्डिंग शुरू ($minutes मिनट तक)।", "Video recording shuru ($minutes min tak)."), "0x23 RECORD_VIDEO", null)
    }

    private suspend fun stopVideo(style: ReplyStyle): Triple<String, String?, ByteArray?> {
        videoTimer?.cancel()
        val ok = ble.send(Cmd.STOP_VIDEO)
        if (ok) _activity.value = _activity.value.copy(videoRecording = false)
        return Triple(
            if (ok) say(style, "Video recording stopped.", "वीडियो रिकॉर्डिंग रोक दी।", "Video recording rok di.")
            else say(style, "Could not stop the video on the glasses.", "चश्मे पर वीडियो नहीं रुका।", "Glasses par video nahi ruka."),
            if (ok) "0x24 STOP_VIDEO" else null, null,
        )
    }

    private suspend fun stopAudio(style: ReplyStyle): Triple<String, String?, ByteArray?> {
        if (!_activity.value.audioRecording && !recorder.state.value.recording) {
            return Triple(say(style, "Audio is not recording.", "ऑडियो रिकॉर्ड नहीं हो रहा।", "Audio record nahi ho raha."), null, null)
        }
        recorder.stop()
        if (ble.isReady && recorder.state.value.glassesRecording) recorder.setGlassesRecording(false)
        _activity.value = _activity.value.copy(audioRecording = false)
        return Triple(say(style, "Audio recording stopped and saved to the album.", "ऑडियो रिकॉर्डिंग रोककर एल्बम में सेव कर दी।", "Audio recording rok ke album mein save kar di."), "RECORD_AUDIO stop", null)
    }

    /** Called by the physical control dispatcher so video state also tracks glasses-initiated toggles. */
    fun noteVideoState(recording: Boolean) {
        _activity.value = _activity.value.copy(videoRecording = recording, videoStartedAt = if (recording) SystemClock.elapsedRealtime() else _activity.value.videoStartedAt)
    }

    fun stopVideoFromControl() = scope.launch { stopVideo(ReplyStyle.ENGLISH) }

    private suspend fun visual(question: String, style: ReplyStyle): Triple<String, String?, ByteArray?> {
        if (!ble.isReady) return Triple(say(style, "Connect the glasses to look at something.", "देखने के लिए चश्मा जोड़ें।", "Dekhne ke liye glasses connect karo."), null, null)
        val photo = capturePreview() ?: return Triple(say(style, "The glasses did not send a photo preview.", "चश्मे से फोटो नहीं आई।", "Glasses se photo nahi aayi."), null, null)
        if (!cloud.ready) return Triple(say(style, "Photo captured. Visual answers need the cloud AI key.", "फोटो ली गई। जवाब के लिए cloud AI key चाहिए।", "Photo le li. Jawab ke liye cloud AI key chahiye."), "0x22 TAKE_PHOTO", photo)
        val answer = cloud.vision(systemPrompt(style) + " The image is what the wearer sees. Question: $question", photo)
        return Triple(answer, "0x22 TAKE_PHOTO + vision", photo)
    }

    /** Takes a photo and waits for the AA15 preview. */
    suspend fun capturePreview(): ByteArray? {
        val waiter = scope.async { withTimeoutOrNull(20_000) { ble.events.filter { it is BleEvent.Photo }.first() as BleEvent.Photo } }
        delay(20)
        if (!ble.send(Cmd.TAKE_PHOTO)) {
            waiter.cancel()
            return null
        }
        return waiter.await()?.jpeg
    }

    /** Glasses-initiated recognition (0x0A): the preview arrives on AA15 by itself. */
    fun onDevicePhotoRecognition() {
        scope.launch {
            val ev = withTimeoutOrNull(20_000) { ble.events.filter { it is BleEvent.Photo }.first() as BleEvent.Photo } ?: return@launch
            val style = if (settings.current.assistantLang == "hi") ReplyStyle.HINDI else ReplyStyle.ENGLISH
            addTurn(ChatTurn(true, "[Glasses photo]", "0x0A DEVICE_PHOTO_RECOGNITION", ev.jpeg))
            if (!cloud.ready) {
                addTurn(ChatTurn(false, "Photo received. Add a cloud AI key to describe it."))
                return@launch
            }
            val answer = runCatching { cloud.vision(systemPrompt(style) + " Describe what the wearer is looking at.", ev.jpeg) }.getOrElse { "Vision failed: ${it.message}" }
            addTurn(ChatTurn(false, answer))
            output.speak(answer, LanguageRegistry.require(if (style == ReplyStyle.HINDI) "hi" else "en"),
                if (audio.outputDevice(OutputTarget.GLASSES_SPEAKERS) != null) OutputTarget.GLASSES_SPEAKERS else OutputTarget.PHONE_SPEAKER)
        }
    }

    private fun media(code: Int) {
        val am = audio.audioManager()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun say(style: ReplyStyle, en: String, hi: String, hinglish: String) = when (style) {
        ReplyStyle.ENGLISH -> en
        ReplyStyle.HINDI -> hi
        ReplyStyle.HINGLISH -> hinglish
    }

    private fun addTurn(t: ChatTurn) {
        _state.value = _state.value.copy(turns = (_state.value.turns + t).takeLast(60))
    }

    fun clear() {
        _state.value = _state.value.copy(turns = emptyList(), error = null)
    }

    companion object {
        const val PRE_ROLL_MS = 600
    }
}
