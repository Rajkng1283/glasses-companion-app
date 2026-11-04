package com.glassescompanion.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Snapshot of the audio devices Android currently exposes. Independent of the BLE link. */
data class AudioRouteState(
    val glassesMicScoAvailable: Boolean = false,
    val glassesMicName: String = "",
    val glassesSpeakerA2dp: Boolean = false,
    val glassesSpeakerSco: Boolean = false,
    val glassesSpeakerName: String = "",
    val phoneMic: Boolean = false,
    val phoneSpeaker: Boolean = false,
    val scoActive: Boolean = false,
    val lastError: String = "",
) {
    val glassesAudioConnected: Boolean get() = glassesMicScoAvailable || glassesSpeakerA2dp || glassesSpeakerSco
}

enum class OutputTarget { PHONE_SPEAKER, GLASSES_SPEAKERS }

/**
 * Classic Bluetooth (HFP/SCO + A2DP) and on-device audio router. It never
 * substitutes one device for another silently: if the requested route is not
 * present, it returns an error and the caller must surface it.
 */
@SuppressLint("MissingPermission")
class ClassicBluetoothAudioController(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _state = MutableStateFlow(AudioRouteState())
    val state: StateFlow<AudioRouteState> = _state.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = probe()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = probe()
    }

    init {
        am.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        probe()
    }

    fun probe() {
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val scoIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val a2dp = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val scoOut = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        _state.value = _state.value.copy(
            glassesMicScoAvailable = scoIn != null || scoOut != null,
            glassesMicName = (scoIn ?: scoOut)?.productName?.toString().orEmpty(),
            glassesSpeakerA2dp = a2dp != null,
            glassesSpeakerSco = scoOut != null,
            glassesSpeakerName = (a2dp ?: scoOut)?.productName?.toString().orEmpty(),
            phoneMic = inputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC },
            phoneSpeaker = outputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER },
            scoActive = isScoActive(),
        )
    }

    fun scoInputDevice(): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    fun phoneMicDevice(): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    fun outputDevice(target: OutputTarget): AudioDeviceInfo? {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return when (target) {
            OutputTarget.PHONE_SPEAKER -> outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            OutputTarget.GLASSES_SPEAKERS -> outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }
    }

    private fun isScoActive(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) am.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        else @Suppress("DEPRECATION") am.isBluetoothScoOn

    /**
     * Routes capture to the glasses HFP microphone. Returns null on success or a
     * human readable reason. Never falls back to the phone microphone.
     */
    suspend fun routeCaptureToGlassesMic(): String? {
        probe()
        if (!_state.value.glassesMicScoAvailable && Build.VERSION.SDK_INT >= 31 &&
            am.availableCommunicationDevices.none { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        ) {
            return fail("Glasses microphone (Bluetooth HFP) is not connected. Pair the glasses as an audio device in Android Bluetooth settings.")
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: return fail("No Bluetooth SCO communication device offered by Android")
            if (!am.setCommunicationDevice(dev)) return fail("Android refused to route audio to the glasses microphone")
        } else {
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = true
        }
        val ok = withTimeoutOrNull(4_000) {
            while (!isScoActive()) delay(100)
            true
        } ?: false
        probe()
        if (!ok) {
            releaseCaptureRoute()
            return fail("Glasses microphone link (SCO) did not open within 4 s")
        }
        delay(250) // SCO needs a moment before the first samples are real audio.
        return null
    }

    /** Makes sure the phone built-in microphone is the capture device (SCO off). */
    fun routeCaptureToPhoneMic(): String? {
        releaseCaptureRoute()
        probe()
        return if (_state.value.phoneMic) null else fail("Phone microphone not available")
    }

    fun releaseCaptureRoute() {
        if (Build.VERSION.SDK_INT >= 31) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
        }
        am.mode = AudioManager.MODE_NORMAL
        probe()
    }

    /** Checks that the requested output exists; returns an error string otherwise. */
    fun checkOutput(target: OutputTarget): String? {
        probe()
        return when {
            target == OutputTarget.GLASSES_SPEAKERS && outputDevice(target) == null ->
                fail("Glasses speakers (Bluetooth A2DP/HFP) are not connected; not playing on the phone instead.")
            target == OutputTarget.PHONE_SPEAKER && outputDevice(target) == null -> fail("Phone speaker not available")
            else -> null
        }
    }

    /** API 26/27 cannot pin a player to a device; this forces the loudspeaker for phone output. */
    fun legacyForceSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 28) return
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = on
    }

    fun adjustVolume(up: Boolean) {
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    fun audioManager(): AudioManager = am

    private fun fail(msg: String): String {
        _state.value = _state.value.copy(lastError = msg)
        return msg
    }
}
