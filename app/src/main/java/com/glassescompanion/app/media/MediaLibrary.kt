package com.glassescompanion.app.media

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class MediaType { IMAGE, VIDEO, AUDIO, OTHER }

data class MediaItem(
    val id: String,
    val fileName: String,
    val localPath: String,
    val type: MediaType,
    val size: Long,
    val sha256: String,
    val importedAt: Long,
    val source: String,
    val remotePath: String = "",
    /** True while the original is believed to still exist on the glasses storage. */
    val glassesOriginalPresent: Boolean = false,
    val durationMs: Long = 0,
) {
    val file: File get() = File(localPath)
}

fun mediaTypeOf(name: String): MediaType {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "webp", "heic" -> MediaType.IMAGE
        "mp4", "mov", "3gp", "mkv", "avi" -> MediaType.VIDEO
        "mp3", "aac", "m4a", "wav", "opus", "ogg", "amr" -> MediaType.AUDIO
        else -> MediaType.OTHER
    }
}

/** Phone-side album. Deleting here only ever removes the phone copy. */
class MediaLibrary(context: Context) {
    val dir: File = File(context.filesDir, "media").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    @Synchronized
    fun add(item: MediaItem) {
        _items.value = (_items.value.filterNot { it.id == item.id } + item).sortedByDescending { it.importedAt }
        save()
    }

    fun contains(remotePath: String, size: Long): MediaItem? =
        _items.value.firstOrNull { it.remotePath == remotePath && it.remotePath.isNotEmpty() && (size <= 0 || it.size == size) && it.file.exists() }

    @Synchronized
    fun deletePhoneCopy(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        item.file.delete()
        _items.value = _items.value.filterNot { it.id == id }
        save()
    }

    @Synchronized
    fun markGlassesOriginalsCleared() {
        _items.value = _items.value.map { it.copy(glassesOriginalPresent = false) }
        save()
    }

    fun newFile(name: String): File = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun load(): List<MediaItem> = runCatching {
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MediaItem(
                id = o.getString("id"),
                fileName = o.getString("fileName"),
                localPath = o.getString("localPath"),
                type = runCatching { MediaType.valueOf(o.getString("type")) }.getOrDefault(MediaType.OTHER),
                size = o.optLong("size"),
                sha256 = o.optString("sha256"),
                importedAt = o.optLong("importedAt"),
                source = o.optString("source"),
                remotePath = o.optString("remotePath"),
                glassesOriginalPresent = o.optBoolean("glassesOriginalPresent"),
                durationMs = o.optLong("durationMs"),
            )
        }.filter { File(it.localPath).exists() }
    }.getOrDefault(emptyList())

    private fun save() {
        val arr = JSONArray()
        _items.value.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("fileName", it.fileName).put("localPath", it.localPath)
                    .put("type", it.type.name).put("size", it.size).put("sha256", it.sha256)
                    .put("importedAt", it.importedAt).put("source", it.source).put("remotePath", it.remotePath)
                    .put("glassesOriginalPresent", it.glassesOriginalPresent).put("durationMs", it.durationMs),
            )
        }
        indexFile.writeText(arr.toString())
    }
}
