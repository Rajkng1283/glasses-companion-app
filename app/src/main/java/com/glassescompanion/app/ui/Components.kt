package com.glassescompanion.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassescompanion.app.lang.Language
import com.glassescompanion.app.lang.LanguageRegistry
import kotlin.math.abs
import kotlin.math.sin

val Black = Color(0xFF0A0A0A)
val Charcoal = Color(0xFF1C1C1E)
val Charcoal2 = Color(0xFF2C2C2E)
val Yellow = Color(0xFFFFD60A)
val Gray = Color(0xFF8E8E93)
val Green = Color(0xFF30D158)
val Red = Color(0xFFFF453A)

private val scheme: ColorScheme = darkColorScheme(
    primary = Yellow, onPrimary = Black, background = Black, onBackground = Color.White,
    surface = Charcoal, onSurface = Color.White, surfaceVariant = Charcoal2, onSurfaceVariant = Gray,
    secondary = Yellow, onSecondary = Black, error = Red,
)

@Composable
fun GlassesTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

@Composable
fun Screen(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
            } else Spacer(Modifier.width(12.dp))
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), content = content)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Charcoal)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun YellowButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Black, disabledContainerColor = Charcoal2, disabledContentColor = Gray),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Color.White, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.6f) else Charcoal2),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) { Text(text) }
}

@Composable
fun OutlineIcon(icon: ImageVector, label: String, size: Int = 26, tint: Color = Color.White) {
    Box(Modifier.size((size + 20).dp).border(1.dp, tint.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(size.dp))
    }
}

@Composable
fun StatusRow(label: String, value: String, ok: Boolean?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(when (ok) { true -> Green; false -> Red; null -> Gray }))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Gray, fontSize = 13.sp, modifier = Modifier.width(120.dp))
        Text(value, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Gray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
fun ErrorBanner(text: String?) {
    if (text.isNullOrBlank()) return
    Text(
        text, color = Color.White, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(Red.copy(alpha = 0.25f)).padding(10.dp),
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Black else Color.White, fontSize = 13.sp,
        modifier = Modifier.padding(end = 8.dp, bottom = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) Yellow else Charcoal2).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Gray, fontSize = 12.sp)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Black, checkedTrackColor = Yellow))
    }
}

@Composable
fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Yellow, focusedLabelColor = Yellow, cursorColor = Yellow, unfocusedTextColor = Color.White, focusedTextColor = Color.White),
    )
}

/** Yellow waveform; animates while [active], scaled by [level] (0..1). */
@Composable
fun Waveform(active: Boolean, level: Float = 0.5f, modifier: Modifier = Modifier.fillMaxWidth().height(48.dp)) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "phase")
    Canvas(modifier) {
        val bars = 36
        val w = size.width / (bars * 1.6f)
        for (i in 0 until bars) {
            val amp = if (active) (0.25f + 0.75f * abs(sin(phase + i * 0.45f))) * (0.35f + 0.65f * level.coerceIn(0f, 1f)) else 0.08f
            val h = size.height * amp
            drawRoundRect(
                Yellow.copy(alpha = if (active) 1f else 0.35f),
                topLeft = Offset(i * w * 1.6f, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

@Composable
fun LanguageButton(label: String, code: String, modifier: Modifier = Modifier, onPicked: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val lang = LanguageRegistry.require(code)
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Charcoal2).clickable { open = true }.padding(10.dp)) {
        Text(label, color = Gray, fontSize = 11.sp)
        Text(lang.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (lang.native != lang.name) Text(lang.native, color = Yellow, fontSize = 12.sp, maxLines = 1)
    }
    if (open) LanguagePickerDialog(onDismiss = { open = false }) { open = false; onPicked(it.code) }
}

@Composable
fun LanguagePickerDialog(onDismiss: () -> Unit, onPick: (Language) -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Charcoal,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Yellow) } },
        title = { Text("${LanguageRegistry.all.size} languages", color = Color.White) },
        text = {
            Column {
                OutlinedTextField(
                    q, { q = it }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Yellow, cursorColor = Yellow, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                )
                val list = LanguageRegistry.all.filter { q.isBlank() || it.name.contains(q, true) || it.native.contains(q, true) || it.code.contains(q, true) }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.code }) { l ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(l) }.padding(vertical = 10.dp)) {
                            Text(l.name, color = Color.White, modifier = Modifier.weight(1f))
                            Text(l.native, color = Gray)
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, destructive: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Charcoal,
        title = { Text(title, color = Color.White) },
        text = { Text(text, color = Gray) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (destructive) Red else Yellow) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )
}

@Composable
fun Badge(text: String, color: Color = Yellow) {
    Text(text, color = Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun Gap(h: Int = 10) = Spacer(Modifier.height(h.dp))

@Suppress("unused")
val spaced = Arrangement.spacedBy(10.dp)
