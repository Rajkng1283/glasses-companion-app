package com.glassescompanion.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SttEngine { ANDROID, CLOUD }
enum class GlassesMicTransport { HFP_SCO, BLE_OPUS }
enum class TurnPolicy { GLASSES_TAP, ALTERNATE }

data class AppSettings(
    val cloudBaseUrl: String = "https://api.openai.com/v1",
    val cloudApiKey: String = "",
    val chatModel: String = "gpt-4o-mini",
    val sttModel: String = "whisper-1",
    val ttsModel: String = "tts-1",
    val ttsVoice: String = "alloy",
    val useCloudTts: Boolean = false,
    val sttEngine: SttEngine = SttEngine.ANDROID,
    val glassesMic: GlassesMicTransport = GlassesMicTransport.HFP_SCO,
    val turnPolicy: TurnPolicy = TurnPolicy.GLASSES_TAP,
    val personALang: String = "hi",
    val personBLang: String = "en",
    val assistantLang: String = "auto",
    val videoDurationMin: Int = 1,
    val signalingUrl: String = "",
    val lastDeviceAddress: String = "",
    val lastDeviceName: String = "",
    val allowUnconfirmedGestures: Boolean = false,
    val confirmedGestures: Set<String> = emptySet(),
    val measuredRecordingMs: Long = 0,
) {
    val cloudReady: Boolean get() = cloudApiKey.isNotBlank() && cloudBaseUrl.startsWith("http")
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            cloudBaseUrl = prefs.getString("cloudBaseUrl", d.cloudBaseUrl) ?: d.cloudBaseUrl,
            cloudApiKey = prefs.getString("cloudApiKey", "") ?: "",
            chatModel = prefs.getString("chatModel", d.chatModel) ?: d.chatModel,
            sttModel = prefs.getString("sttModel", d.sttModel) ?: d.sttModel,
            ttsModel = prefs.getString("ttsModel", d.ttsModel) ?: d.ttsModel,
            ttsVoice = prefs.getString("ttsVoice", d.ttsVoice) ?: d.ttsVoice,
            useCloudTts = prefs.getBoolean("useCloudTts", false),
            sttEngine = enumOr(prefs.getString("sttEngine", null), SttEngine.ANDROID),
            glassesMic = enumOr(prefs.getString("glassesMic", null), GlassesMicTransport.HFP_SCO),
            turnPolicy = enumOr(prefs.getString("turnPolicy", null), TurnPolicy.GLASSES_TAP),
            personALang = prefs.getString("personALang", d.personALang) ?: d.personALang,
            personBLang = prefs.getString("personBLang", d.personBLang) ?: d.personBLang,
            assistantLang = prefs.getString("assistantLang", d.assistantLang) ?: d.assistantLang,
            videoDurationMin = prefs.getInt("videoDurationMin", d.videoDurationMin),
            signalingUrl = prefs.getString("signalingUrl", "") ?: "",
            lastDeviceAddress = prefs.getString("lastDeviceAddress", "") ?: "",
            lastDeviceName = prefs.getString("lastDeviceName", "") ?: "",
            allowUnconfirmedGestures = prefs.getBoolean("allowUnconfirmedGestures", false),
            confirmedGestures = prefs.getStringSet("confirmedGestures", emptySet())?.toSet() ?: emptySet(),
            measuredRecordingMs = prefs.getLong("measuredRecordingMs", 0),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString("cloudBaseUrl", s.cloudBaseUrl.trim())
            .putString("cloudApiKey", s.cloudApiKey.trim())
            .putString("chatModel", s.chatModel.trim())
            .putString("sttModel", s.sttModel.trim())
            .putString("ttsModel", s.ttsModel.trim())
            .putString("ttsVoice", s.ttsVoice.trim())
            .putBoolean("useCloudTts", s.useCloudTts)
            .putString("sttEngine", s.sttEngine.name)
            .putString("glassesMic", s.glassesMic.name)
            .putString("turnPolicy", s.turnPolicy.name)
            .putString("personALang", s.personALang)
            .putString("personBLang", s.personBLang)
            .putString("assistantLang", s.assistantLang)
            .putInt("videoDurationMin", s.videoDurationMin)
            .putString("signalingUrl", s.signalingUrl.trim())
            .putString("lastDeviceAddress", s.lastDeviceAddress)
            .putString("lastDeviceName", s.lastDeviceName)
            .putBoolean("allowUnconfirmedGestures", s.allowUnconfirmedGestures)
            .putStringSet("confirmedGestures", s.confirmedGestures)
            .putLong("measuredRecordingMs", s.measuredRecordingMs)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        runCatching { enumValueOf<T>(name ?: "") }.getOrDefault(fallback)

    companion object {
        val VIDEO_DURATIONS = listOf(1, 3, 5, 7, 10)
    }
}
