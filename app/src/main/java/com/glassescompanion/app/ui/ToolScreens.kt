package com.glassescompanion.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.glassescompanion.app.AppGraph
import com.glassescompanion.app.ble.BleLink
import com.glassescompanion.app.lang.LanguageRegistry
import com.glassescompanion.app.service.CompanionService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.io.File

@Composable
fun CallScreen(g: AppGraph, back: () -> Unit, joinRoom: String?, joinServer: String?) {
    val st by g.call.state.collectAsState()
    val ctx = LocalContext.current
    var code by remember { mutableStateOf(joinRoom.orEmpty()) }
    Screen("Call Translation", back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LanguageButton("I speak", st.myLang, Modifier.weight(1f)) { g.call.setLanguages(it, st.peerLang) }
                LanguageButton("They speak", st.peerLang, Modifier.weight(1f)) { g.call.setLanguages(st.myLang, it) }
            }
            ErrorBanner(st.error)
            if (!st.active) {
                Gap()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    YellowButton("Audio call", Modifier.weight(1f)) { CompanionService.start(ctx); g.call.host(video = false) }
                    YellowButton("Video call", Modifier.weight(1f)) { CompanionService.start(ctx); g.call.host(video = true) }
                }
                SectionTitle("Join with an invitation code")
                Field("Room code", code) { code = it.uppercase().take(6) }
                GhostButton("Join", Modifier.fillMaxWidth(), enabled = code.length == 6) { CompanionService.start(ctx); g.call.join(code, joinServer, video = true) }
                Text("Calls use your own signalling server (Settings → Calls). Audio is peer-to-peer WebRTC; captions are translated on each phone.", color = Gray, fontSize = 12.sp)
            } else {
                Card {
                    Text("Room ${st.room} · ${st.status}", color = Color.White)
                    Text(st.videoSourceDisclosure, color = Gray, fontSize = 12.sp)
                    if (st.inviteLink.isNotBlank()) {
                        Gap(6)
                        Text(st.inviteLink, color = Yellow, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("Copy link") {
                                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("invite", st.inviteLink))
                            }
                            GhostButton("Share") {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join my translated call: ${st.inviteLink}  (code ${st.room})"), "Invite"))
                            }
                        }
                    }
                }
                if (st.video) {
                    Gap()
                    VideoPanes(g)
                }
                SectionTitle("Live captions")
                st.captions.takeLast(20).forEach { c ->
                    Text((if (c.mine) "You: " else "Them: ") + c.original, color = Gray, fontSize = 12.sp)
                    if (c.translated.isNotBlank()) Text(c.translated, color = if (c.mine) Color.White else Yellow, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
        }
        if (st.active) Row(Modifier.navigationBarsPadding().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(if (st.muted) "Unmute" else "Mute", Modifier.weight(1f)) { g.call.toggleMute() }
            GhostButton(if (st.speakTranslations) "Voice on" else "Voice off", Modifier.weight(1f)) { g.call.toggleSpeak() }
            YellowButton("Hang up", Modifier.weight(1f)) { g.call.hangUp() }
        }
    }
}

@Composable
private fun VideoPanes(g: AppGraph) {
    val ctx = LocalContext.current
    val remote = remember { SurfaceViewRenderer(ctx) }
    val local = remember { SurfaceViewRenderer(ctx) }
    DisposableEffect(Unit) {
        runCatching {
            remote.init(g.call.eglContext(), null); remote.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            local.init(g.call.eglContext(), null); local.setMirror(true)
        }
        g.call.attachRenderers(remote, local)
        onDispose {
            g.call.attachRenderers(null, null)
            runCatching { remote.release() }
            runCatching { local.release() }
        }
    }
    Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp))) {
        AndroidView({ remote }, Modifier.fillMaxWidth().height(260.dp))
        AndroidView({ local }, Modifier.align(Alignment.BottomEnd).padding(8.dp).height(110.dp).fillMaxWidth(0.3f))
    }
}

// ---------------- Photo translation ----------------

@Composable
fun PhotoTranslateScreen(g: AppGraph, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ble by g.ble.state.collectAsState()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var source by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("en") }
    var to by remember { mutableStateOf(g.settings.current.personALang) }
    var recognized by remember { mutableStateOf("") }
    var translated by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val camFile = remember { File(ctx.cacheDir, "photo_translate.jpg") }

    fun process(bmp: Bitmap, src: String) {
        bitmap = bmp
        source = src
        recognized = ""
        translated = ""
        error = null
        busy = true
        scope.launch {
            runCatching {
                val text = ocr(bmp)
                recognized = text
                if (text.isBlank()) throw IllegalStateException("No text found in the photo")
                translated = g.translator.translate(text, LanguageRegistry.require(from), LanguageRegistry.require(to)).text
            }.onFailure { error = it.message }
            busy = false
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) decodeFile(camFile)?.let { process(it, "Phone camera") }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { u -> decodeUri(ctx, u)?.let { process(it, "Gallery") } }
    }

    Screen("Photo Translation", back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LanguageButton("Text language", from, Modifier.weight(1f)) { from = it }
                LanguageButton("Translate to", to, Modifier.weight(1f)) { to = it }
            }
            Gap()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YellowButton("Glasses", Modifier.weight(1f), enabled = ble.link == BleLink.READY && !busy) {
                    busy = true
                    scope.launch {
                        val jpeg = g.assistant.capturePreview()
                        busy = false
                        val bmp = jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        if (bmp != null) process(bmp, "Glasses camera (BLE preview)") else error = "The glasses did not send a preview photo"
                    }
                }
                GhostButton("Phone camera", Modifier.weight(1f), enabled = !busy) {
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", camFile)
                    takePicture.launch(uri)
                }
                GhostButton("Gallery", Modifier.weight(1f), enabled = !busy) { pick.launch("image/*") }
            }
            ErrorBanner(error)
            bitmap?.let { bmp ->
                Gap()
                Box {
                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                    Box(Modifier.padding(8.dp)) { Badge(source) }
                }
            }
            if (busy) Text("Working…", color = Gray, modifier = Modifier.padding(8.dp))
            if (recognized.isNotBlank()) {
                SectionTitle("Recognized text")
                Card { Text(recognized, color = Color.White, fontSize = 14.sp) }
            }
            if (translated.isNotBlank()) {
                SectionTitle("Translation · ${LanguageRegistry.require(to).name}")
                Card { Text(translated, color = Yellow, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

private suspend fun ocr(bmp: Bitmap): String {
    val image = InputImage.fromBitmap(bmp, 0)
    val recognizers = listOf(
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
    )
    val results = recognizers.map { r -> runCatching { r.process(image).await().text }.getOrDefault("").also { r.close() } }
    return results.maxByOrNull { t -> t.count { it.isLetterOrDigit() } }.orEmpty()
}

private fun decodeFile(f: File): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (o.outWidth / s > 2000 || o.outHeight / s > 2000) s *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
}.getOrNull()

private fun decodeUri(ctx: Context, u: Uri): Bitmap? = runCatching {
    val tmp = File(ctx.cacheDir, "pick.jpg")
    ctx.contentResolver.openInputStream(u)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    decodeFile(tmp)
}.getOrNull()

// ---------------- AI Recording ----------------

@Composable
fun RecordingScreen(g: AppGraph, back: () -> Unit) {
    val st by g.recorder.state.collectAsState()
    val settings by g.settings.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Screen("AI Recording", back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Card {
                Text("Measured usage on this phone", color = Gray, fontSize = 12.sp)
                Text(formatDuration(settings.measuredRecordingMs), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Counted from audio actually captured. No minute allowance is assumed.", color = Gray, fontSize = 11.sp)
            }
            Gap()
            Row {
                Chip("Phone mic", st.source == com.glassescompanion.app.speech.MicSource.PHONE) { g.recorder.setSource(com.glassescompanion.app.speech.MicSource.PHONE) }
                Chip("Glasses mic", st.source == com.glassescompanion.app.speech.MicSource.GLASSES) { g.recorder.setSource(com.glassescompanion.app.speech.MicSource.GLASSES) }
            }
            Waveform(st.recording)
            Text(formatDuration(st.elapsedMs), color = Yellow, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            ErrorBanner(st.error)
            Gap()
            SwitchRow("Also record on the glasses (0x34)", st.glassesRecording, "Saved on the glasses; import it later over Wi-Fi") { on ->
                scope.launch { g.recorder.setGlassesRecording(on) }
            }
            if (st.lastFile.isNotBlank() && !st.recording) {
                GhostButton(if (st.busy) "Working…" else "Transcribe & summarize", Modifier.fillMaxWidth(), enabled = !st.busy) { g.recorder.transcribeAndSummarize() }
            }
            if (st.transcript.isNotBlank()) { SectionTitle("Transcript"); Card { Text(st.transcript, color = Color.White, fontSize = 13.sp) } }
            if (st.summary.isNotBlank()) { SectionTitle("Summary"); Card { Text(st.summary, color = Yellow, fontSize = 14.sp) } }
        }
        Row(Modifier.navigationBarsPadding().padding(vertical = 10.dp)) {
            if (st.recording) YellowButton("Stop", Modifier.fillMaxWidth()) { g.recorder.stop() }
            else YellowButton("Start recording", Modifier.fillMaxWidth()) { CompanionService.start(ctx); g.recorder.start() }
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

// ---------------- Assistant ----------------

@Composable
fun AssistantScreen(g: AppGraph, back: () -> Unit) {
    val st by g.assistant.state.collectAsState()
    var text by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        g.assistant.arm()
        onDispose { g.assistant.disarm() }
    }
    Screen("AI Assistant", back) {
        Text(
            (if (st.preRollActive) "Pre-roll 600 ms active · " else "") + st.micLabel,
            color = Gray, fontSize = 11.sp,
        )
        ErrorBanner(st.error)
        LazyColumn(Modifier.weight(1f)) {
            if (st.turns.isEmpty()) item {
                Card(Modifier.padding(vertical = 8.dp)) {
                    Text("Try:", color = Gray, fontSize = 12.sp)
                    listOf("Take a photo", "वीडियो रिकॉर्डिंग शुरू करो", "recording roko", "gaana chalao", "volume badhao",
                        "remind me in 10 minutes to call mom", "call uthao", "ye kya hai?").forEach {
                        Text("• $it", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
            items(st.turns) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (t.fromUser) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(14.dp)).padding(2.dp)) {
                        t.image?.let { img -> BitmapFactory.decodeByteArray(img, 0, img.size)?.let { Image(it.asImageBitmap(), null, Modifier.height(140.dp).clip(RoundedCornerShape(10.dp))) } }
                        Text(t.text, color = if (t.fromUser) Color.White else Yellow, fontSize = 15.sp)
                        t.action?.let { Text("✓ $it", color = Green, fontSize = 11.sp) }
                    }
                }
            }
            if (st.thinking) item { Text("…", color = Gray) }
        }
        Waveform(st.listening, st.level)
        Row(Modifier.navigationBarsPadding().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Field("Type or tap the mic", text) { text = it } }
            if (text.isNotBlank()) YellowButton("Send") { g.assistant.handleText(text); text = "" }
            else YellowButton(if (st.listening) "Listening" else "Speak", enabled = !st.listening) { g.assistant.trigger() }
        }
    }
}
