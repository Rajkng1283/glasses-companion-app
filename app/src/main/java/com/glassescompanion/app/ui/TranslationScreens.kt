package com.glassescompanion.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassescompanion.app.AppGraph
import com.glassescompanion.app.audio.OutputTarget
import com.glassescompanion.app.lang.LanguageRegistry
import com.glassescompanion.app.service.CompanionService
import com.glassescompanion.app.speech.MicSource
import com.glassescompanion.app.translate.DialogueLine
import com.glassescompanion.app.translate.Speaker
import com.glassescompanion.app.translate.TurnPhase

@Composable
fun DialogueScreen(g: AppGraph, back: () -> Unit) {
    val st by g.dialogue.state.collectAsState()
    val audio by g.audio.state.collectAsState()
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    LaunchedEffect(st.lines.size) { if (st.lines.isNotEmpty()) list.animateScrollToItem(st.lines.size - 1) }
    Screen("Dialogue Translation", back, actions = {
        IconButton(onClick = { g.dialogue.clear() }) { Icon(Icons.Outlined.DeleteSweep, "Clear", tint = Color.White) }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LanguageButton("Person A · glasses", st.aLang, Modifier.weight(1f)) { if (!st.running) g.dialogue.setLanguages(it, st.bLang) }
            IconButton(onClick = { if (!st.running) g.dialogue.swapLanguages() }) { Icon(Icons.Outlined.SwapHoriz, "Swap", tint = Yellow) }
            LanguageButton("Person B · phone", st.bLang, Modifier.weight(1f)) { if (!st.running) g.dialogue.setLanguages(st.aLang, it) }
        }
        Gap(6)
        Text("A: glasses mic → phone speaker   ·   B: phone mic → glasses speakers", color = Gray, fontSize = 11.sp)
        if (!audio.glassesMicScoAvailable) ErrorBanner("Glasses microphone (HFP) not connected — Person A cannot be heard. The phone mic is never used in its place.")
        if (!audio.glassesSpeakerA2dp && !audio.glassesSpeakerSco) ErrorBanner("Glasses speakers not connected — Person B's translation will not be played on the phone instead.")
        ErrorBanner(st.error)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            items(st.lines) { DialogueBubble(it, st.aLang, st.bLang) }
            if (st.partial.isNotBlank()) item {
                Text("… ${st.partial}", color = Gray, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
            }
        }
        Waveform(st.phase == TurnPhase.LISTENING || st.phase == TurnPhase.SPEAKING)
        Text(st.status, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        Row(Modifier.navigationBarsPadding().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (st.running) {
                GhostButton("Person A speaks", Modifier.weight(1f), color = Yellow) { g.dialogue.requestPersonA() }
                YellowButton("Stop", Modifier.weight(1f)) { g.dialogue.stop() }
            } else {
                YellowButton("Start", Modifier.fillMaxWidth()) {
                    CompanionService.start(ctx)
                    g.dialogue.start()
                }
            }
        }
    }
}

@Composable
private fun DialogueBubble(line: DialogueLine, a: String, b: String) {
    val isA = line.speaker == Speaker.A
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (isA) Arrangement.Start else Arrangement.End) {
        Column(
            Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(14.dp)).background(if (isA) Charcoal else Charcoal2).padding(10.dp),
        ) {
            Text(if (isA) "A · ${LanguageRegistry.require(a).name}" else "B · ${LanguageRegistry.require(b).name}", color = Gray, fontSize = 11.sp)
            Text(line.original, color = Color.White, fontSize = 15.sp)
            if (line.translated.isNotBlank()) Text(line.translated, color = Yellow, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            line.error?.let { Text(it, color = Red, fontSize = 12.sp) }
            if (line.engine.isNotBlank()) Text(line.engine, color = Gray, fontSize = 10.sp)
        }
    }
}

/** Face-to-face: the top half faces Person B (rotated), the bottom half faces the wearer. */
@Composable
fun GlassesPhoneScreen(g: AppGraph, back: () -> Unit) {
    val st by g.dialogue.state.collectAsState()
    val ctx = LocalContext.current
    val lastForB = st.lines.lastOrNull { it.speaker == Speaker.A }
    val lastForA = st.lines.lastOrNull { it.speaker == Speaker.B }
    Screen("Glasses + Phone", back) {
        Box(Modifier.weight(1f).fillMaxWidth().rotate(180f).clip(RoundedCornerShape(16.dp)).background(Charcoal).padding(16.dp)) {
            Column {
                Text("${LanguageRegistry.require(st.bLang).name} · for you", color = Gray, fontSize = 12.sp)
                Text(lastForB?.translated ?: "Waiting for the glasses wearer…", color = Yellow, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                if (st.activeSpeaker == Speaker.B && st.phase == TurnPhase.LISTENING) Text("Speak now — the phone is listening", color = Color.White, fontSize = 14.sp)
            }
        }
        Gap(8)
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Charcoal).padding(16.dp)) {
            Column {
                Text("${LanguageRegistry.require(st.aLang).name} · wearer", color = Gray, fontSize = 12.sp)
                Text(lastForA?.translated ?: "Their words appear here and play in your glasses.", color = Color.White, fontSize = 22.sp)
                ErrorBanner(st.error)
                Text(st.status, color = Gray, fontSize = 12.sp)
            }
        }
        Row(Modifier.navigationBarsPadding().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LanguageButton("A", st.aLang, Modifier.weight(1f)) { if (!st.running) g.dialogue.setLanguages(it, st.bLang) }
            LanguageButton("B", st.bLang, Modifier.weight(1f)) { if (!st.running) g.dialogue.setLanguages(st.aLang, it) }
            if (st.running) YellowButton("Stop", Modifier.weight(1f)) { g.dialogue.stop() }
            else YellowButton("Start", Modifier.weight(1f)) { CompanionService.start(ctx); g.dialogue.start() }
        }
    }
}

@Composable
fun InterpretationScreen(g: AppGraph, back: () -> Unit) {
    val st by g.interpretation.state.collectAsState()
    val ctx = LocalContext.current
    Screen("Real-Time Interpretation", back) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LanguageButton("Speaker", st.from, Modifier.weight(1f)) { code -> g.interpretation.configure { it.copy(from = code) } }
            Icon(Icons.Outlined.SwapHoriz, null, tint = Yellow, modifier = Modifier.padding(8.dp))
            LanguageButton("Translate to", st.to, Modifier.weight(1f)) { code -> g.interpretation.configure { it.copy(to = code) } }
        }
        Gap(6)
        Row {
            Chip("Phone mic", st.mic == MicSource.PHONE) { g.interpretation.configure { it.copy(mic = MicSource.PHONE) } }
            Chip("Glasses mic", st.mic == MicSource.GLASSES) { g.interpretation.configure { it.copy(mic = MicSource.GLASSES) } }
        }
        Row {
            Chip("Speak in glasses", st.speakOut && st.output == OutputTarget.GLASSES_SPEAKERS) { g.interpretation.configure { it.copy(speakOut = true, output = OutputTarget.GLASSES_SPEAKERS) } }
            Chip("Speak on phone", st.speakOut && st.output == OutputTarget.PHONE_SPEAKER) { g.interpretation.configure { it.copy(speakOut = true, output = OutputTarget.PHONE_SPEAKER) } }
            Chip("Text only", !st.speakOut) { g.interpretation.configure { it.copy(speakOut = false) } }
        }
        ErrorBanner(st.error)
        LazyColumn(Modifier.weight(1f)) {
            items(st.lines.reversed()) { l ->
                Card(Modifier.padding(vertical = 4.dp)) {
                    Text(l.original, color = Gray, fontSize = 13.sp)
                    Text(l.translated, color = Yellow, fontSize = 17.sp)
                }
            }
        }
        if (st.partial.isNotBlank()) Text("… ${st.partial}", color = Gray)
        Waveform(st.running && !st.paused)
        Row(Modifier.navigationBarsPadding().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (st.running) {
                GhostButton(if (st.paused) "Resume" else "Pause", Modifier.weight(1f)) { g.interpretation.togglePause() }
                YellowButton("Stop", Modifier.weight(1f)) { g.interpretation.stop() }
            } else YellowButton("Start", Modifier.fillMaxWidth()) { CompanionService.start(ctx); g.interpretation.start() }
        }
    }
}

@Suppress("unused")
private val fill = Modifier.fillMaxSize()
