package com.glassescompanion.app.cloud

import android.util.Base64
import com.glassescompanion.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudException(message: String) : Exception(message)

/**
 * Configurable OpenAI-compatible backend (chat, vision, speech-to-text,
 * text-to-speech). Base URL, key and model names come from Settings, so any
 * compatible gateway works. Disabled when no key is configured.
 */
class CloudClient(private val settings: SettingsStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val ready: Boolean get() = settings.current.cloudReady

    private fun base(): String = settings.current.cloudBaseUrl.trim().trimEnd('/')

    private fun authed(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", "Bearer ${settings.current.cloudApiKey.trim()}")

    private fun require() {
        if (!ready) throw CloudException("Cloud backend not configured (Settings → Cloud AI)")
    }

    suspend fun chat(system: String, user: String, temperature: Double = 0.2): String = withContext(Dispatchers.IO) {
        require()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        completion(messages, temperature)
    }

    suspend fun chatHistory(system: String, turns: List<Pair<String, String>>): String = withContext(Dispatchers.IO) {
        require()
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        turns.forEach { (role, text) -> messages.put(JSONObject().put("role", role).put("content", text)) }
        completion(messages, 0.5)
    }

    suspend fun vision(prompt: String, jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        require()
        val dataUri = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUri)))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        completion(messages, 0.3)
    }

    private fun completion(messages: JSONArray, temperature: Double): String {
        val body = JSONObject()
            .put("model", settings.current.chatModel)
            .put("messages", messages)
            .put("temperature", temperature)
        val req = authed(Request.Builder().url("${base()}/chat/completions"))
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw CloudException("Cloud chat HTTP ${res.code}: ${errorMessage(text)}")
            return JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content").trim()
        }
    }

    suspend fun transcribe(wav: ByteArray, languageCode: String?): String = withContext(Dispatchers.IO) {
        require()
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", settings.current.sttModel)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
        if (!languageCode.isNullOrBlank()) builder.addFormDataPart("language", languageCode.substringBefore('-'))
        val req = authed(Request.Builder().url("${base()}/audio/transcriptions")).post(builder.build()).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw CloudException("Cloud speech-to-text HTTP ${res.code}: ${errorMessage(text)}")
            JSONObject(text).optString("text").trim()
        }
    }

    /** Returns MP3 bytes. */
    suspend fun speech(text: String): ByteArray = withContext(Dispatchers.IO) {
        require()
        val body = JSONObject()
            .put("model", settings.current.ttsModel)
            .put("voice", settings.current.ttsVoice)
            .put("input", text)
            .put("response_format", "mp3")
        val req = authed(Request.Builder().url("${base()}/audio/speech")).post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw CloudException("Cloud text-to-speech HTTP ${res.code}: ${errorMessage(res.body?.string().orEmpty())}")
            res.body?.bytes() ?: throw CloudException("Empty speech response")
        }
    }

    suspend fun test(): String = chat("Reply with the single word OK.", "ping")

    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: body.take(200)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
