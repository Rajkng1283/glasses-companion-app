package com.glassescompanion.app.translate

import com.glassescompanion.app.cloud.CloudClient
import com.glassescompanion.app.lang.Language
import com.glassescompanion.app.lang.LanguageRegistry
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

data class TranslationResult(val text: String, val engine: String)

/**
 * Translation engine: the configured cloud model when available (all 100
 * languages), otherwise Google ML Kit on-device translation (about 59 languages,
 * one-time model download), so translation works on the phone immediately.
 */
class Translator(private val cloud: CloudClient) {

    private val clients = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()

    fun onDeviceSupported(lang: Language): Boolean =
        TranslateLanguage.fromLanguageTag(LanguageRegistry.mlKitTag(lang)) != null

    fun canTranslate(from: Language, to: Language): Boolean =
        cloud.ready || (onDeviceSupported(from) && onDeviceSupported(to))

    suspend fun translate(text: String, from: Language, to: Language): TranslationResult {
        if (text.isBlank()) return TranslationResult("", "none")
        if (from.code == to.code) return TranslationResult(text, "same language")
        if (cloud.ready) {
            val cloudResult = runCatching {
                cloud.chat(
                    system = "You are a translation engine. Translate the user's text from ${from.name} to ${to.name}" +
                        (if (to.code == "zh-CN") " (Mainland Mandarin, Simplified characters)" else "") +
                        ". Output only the translation, no quotes, no notes, no transliteration. Keep names and numbers.",
                    user = text,
                    temperature = 0.0,
                )
            }
            cloudResult.getOrNull()?.takeIf { it.isNotBlank() }?.let { return TranslationResult(it, "cloud") }
            if (!(onDeviceSupported(from) && onDeviceSupported(to))) {
                throw cloudResult.exceptionOrNull() ?: IllegalStateException("Cloud translation returned nothing")
            }
        }
        val src = TranslateLanguage.fromLanguageTag(LanguageRegistry.mlKitTag(from))
        val dst = TranslateLanguage.fromLanguageTag(LanguageRegistry.mlKitTag(to))
        if (src == null || dst == null) {
            val missing = if (src == null) from.name else to.name
            throw IllegalStateException("$missing needs the cloud translator (Settings → Cloud AI). On-device translation does not cover it.")
        }
        val key = "$src>$dst"
        val client = clients.getOrPut(key) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
        }
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return TranslationResult(client.translate(text).await(), "on-device")
    }

    suspend fun prepare(from: Language, to: Language): String? = runCatching {
        if (cloud.ready) return null
        val src = TranslateLanguage.fromLanguageTag(LanguageRegistry.mlKitTag(from)) ?: return "${from.name}: cloud translator required"
        val dst = TranslateLanguage.fromLanguageTag(LanguageRegistry.mlKitTag(to)) ?: return "${to.name}: cloud translator required"
        val client = clients.getOrPut("$src>$dst") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
        }
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        null
    }.getOrElse { "Language model download failed: ${it.message}" }
}
