package com.glassescompanion.app.call

import android.content.Context
import com.glassescompanion.app.audio.OpusBleMicStream
import com.glassescompanion.app.audio.OutputTarget
import com.glassescompanion.app.audio.PcmCapture
import com.glassescompanion.app.audio.SAMPLE_RATE
import com.glassescompanion.app.audio.VoiceActivityDetector
import com.glassescompanion.app.data.SettingsStore
import com.glassescompanion.app.lang.LanguageRegistry
import com.glassescompanion.app.speech.SpeechOutput
import com.glassescompanion.app.speech.VoiceInput
import com.glassescompanion.app.translate.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class CallCaption(val mine: Boolean, val original: String, val translated: String)

data class CallState(
    val active: Boolean = false,
    val room: String = "",
    val inviteLink: String = "",
    val deepLink: String = "",
    val status: String = "No call",
    val peerPresent: Boolean = false,
    val video: Boolean = false,
    val muted: Boolean = false,
    val speakTranslations: Boolean = true,
    val myLang: String = "hi",
    val peerLang: String = "en",
    val videoSourceDisclosure: String = "",
    val captions: List<CallCaption> = emptyList(),
    val error: String? = null,
)

/**
 * Our own translated audio/video call: WebRTC media peer-to-peer, signalling
 * over a small WebSocket relay (signaling-server/ in the repository). Each side
 * transcribes its own outgoing audio (taken from the WebRTC audio module, so
 * the microphone is not opened twice), translates it into the peer's language
 * and sends captions over a DataChannel.
 */
class CallTranslationSession(
    private val context: Context,
    private val settings: SettingsStore,
    private val voice: VoiceInput,
    private val translator: Translator,
    private val output: SpeechOutput,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    private var ws: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var egl: EglBase? = null
    private var localAudio: AudioTrack? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var dataChannel: DataChannel? = null
    private var isHost = false
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private val segments = Channel<ShortArray>(Channel.BUFFERED)
    private val vad = VoiceActivityDetector(silenceMsToEnd = 700, maxUtteranceMs = 12_000)
    private val pending = ArrayList<Short>(SAMPLE_RATE * 12)

    fun eglContext(): EglBase.Context? = egl?.eglBaseContext

    fun setLanguages(mine: String, peer: String) {
        _state.value = _state.value.copy(myLang = mine, peerLang = peer)
    }

    fun toggleSpeak() {
        _state.value = _state.value.copy(speakTranslations = !_state.value.speakTranslations)
    }

    private fun base(): String? = settings.current.signalingUrl.trim().trimEnd('/').ifBlank { null }

    /** Creates a room and an invitation link. */
    fun host(video: Boolean) {
        val b = base() ?: return fail("Set the call signalling server URL in Settings → Calls (deploy signaling-server/ from the repository).")
        val room = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
        isHost = true
        val httpBase = b.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
        _state.value = _state.value.copy(
            room = room,
            inviteLink = "$httpBase/join/$room",
            deepLink = "glassescompanion://call?room=$room&server=${java.net.URLEncoder.encode(b, "UTF-8")}",
        )
        connect(b, room, video)
    }

    fun join(room: String, server: String?, video: Boolean) {
        server?.takeIf { it.isNotBlank() }?.let { s -> settings.update { it.copy(signalingUrl = s) } }
        val b = base() ?: return fail("No signalling server for this invitation.")
        isHost = false
        _state.value = _state.value.copy(room = room.uppercase())
        connect(b, room.uppercase(), video)
    }

    private fun connect(b: String, room: String, video: Boolean) {
        if (_state.value.active) return
        val camName = if (video) "Phone front camera" else "None (audio call)"
        _state.value = _state.value.copy(
            active = true, video = video, status = "Connecting…", error = null, captions = emptyList(),
            videoSourceDisclosure = "Video source: $camName. The glasses camera is not streamed live — it only sends still previews over BLE.",
        )
        runCatching { initPeer(video) }.onFailure { return fail("WebRTC init failed: ${it.message}") }
        val wsUrl = b.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws?room=$room"
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), wsListener)
        scope.launch { captionWorker() }
    }

    private fun initPeer(video: Boolean) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        val e = EglBase.create()
        egl = e
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setSamplesReadyCallback { samples -> onLocalSamples(samples.data, samples.sampleRate, samples.channelCount) }
            .createAudioDeviceModule()
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        val ice = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val config = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val peer = f.createPeerConnection(config, peerObserver) ?: throw IllegalStateException("peer connection")
        pc = peer
        val audioTrack = f.createAudioTrack("mic", f.createAudioSource(MediaConstraints()))
        localAudio = audioTrack
        peer.addTrack(audioTrack, listOf("local"))
        if (video) {
            val en = Camera2Enumerator(context)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull()
            if (name != null) {
                val cap = en.createCapturer(name, null)
                val helper = SurfaceTextureHelper.create("cam", e.eglBaseContext)
                val src = f.createVideoSource(false)
                cap.initialize(helper, context, src.capturerObserver)
                cap.startCapture(960, 540, 24)
                capturer = cap
                val vt = f.createVideoTrack("cam", src)
                localVideo = vt
                peer.addTrack(vt, listOf("local"))
                localRenderer?.let { vt.addSink(it) }
            }
        }
        if (isHost) {
            dataChannel = peer.createDataChannel("captions", DataChannel.Init()).also { observeChannel(it) }
        }
    }

    fun attachRenderers(remote: SurfaceViewRenderer?, local: SurfaceViewRenderer?) {
        remoteRenderer = remote
        localRenderer = local
        remote?.let { r -> remoteVideo?.addSink(r) }
        local?.let { l -> localVideo?.addSink(l) }
    }

    fun toggleMute() {
        val m = !_state.value.muted
        localAudio?.setEnabled(!m)
        _state.value = _state.value.copy(muted = m)
    }

    fun hangUp() {
        runCatching { ws?.send(JSONObject().put("type", "bye").toString()) }
        ws?.close(1000, "bye")
        ws = null
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        remoteRenderer?.let { r -> remoteVideo?.removeSink(r) }
        localRenderer?.let { l -> localVideo?.removeSink(l) }
        dataChannel?.close()
        dataChannel = null
        pc?.close()
        pc = null
        factory?.dispose()
        factory = null
        egl?.release()
        egl = null
        remoteVideo = null
        localVideo = null
        synchronized(pending) { pending.clear() }
        _state.value = _state.value.copy(active = false, peerPresent = false, status = "Call ended")
    }

    // ---------- captions ----------

    private fun onLocalSamples(data: ByteArray, rate: Int, channels: Int) {
        if (_state.value.muted || !_state.value.peerPresent) return
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val all = ShortArray(bb.remaining()).also { bb.get(it) }
        val mono = if (channels <= 1) all else ShortArray(all.size / channels) { all[it * channels] }
        val pcm = OpusBleMicStream.resample(mono, rate, SAMPLE_RATE)
        synchronized(pending) {
            pcm.forEach { pending.add(it) }
            if (vad.accept(pcm, pcm.size)) {
                if (vad.speechStarted) segments.trySend(pending.toShortArray())
                pending.clear()
                vad.reset()
            } else if (!vad.speechStarted && pending.size > SAMPLE_RATE / 2) {
                // keep ~0.5 s of lead-in only
                val keep = pending.takeLast(SAMPLE_RATE / 4)
                pending.clear()
                pending.addAll(keep)
            }
        }
    }

    private suspend fun captionWorker() {
        for (seg in segments) {
            if (!_state.value.active) break
            val mine = LanguageRegistry.require(_state.value.myLang)
            val peer = LanguageRegistry.require(_state.value.peerLang)
            val text = runCatching { voice.transcribePcm(seg, mine) }.onFailure { e ->
                _state.value = _state.value.copy(error = "Caption speech-to-text: ${e.message}")
            }.getOrNull()?.trim().orEmpty()
            if (text.isEmpty()) continue
            val tr = runCatching { translator.translate(text, mine, peer).text }.getOrElse { "" }
            addCaption(CallCaption(true, text, tr))
            val msg = JSONObject().put("original", text).put("translated", tr).put("from", mine.code).put("to", peer.code).toString()
            val buf = DataChannel.Buffer(ByteBuffer.wrap(msg.toByteArray()), false)
            dataChannel?.send(buf)
        }
    }

    private fun onRemoteCaption(json: JSONObject) {
        val tr = json.optString("translated")
        addCaption(CallCaption(false, json.optString("original"), tr))
        if (_state.value.speakTranslations && tr.isNotBlank()) {
            scope.launch { output.speak(tr, LanguageRegistry.require(_state.value.myLang), OutputTarget.GLASSES_SPEAKERS) }
        }
    }

    private fun addCaption(c: CallCaption) {
        _state.value = _state.value.copy(captions = (_state.value.captions + c).takeLast(80))
    }

    private fun observeChannel(ch: DataChannel) {
        ch.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                runCatching { onRemoteCaption(JSONObject(String(bytes))) }
            }
        })
    }

    // ---------- signalling ----------

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = _state.value.copy(status = if (isHost) "Waiting for the invited person…" else "Joining…")
            webSocket.send(JSONObject().put("type", "join").put("role", if (isHost) "host" else "guest").toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (m.optString("type")) {
                "peer-joined" -> {
                    _state.value = _state.value.copy(peerPresent = true, status = "Connected — setting up media")
                    if (isHost) makeOffer()
                }
                "peer-left", "bye" -> _state.value = _state.value.copy(peerPresent = false, status = "The other person left")
                "room-full" -> fail("This call already has two participants")
                "offer" -> onOffer(m.optString("sdp"))
                "answer" -> pc?.setRemoteDescription(NoopSdp, SessionDescription(SessionDescription.Type.ANSWER, m.optString("sdp")))
                "ice" -> pc?.addIceCandidate(IceCandidate(m.optString("mid"), m.optInt("index"), m.optString("candidate")))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail("Signalling connection failed: ${t.message}")
        }
    }

    private fun makeOffer() {
        val peer = pc ?: return
        peer.createOffer(object : SdpObserver by NoopSdp {
            override fun onCreateSuccess(desc: SessionDescription) {
                peer.setLocalDescription(NoopSdp, desc)
                ws?.send(JSONObject().put("type", "offer").put("sdp", desc.description).toString())
            }
        }, MediaConstraints())
    }

    private fun onOffer(sdp: String) {
        val peer = pc ?: return
        _state.value = _state.value.copy(peerPresent = true)
        peer.setRemoteDescription(object : SdpObserver by NoopSdp {
            override fun onSetSuccess() {
                peer.createAnswer(object : SdpObserver by NoopSdp {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        peer.setLocalDescription(NoopSdp, desc)
                        ws?.send(JSONObject().put("type", "answer").put("sdp", desc.description).toString())
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private val peerObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            ws?.send(JSONObject().put("type", "ice").put("mid", c.sdpMid).put("index", c.sdpMLineIndex).put("candidate", c.sdp).toString())
        }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            val label = when (s) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> "In call"
                PeerConnection.IceConnectionState.FAILED -> "Media connection failed (a TURN server may be needed on this network)"
                PeerConnection.IceConnectionState.DISCONNECTED -> "Reconnecting…"
                else -> _state.value.status
            }
            _state.value = _state.value.copy(status = label)
        }
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val track = receiver.track()
            if (track is VideoTrack) {
                remoteVideo = track
                remoteRenderer?.let { track.addSink(it) }
            }
        }
        override fun onDataChannel(ch: DataChannel) {
            dataChannel = ch
            observeChannel(ch)
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(b: Boolean) = Unit
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
        override fun onAddStream(s: MediaStream) = Unit
        override fun onRemoveStream(s: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun fail(msg: String) {
        _state.value = _state.value.copy(error = msg, status = msg)
    }

    private object NoopSdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    @Suppress("unused")
    private fun pcmToWav(pcm: ShortArray) = PcmCapture.wav(pcm)
}
