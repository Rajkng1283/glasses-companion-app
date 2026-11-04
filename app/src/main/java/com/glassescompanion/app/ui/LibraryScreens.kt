package com.glassescompanion.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.glassescompanion.app.AppGraph
import com.glassescompanion.app.ble.BleLink
import com.glassescompanion.app.media.MediaType
import com.glassescompanion.app.service.CompanionService
import com.glassescompanion.app.wifi.WifiPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

// ---------------- Reminders ----------------

@Composable
fun RemindersScreen(g: AppGraph, back: () -> Unit) {
    val items by g.reminders.items.collectAsState()
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    Screen("Schedule Reminders", back) {
        Field("What to remind", title) { title = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("+10 min", Modifier.weight(1f)) { g.reminders.add(title, System.currentTimeMillis() + 10 * 60_000); title = "" }
            GhostButton("+1 hour", Modifier.weight(1f)) { g.reminders.add(title, System.currentTimeMillis() + 3_600_000); title = "" }
            YellowButton("Pick time", Modifier.weight(1f)) {
                val c = Calendar.getInstance()
                DatePickerDialog(ctx, { _, y, m, d ->
                    TimePickerDialog(ctx, { _, h, min ->
                        c.set(y, m, d, h, min, 0)
                        g.reminders.add(title, c.timeInMillis)
                        title = ""
                    }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        Gap()
        LazyColumn(Modifier.weight(1f)) {
            if (items.isEmpty()) item { Text("No reminders. You can also ask the assistant: \"remind me at 5 pm\".", color = Gray) }
            items(items.sortedByDescending { it.atMs }, key = { it.id }) { r ->
                Card(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, color = Color.White, fontSize = 15.sp)
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.atMs)), color = Gray, fontSize = 12.sp)
                        }
                        val s = r.status(now)
                        Badge(s, if (s == "In-Progress") Yellow else Gray)
                        IconButton(onClick = { g.reminders.delete(r.id) }) { Icon(Icons.Outlined.Delete, "Delete", tint = Color.White) }
                    }
                }
            }
        }
    }
}

// ---------------- Album ----------------

@Composable
fun AlbumScreen(g: AppGraph, back: () -> Unit, open: (String) -> Unit) {
    val items by g.library.items.collectAsState()
    val imp by g.importer.state.collectAsState()
    val ble by g.ble.state.collectAsState()
    val ctx = LocalContext.current
    var filter by remember { mutableStateOf<MediaType?>(null) }
    Screen("Album", back) {
        Card {
            Text("Import from glasses (Wi-Fi)", color = Color.White)
            Text(imp.message.ifBlank { "Uses BLE 0x39 to open the glasses Wi-Fi, then downloads over 192.168.169.1. Originals stay on the glasses." }, color = Gray, fontSize = 12.sp)
            if (imp.phase == WifiPhase.DOWNLOADING) {
                Text("${imp.currentFile}  (${imp.done}/${imp.remote.size})", color = Yellow, fontSize = 12.sp)
                LinearProgressIndicator(progress = { imp.fileProgress }, modifier = Modifier.fillMaxWidth(), color = Yellow, trackColor = Charcoal2)
            }
            imp.failed.takeLast(3).forEach { Text(it, color = Red, fontSize = 11.sp) }
            Gap(6)
            val busy = imp.phase in listOf(WifiPhase.OPENING_GLASSES_WIFI, WifiPhase.JOINING, WifiPhase.LISTING, WifiPhase.DOWNLOADING)
            if (busy) GhostButton("Cancel", Modifier.fillMaxWidth()) { g.importer.cancel() }
            else YellowButton("Import media", Modifier.fillMaxWidth(), enabled = ble.link == BleLink.READY) { CompanionService.start(ctx); g.importer.start() }
        }
        Row(Modifier.padding(vertical = 8.dp)) {
            Chip("All", filter == null) { filter = null }
            Chip("Photos", filter == MediaType.IMAGE) { filter = MediaType.IMAGE }
            Chip("Videos", filter == MediaType.VIDEO) { filter = MediaType.VIDEO }
            Chip("Audio", filter == MediaType.AUDIO) { filter = MediaType.AUDIO }
        }
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items.filter { filter == null || it.type == filter }, key = { it.id }) { m ->
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Charcoal).clickable { open(m.id) }, contentAlignment = Alignment.Center) {
                    val thumb by produceState<Bitmap?>(null, m.localPath) { value = withContext(Dispatchers.IO) { thumbnail(m.file, m.type) } }
                    thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    when (m.type) {
                        MediaType.VIDEO -> Icon(Icons.Outlined.PlayCircle, null, tint = Color.White)
                        MediaType.AUDIO -> Icon(Icons.Outlined.GraphicEq, null, tint = Yellow)
                        else -> Unit
                    }
                }
            }
        }
    }
}

private fun thumbnail(f: File, type: MediaType): Bitmap? = runCatching {
    when (type) {
        MediaType.IMAGE -> {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / s > 320) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        }
        MediaType.VIDEO -> MediaMetadataRetriever().run {
            try { setDataSource(f.path); getFrameAtTime(0) } finally { release() }
        }
        else -> null
    }
}.getOrNull()

@Composable
fun PlayerScreen(g: AppGraph, id: String, back: () -> Unit) {
    val items by g.library.items.collectAsState()
    val item = items.firstOrNull { it.id == id }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmPhone by remember { mutableStateOf(false) }
    var confirmGlasses by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Screen(item?.fileName ?: "Media", back) {
        if (item == null) {
            Text("Deleted.", color = Gray)
            return@Screen
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (item.type) {
                MediaType.IMAGE -> {
                    val bmp by produceState<Bitmap?>(null, item.localPath) { value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(item.localPath) } }
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
                MediaType.VIDEO, MediaType.AUDIO -> {
                    val player = remember { ExoPlayer.Builder(ctx).build() }
                    DisposableEffect(item.localPath) {
                        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(item.file)))
                        player.prepare()
                        player.playWhenReady = true
                        onDispose { player.release() }
                    }
                    AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxSize())
                }
                else -> Text("Unsupported file", color = Gray)
            }
        }
        Text("${item.source} · ${item.size / 1024} KB${if (item.sha256.isNotBlank()) " · sha256 ${item.sha256.take(12)}…" else ""}", color = Gray, fontSize = 11.sp)
        msg?.let { Text(it, color = Yellow, fontSize = 12.sp) }
        Column(Modifier.navigationBarsPadding().padding(vertical = 10.dp)) {
            GhostButton("Delete phone copy", Modifier.fillMaxWidth()) { confirmPhone = true }
            if (item.source == "glasses") {
                Gap(6)
                GhostButton(
                    if (item.glassesOriginalPresent) "Delete glasses original…" else "Glasses original already cleared",
                    Modifier.fillMaxWidth(), enabled = item.glassesOriginalPresent, color = Red,
                ) { confirmGlasses = true }
            }
        }
    }
    if (confirmPhone && item != null) ConfirmDialog(
        "Delete phone copy?", "Removes ${item.fileName} from this phone only. The original on the glasses is not touched.", "Delete phone copy", true,
        onDismiss = { confirmPhone = false },
    ) { confirmPhone = false; g.library.deletePhoneCopy(item.id); back() }
    if (confirmGlasses && item != null) ConfirmDialog(
        "Delete originals on the glasses?",
        "This sends FILE_DOWNLOAD_FINISH (0x44) to the glasses. The firmware then clears its media storage — this can remove ALL originals on the glasses, not only this file. Phone copies are kept. This cannot be undone.",
        "Delete glasses originals", true,
        onDismiss = { confirmGlasses = false },
    ) {
        confirmGlasses = false
        scope.launch { msg = if (g.importer.deleteGlassesOriginals()) "Glasses told to clear originals (0x44 sent)" else "Glasses not connected — nothing was deleted" }
    }
}

@Suppress("unused")
private val h = Modifier.height(1.dp)
