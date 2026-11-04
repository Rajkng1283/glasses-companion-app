package com.glassescompanion.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartScreen
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassescompanion.app.AppGraph
import com.glassescompanion.app.ble.BleLink
import com.glassescompanion.app.ble.EyevueBleProtocol.Cmd
import kotlinx.coroutines.launch

data class Tool(val title: String, val sub: String, val icon: ImageVector, val route: String)

val TOOLS = listOf(
    Tool("Dialogue Translation", "Two-way · 100 languages", Icons.Outlined.Forum, Routes.DIALOGUE),
    Tool("Real-Time Interpretation", "Continuous one-way", Icons.Outlined.Hearing, Routes.INTERPRET),
    Tool("Glasses + Phone", "Face-to-face captions", Icons.Outlined.SmartScreen, Routes.GLASSES_PHONE),
    Tool("Call Translation", "Audio / video call", Icons.Outlined.VideoCall, Routes.CALL),
    Tool("Photo Translation", "OCR + translate", Icons.Outlined.CameraAlt, Routes.PHOTO),
    Tool("AI Assistant", "English · हिन्दी · Hinglish", Icons.Outlined.AutoAwesome, Routes.ASSISTANT),
    Tool("AI Recording", "Record · transcribe · summary", Icons.Outlined.Mic, Routes.RECORDING),
    Tool("Schedule Reminders", "In-Progress / Expired", Icons.Outlined.Alarm, Routes.REMINDERS),
    Tool("Album", "Glasses media", Icons.Outlined.PhotoLibrary, Routes.ALBUM),
    Tool("Control Guide", "Buttons & touchpad", Icons.Outlined.TouchApp, Routes.GUIDE),
    Tool("Settings", "Cloud, audio, video", Icons.Outlined.Settings, Routes.SETTINGS),
)

@Composable
fun HomeScreen(g: AppGraph, nav: (String) -> Unit) {
    val ble by g.ble.state.collectAsState()
    val audio by g.audio.state.collectAsState()
    val wifi by g.importer.state.collectAsState()
    LaunchedEffect(Unit) {
        g.audio.probe()
        g.ble.connectLast()
    }
    Screen("Glasses Companion", onBack = null) {
        LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(span = { GridItemSpan(2) }) {
                Card(onClick = { nav(Routes.DEVICE) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ble.info.name.ifBlank { g.settings.current.lastDeviceName.ifBlank { "No glasses bound" } }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("G6-208B / TK80201", color = Gray, fontSize = 12.sp)
                        }
                        ble.info.battery?.let { Badge("$it%${if (ble.info.charging) " ⚡" else ""}") }
                    }
                    Gap(8)
                    StatusRow("BLE control", ble.link.name.lowercase().replaceFirstChar { it.uppercase() }, ble.link == BleLink.READY)
                    StatusRow("Glasses mic", if (audio.glassesMicScoAvailable) "HFP ${audio.glassesMicName}" else "Not connected", audio.glassesMicScoAvailable)
                    StatusRow("Glasses speakers", when {
                        audio.glassesSpeakerA2dp -> "A2DP ${audio.glassesSpeakerName}"
                        audio.glassesSpeakerSco -> "HFP ${audio.glassesSpeakerName}"
                        else -> "Not connected"
                    }, audio.glassesSpeakerA2dp || audio.glassesSpeakerSco)
                    StatusRow("Wi-Fi media", if (wifi.wifiConnected) "Joined ${wifi.ssid}" else wifi.phase.name.lowercase(), if (wifi.wifiConnected) true else null)
                }
            }
            items(TOOLS) { t ->
                Card(onClick = { nav(t.route) }) {
                    OutlineIcon(t.icon, t.title, 22, Yellow)
                    Gap(8)
                    Text(t.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(t.sub, color = Gray, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun DeviceScreen(g: AppGraph, back: () -> Unit) {
    val ble by g.ble.state.collectAsState()
    val scan by g.ble.scanResults.collectAsState()
    val audio by g.audio.state.collectAsState()
    val log by g.ble.log.collectAsState()
    val scope = rememberCoroutineScope()
    Screen("Glasses", back) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Card {
                StatusRow("BLE", "${ble.link} · ${ble.message}", ble.link == BleLink.READY)
                StatusRow("Address", ble.info.address.ifBlank { "—" }, null)
                StatusRow("Battery", ble.info.battery?.let { "$it%" } ?: "—", null)
                StatusRow("Firmware", ble.info.firmware.ifBlank { "—" }, null)
                StatusRow("PID/VID", ble.info.pidVid.ifBlank { "—" }, null)
                StatusRow("Capacity", ble.info.capacity.ifBlank { "—" }, null)
                StatusRow("Functions", ble.info.supportMask.ifBlank { "—" }, null)
                StatusRow("MTU", ble.mtu.toString(), null)
            }
            Gap()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YellowButton("Scan", Modifier.weight(1f)) { g.ble.startScan() }
                GhostButton("Disconnect", Modifier.weight(1f), enabled = ble.link == BleLink.READY) { g.ble.disconnect() }
                GhostButton("Forget", Modifier.weight(1f), color = Red) { g.ble.forget() }
            }
            if (!g.ble.bluetoothEnabled) ErrorBanner("Bluetooth is off.")
            SectionTitle("Nearby")
            if (scan.isEmpty()) Text(if (ble.link == BleLink.SCANNING) "Scanning…" else "Press Scan. Put the glasses in pairing mode.", color = Gray, fontSize = 13.sp)
            scan.forEach { d ->
                Row(Modifier.fillMaxWidth().clickable { g.ble.connect(d.address, d.name) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = Color.White)
                        Text("${d.address} · ${d.rssi} dBm", color = Gray, fontSize = 12.sp)
                    }
                    if (d.advertisesAa12 || g.ble.looksLikeGlasses(d.name)) Badge("Glasses")
                }
            }
            SectionTitle("Classic Bluetooth audio (separate from BLE)")
            Card {
                StatusRow("HFP mic", if (audio.glassesMicScoAvailable) audio.glassesMicName else "Not connected", audio.glassesMicScoAvailable)
                StatusRow("A2DP speakers", if (audio.glassesSpeakerA2dp) audio.glassesSpeakerName else "Not connected", audio.glassesSpeakerA2dp)
                StatusRow("Phone mic", if (audio.phoneMic) "Available" else "Missing", audio.phoneMic)
                StatusRow("Phone speaker", if (audio.phoneSpeaker) "Available" else "Missing", audio.phoneSpeaker)
                if (!audio.glassesAudioConnected) Text("Pair the glasses in Android Bluetooth settings as an audio device too; BLE alone does not carry the mic or speakers.", color = Gray, fontSize = 12.sp)
            }
            SectionTitle("Commands")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Photo 0x22", Modifier.weight(1f), enabled = ble.link == BleLink.READY) { scope.launch { g.ble.send(Cmd.TAKE_PHOTO) } }
                GhostButton("Battery", Modifier.weight(1f), enabled = ble.link == BleLink.READY) { g.ble.refreshBattery() }
                GhostButton("Info", Modifier.weight(1f), enabled = ble.link == BleLink.READY) { scope.launch { g.ble.send(Cmd.GET_DEVICE_INFO); g.ble.send(Cmd.GET_SUPPORT_FUNCTION) } }
            }
            SectionTitle("BLE log")
            Card {
                LazyColumn(Modifier.height(220.dp)) {
                    items(log.reversed()) { Text(it, color = Gray, fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(24.dp).width(1.dp))
        }
    }
}
