package com.glassescompanion.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassescompanion.app.AppGraph
import com.glassescompanion.app.BuildConfig
import com.glassescompanion.app.data.GlassesMicTransport
import com.glassescompanion.app.data.SettingsStore
import com.glassescompanion.app.data.SttEngine
import com.glassescompanion.app.data.TurnPolicy
import com.glassescompanion.app.gesture.PhysicalControlDispatcher
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(g: AppGraph, back: () -> Unit, openGuide: () -> Unit) {
    val s by g.settings.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var test by remember { mutableStateOf<String?>(null) }
    Screen("Settings", back) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionTitle("Cloud AI (OpenAI-compatible)")
            Card {
                Field("Base URL", s.cloudBaseUrl) { v -> g.settings.update { it.copy(cloudBaseUrl = v) } }
                Field("API key", s.cloudApiKey, secret = true) { v -> g.settings.update { it.copy(cloudApiKey = v) } }
                Field("Chat / vision model", s.chatModel) { v -> g.settings.update { it.copy(chatModel = v) } }
                Field("Speech-to-text model", s.sttModel) { v -> g.settings.update { it.copy(sttModel = v) } }
                Field("Voice model", s.ttsModel) { v -> g.settings.update { it.copy(ttsModel = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    YellowButton("Test", enabled = s.cloudReady) {
                        test = "Testing…"
                        scope.launch { test = runCatching { "OK: " + g.cloud.test() }.getOrElse { "Failed: ${it.message}" } }
                    }
                    Text("  " + (test ?: if (s.cloudReady) "Configured" else "Not configured — on-device engines are used"), color = Gray, fontSize = 12.sp)
                }
                Text("Without a key: Android speech recognition + ML Kit on-device translation (~59 languages) + Android voices. With a key: all 100 languages.", color = Gray, fontSize = 11.sp)
            }
            SectionTitle("Speech")
            Card {
                Text("Speech-to-text engine", color = Color.White)
                Row {
                    Chip("Android (phone)", s.sttEngine == SttEngine.ANDROID) { g.settings.update { it.copy(sttEngine = SttEngine.ANDROID) } }
                    Chip("Cloud", s.sttEngine == SttEngine.CLOUD) { g.settings.update { it.copy(sttEngine = SttEngine.CLOUD) } }
                }
                SwitchRow("Cloud voice output", s.useCloudTts, "Otherwise Android text-to-speech voices") { v -> g.settings.update { it.copy(useCloudTts = v) } }
                Text("Glasses microphone transport", color = Color.White)
                Row {
                    Chip("Bluetooth HFP", s.glassesMic == GlassesMicTransport.HFP_SCO) { g.settings.update { it.copy(glassesMic = GlassesMicTransport.HFP_SCO) } }
                    Chip("BLE Opus (experimental)", s.glassesMic == GlassesMicTransport.BLE_OPUS) { g.settings.update { it.copy(glassesMic = GlassesMicTransport.BLE_OPUS) } }
                }
                Text("Dialogue turn control", color = Color.White)
                Row {
                    Chip("Glasses tap = Person A", s.turnPolicy == TurnPolicy.GLASSES_TAP) { g.settings.update { it.copy(turnPolicy = TurnPolicy.GLASSES_TAP) } }
                    Chip("Alternate A/B", s.turnPolicy == TurnPolicy.ALTERNATE) { g.settings.update { it.copy(turnPolicy = TurnPolicy.ALTERNATE) } }
                }
                Text("Assistant language", color = Color.White)
                Row {
                    Chip("Auto / Hinglish", s.assistantLang == "auto") { g.settings.update { it.copy(assistantLang = "auto") } }
                    Chip("English", s.assistantLang == "en") { g.settings.update { it.copy(assistantLang = "en") } }
                    Chip("हिन्दी", s.assistantLang == "hi") { g.settings.update { it.copy(assistantLang = "hi") } }
                }
            }
            SectionTitle("Glasses video")
            Card {
                Text("Video recording duration", color = Color.White)
                FlowRow {
                    SettingsStore.VIDEO_DURATIONS.forEach { m -> Chip("$m min", s.videoDurationMin == m) { g.settings.update { it.copy(videoDurationMin = m) } } }
                }
                Text("Video started from the app is stopped automatically after this time.", color = Gray, fontSize = 11.sp)
            }
            SectionTitle("Calls")
            Card {
                Field("Signalling server (wss://…)", s.signalingUrl) { v -> g.settings.update { it.copy(signalingUrl = v) } }
                Text("Deploy signaling-server/ from the repository (any Node host) and paste its URL.", color = Gray, fontSize = 11.sp)
            }
            SectionTitle("Physical controls")
            Card {
                SwitchRow("Act on unconfirmed gestures", s.allowUnconfirmedGestures, "Off: unknown touchpad events are logged, not executed") { v ->
                    g.settings.update { it.copy(allowUnconfirmedGestures = v) }
                }
                GhostButton("Open Physical Control Guide", Modifier.fillMaxWidth()) { openGuide() }
            }
            SectionTitle("Permissions & app")
            Card {
                GhostButton("Android app permissions", Modifier.fillMaxWidth()) {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                }
                Gap(6)
                GhostButton("Text-to-speech voices", Modifier.fillMaxWidth()) {
                    runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                }
                Gap(6)
                Text("Glasses Companion ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ControlGuideScreen(g: AppGraph, back: () -> Unit) {
    val s by g.settings.state.collectAsState()
    val last by g.lastControl.collectAsState()
    val log by g.controls.log.collectAsState()
    Screen("Physical Control Guide", back) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionTitle("What each control does, by state")
            PhysicalControlDispatcher.GUIDE.forEach { (control, state, action) ->
                Card(Modifier.padding(vertical = 3.dp)) {
                    Text(control, color = Color.White, fontSize = 14.sp)
                    Text("When: $state", color = Gray, fontSize = 12.sp)
                    Text("→ $action", color = Yellow, fontSize = 13.sp)
                }
            }
            SectionTitle("Test my glasses")
            Card {
                Text("Tap the touchpad or press a button. The last event received over BLE:", color = Gray, fontSize = 12.sp)
                Text(last.ifBlank { "Nothing received yet" }, color = Color.White, fontSize = 15.sp)
                if (last.isNotBlank() && last !in s.confirmedGestures) {
                    GhostButton("Confirm: this was my tap", Modifier.fillMaxWidth(), color = Yellow) {
                        g.settings.update { it.copy(confirmedGestures = it.confirmedGestures + last) }
                    }
                }
                if (s.confirmedGestures.isNotEmpty()) {
                    Text("Confirmed: " + s.confirmedGestures.joinToString(), color = Green, fontSize = 12.sp)
                    GhostButton("Clear confirmations", Modifier.fillMaxWidth()) { g.settings.update { it.copy(confirmedGestures = emptySet()) } }
                }
                Text("Recording-ending actions never run from an unconfirmed gesture.", color = Gray, fontSize = 11.sp)
            }
            SectionTitle("Recent control events")
            log.reversed().forEach { e -> Text("${e.event} — ${e.decision}", color = Gray, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Suppress("unused")
private val sp8 = Arrangement.spacedBy(8.dp)
