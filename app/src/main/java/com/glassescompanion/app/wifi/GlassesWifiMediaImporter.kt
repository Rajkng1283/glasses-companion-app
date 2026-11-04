package com.glassescompanion.app.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.glassescompanion.app.ble.BleControlManager
import com.glassescompanion.app.ble.BleEvent
import com.glassescompanion.app.ble.EyevueBleProtocol
import com.glassescompanion.app.ble.EyevueBleProtocol.Cmd
import com.glassescompanion.app.media.MediaItem
import com.glassescompanion.app.media.MediaLibrary
import com.glassescompanion.app.media.mediaTypeOf
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class RemoteMedia(val name: String, val path: String, val size: Long)

enum class WifiPhase { IDLE, OPENING_GLASSES_WIFI, JOINING, LISTING, DOWNLOADING, DONE, ERROR }

data class ImportState(
    val phase: WifiPhase = WifiPhase.IDLE,
    val ssid: String = "",
    val remote: List<RemoteMedia> = emptyList(),
    val currentFile: String = "",
    val fileProgress: Float = 0f,
    val done: Int = 0,
    val skipped: Int = 0,
    val failed: List<String> = emptyList(),
    val message: String = "",
) {
    val wifiConnected: Boolean get() = phase == WifiPhase.LISTING || phase == WifiPhase.DOWNLOADING || phase == WifiPhase.DONE
}

/** Tolerant parser for the /app/getfilelist response (format varies by firmware). */
object GlassesFileListParser {
    private val nameKeys = listOf("name", "fileName", "filename", "file", "fname")
    private val pathKeys = listOf("path", "url", "filePath", "fpath", "uri", "fileUrl")
    private val sizeKeys = listOf("size", "fileSize", "filesize", "length", "len")

    fun parse(body: String): List<RemoteMedia> {
        val t = body.trim()
        val out = ArrayList<RemoteMedia>()
        when {
            t.startsWith("[") -> collect(JSONArray(t), out)
            t.startsWith("{") -> collect(JSONObject(t), out)
            else -> t.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val parts = line.split(Regex("[,\\s]+"))
                val path = parts[0]
                if (path.contains('.')) out += RemoteMedia(path.substringAfterLast('/'), normalize(path), parts.getOrNull(1)?.toLongOrNull() ?: -1)
            }
        }
        return out.distinctBy { it.path }
    }

    private fun collect(obj: JSONObject, out: MutableList<RemoteMedia>) {
        val name = nameKeys.firstNotNullOfOrNull { k -> obj.optString(k).takeIf { obj.has(k) && it.isNotBlank() } }
        val path = pathKeys.firstNotNullOfOrNull { k -> obj.optString(k).takeIf { obj.has(k) && it.isNotBlank() } }
        if (name != null || path != null) {
            val p = path ?: name!!
            val n = name ?: p.substringAfterLast('/')
            val size = sizeKeys.firstNotNullOfOrNull { k -> if (obj.has(k)) obj.optLong(k, -1) else null } ?: -1
            if (n.contains('.')) out += RemoteMedia(n.substringAfterLast('/'), normalize(p), size)
            return
        }
        val keys = obj.keys()
        while (keys.hasNext()) {
            when (val v = obj.opt(keys.next())) {
                is JSONArray -> collect(v, out)
                is JSONObject -> collect(v, out)
            }
        }
    }

    private fun collect(arr: JSONArray, out: MutableList<RemoteMedia>) {
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is String -> if (v.contains('.')) out += RemoteMedia(v.substringAfterLast('/'), normalize(v), -1)
                is JSONObject -> collect(v, out)
                is JSONArray -> collect(v, out)
            }
        }
    }

    fun normalize(path: String): String {
        val p = path.trim()
        if (p.startsWith("http://") || p.startsWith("https://")) return p
        return if (p.startsWith("/")) p else "/$p"
    }

    fun urlFor(host: String, item: RemoteMedia): String =
        if (item.path.startsWith("http")) item.path else "http://$host${item.path}"
}

/** Lightweight integrity checks on a finished download. */
object MediaIntegrity {
    fun check(file: File, expectedSize: Long, contentLength: Long): String? {
        val size = file.length()
        if (size == 0L) return "empty file"
        if (expectedSize > 0 && size != expectedSize) return "size $size ≠ listed $expectedSize"
        if (contentLength > 0 && size < contentLength) return "truncated ($size of $contentLength)"
        val head = ByteArray(12)
        file.inputStream().use { it.read(head) }
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> {
                val tail = ByteArray(2)
                java.io.RandomAccessFile(file, "r").use { raf -> raf.seek(size - 2); raf.readFully(tail) }
                if ((head[0].toInt() and 0xFF) != 0xFF || (head[1].toInt() and 0xFF) != 0xD8) "not a JPEG"
                else if ((tail[0].toInt() and 0xFF) != 0xFF || (tail[1].toInt() and 0xFF) != 0xD9) "JPEG end marker missing"
                else null
            }
            "mp4", "mov" -> if (String(head, 4, 4, Charsets.US_ASCII) != "ftyp") "MP4 header missing" else null
            else -> null
        }
    }
}

/**
 * Imports photos/videos/audio from the glasses over their own Wi-Fi AP:
 * BLE 0x39 OPEN_WIFI → credentials on 0x25 → join via WifiNetworkSpecifier →
 * GET http://192.168.169.1/app/getfilelist → resumable downloads with integrity
 * checks. Originals are preserved: FILE_DOWNLOAD_FINISH (0x44) is never sent here.
 */
@SuppressLint("MissingPermission")
class GlassesWifiMediaImporter(
    private val context: Context,
    private val ble: BleControlManager,
    private val library: MediaLibrary,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var job: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var network: Network? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                run()
            } catch (e: Exception) {
                if (isActive) mutate { it.copy(phase = WifiPhase.ERROR, message = e.message ?: e.toString()) }
            } finally {
                releaseNetwork()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        releaseNetwork()
        mutate { it.copy(phase = WifiPhase.IDLE, message = "Import cancelled; partial files are kept and resume next time") }
    }

    private suspend fun run() {
        if (!ble.isReady) throw IllegalStateException("Connect the glasses over BLE first (needed to switch on their Wi-Fi)")
        mutate { ImportState(phase = WifiPhase.OPENING_GLASSES_WIFI, message = "Asking the glasses to open Wi-Fi (0x39)") }
        val creds = requestCredentials() ?: throw IllegalStateException("Glasses did not send Wi-Fi credentials (0x25)")
        mutate { it.copy(phase = WifiPhase.JOINING, ssid = creds.ssid, message = "Joining ${creds.ssid}") }
        val net = join(creds) ?: throw IllegalStateException("Could not join the glasses Wi-Fi ${creds.ssid}")
        network = net
        val http = OkHttpClient.Builder()
            .socketFactory(net.socketFactory)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        mutate { it.copy(phase = WifiPhase.LISTING, message = "Reading file list") }
        val listUrl = "http://${EyevueBleProtocol.GLASSES_AP_HOST}${EyevueBleProtocol.FILE_LIST_PATH}"
        val body = retry(3) {
            http.newCall(Request.Builder().url(listUrl).build()).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("File list HTTP ${res.code}")
                res.body?.string().orEmpty()
            }
        }
        val remote = GlassesFileListParser.parse(body)
        mutate { it.copy(remote = remote, message = "${remote.size} files on the glasses") }
        var done = 0
        var skipped = 0
        val failed = ArrayList<String>()
        for (item in remote) {
            if (library.contains(item.path, item.size) != null) {
                skipped++
                mutate { it.copy(skipped = skipped) }
                continue
            }
            mutate { it.copy(phase = WifiPhase.DOWNLOADING, currentFile = item.name, fileProgress = 0f) }
            val ok = runCatching { retry(3) { download(http, item) } }
            if (ok.isSuccess) done++ else failed += "${item.name}: ${ok.exceptionOrNull()?.message}"
            mutate { it.copy(done = done, failed = failed.toList()) }
        }
        mutate {
            it.copy(
                phase = WifiPhase.DONE,
                currentFile = "",
                message = "Imported $done, already on phone $skipped, failed ${failed.size}. Originals kept on the glasses.",
            )
        }
    }

    private suspend fun requestCredentials(): EyevueBleProtocol.WifiCredentials? = coroutineScopeAsync {
        val waiter = async {
            withTimeoutOrNull(20_000) {
                ble.events.filter { it is BleEvent.Frame && it.frame.command == Cmd.APP_RECEIVE_WIFI_INFO }.first()
            } as BleEvent.Frame?
        }
        delay(20)
        ble.send(Cmd.OPEN_WIFI, byteArrayOf(0x01))
        waiter.await()?.let { EyevueBleProtocol.parseWifiInfo(it.frame.payload) }
    }

    private suspend fun <T> coroutineScopeAsync(block: suspend CoroutineScope.() -> T): T = kotlinx.coroutines.coroutineScope(block)

    private suspend fun join(creds: EyevueBleProtocol.WifiCredentials): Network? {
        if (Build.VERSION.SDK_INT >= 29) {
            val spec = WifiNetworkSpecifier.Builder().setSsid(creds.ssid).apply {
                if (creds.password.isNotEmpty()) setWpa2Passphrase(creds.password)
            }.build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
            val result = CompletableDeferred<Network?>()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { result.complete(network) }
                override fun onUnavailable() { result.complete(null) }
                override fun onLost(network: Network) {
                    if (this@GlassesWifiMediaImporter.network == network) mutate { it.copy(message = "Glasses Wi-Fi lost") }
                }
            }
            callback = cb
            withContext(Dispatchers.Main) { cm.requestNetwork(request, cb, 60_000) }
            return withTimeoutOrNull(65_000) { result.await() }
        }
        // Android 8/9: legacy configuration API.
        @Suppress("DEPRECATION")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val conf = WifiConfiguration().apply {
            SSID = "\"${creds.ssid}\""
            if (creds.password.isEmpty()) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE) else preSharedKey = "\"${creds.password}\""
        }
        @Suppress("DEPRECATION")
        val id = wm.addNetwork(conf)
        if (id < 0) return null
        @Suppress("DEPRECATION")
        wm.enableNetwork(id, true)
        return withTimeoutOrNull(30_000) {
            var found: Network? = null
            while (found == null) {
                @Suppress("DEPRECATION")
                val n = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                @Suppress("DEPRECATION")
                val ssid = wm.connectionInfo?.ssid?.trim('"')
                if (n != null && ssid == creds.ssid) found = n else delay(500)
            }
            found
        }
    }

    private fun download(http: OkHttpClient, item: RemoteMedia) {
        val target = library.newFile(item.name)
        val part = File(target.path + ".part")
        val already = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(GlassesFileListParser.urlFor(EyevueBleProtocol.GLASSES_AP_HOST, item)).apply {
            if (already > 0) header("Range", "bytes=$already-")
        }.build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            val append = res.code == 206 && already > 0
            val body = res.body ?: throw IllegalStateException("empty body")
            val total = if (append) already + body.contentLength() else body.contentLength()
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                var written = if (append) already else 0L
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) mutate { it.copy(fileProgress = written.toFloat() / total) }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("could not finalise file")
            MediaIntegrity.check(target, item.size, total)?.let { problem ->
                target.renameTo(part) // keep bytes so the next attempt resumes / re-verifies
                throw IllegalStateException("integrity check failed: $problem")
            }
        }
        library.add(
            MediaItem(
                id = item.path,
                fileName = item.name,
                localPath = target.path,
                type = mediaTypeOf(item.name),
                size = target.length(),
                sha256 = sha256(target),
                importedAt = System.currentTimeMillis(),
                source = "glasses",
                remotePath = item.path,
                glassesOriginalPresent = true,
            ),
        )
    }

    /**
     * Explicit, user-confirmed only: tells the glasses the transfer is finished
     * so the firmware may clear its media storage (affects all originals).
     */
    suspend fun deleteGlassesOriginals(): Boolean {
        val ok = ble.send(Cmd.FILE_DOWNLOAD_FINISH, byteArrayOf(0x30, 0x00))
        if (ok) library.markGlassesOriginalsCleared()
        return ok
    }

    private fun releaseNetwork() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }

    private suspend fun <T> retry(times: Int, block: () -> T): T {
        var last: Exception? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                last = e
                delay(800L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("failed")
    }

    private fun mutate(block: (ImportState) -> ImportState) {
        _state.value = block(_state.value)
    }

    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
