package com.nebula.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** All colours in one place. */
object C {
    val bg = Color(0xFF0F1020)
    val panel = Color(0xFF16182E)
    val panel2 = Color(0xFF1D2040)
    val line = Color(0xFF2B2F57)
    val accent = Color(0xFF7C4DFF)
    val accent2 = Color(0xFFB388FF)
    val text = Color(0xFFE8EAF6)
    val dim = Color(0xFF9FA3C7)
    val ok = Color(0xFF66BB6A)
    val warn = Color(0xFFFFB74D)
    val err = Color(0xFFEF5350)
    val sel = Color(0x447C4DFF)
}

@Composable
fun NebulaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = C.accent, onPrimary = Color.White, secondary = C.accent2, background = C.bg,
            surface = C.panel, onSurface = C.text, onBackground = C.text, surfaceVariant = C.panel2, onSurfaceVariant = C.dim, outline = C.line),
        content = content,
    )
}

val Mono = FontFamily.Monospace

@Composable
fun IconBtn(icon: ImageVector, desc: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = C.text, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (enabled) tint else C.dim.copy(alpha = 0.4f), modifier = Modifier.size(22.dp))
    }
}

@Composable
fun TextBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    val bg = if (primary) C.accent else C.panel2
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(if (enabled) bg else bg.copy(alpha = 0.4f))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) C.text else C.dim, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().height(40.dp).background(C.panel2).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = C.dim, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun Chip(text: String, color: Color = C.accent, modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp).clip(CircleShape).background(color.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 11.sp)
    }
}

/** How many [Field]s hold the keyboard focus right now. Node shortcuts (copy, cut, paste, delete) stand aside while one does, so they never steal a text field's own keys. */
object TextFocus { var count = 0 }

/** Dense single-line text input. Commits on Done / focus loss (so typing makes one undo step, not one per key). */
@Composable
fun Field(value: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null,
          number: Boolean = false, mono: Boolean = false, placeholder: String = "", onChange: ((String) -> Unit)? = null) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (focused) TextFocus.count = maxOf(0, TextFocus.count - 1) } }
    Column(modifier) {
        if (label != null) Text(label, color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
        BasicTextField(
            value = text, onValueChange = { text = it; onChange?.invoke(it) }, singleLine = true,
            textStyle = TextStyle(color = C.text, fontSize = 14.sp, fontFamily = if (mono) Mono else FontFamily.Default),
            cursorBrush = SolidColor(C.accent2),
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                if (focused && !it.isFocused && text != value) onCommit(text)
                if (it.isFocused != focused) TextFocus.count = maxOf(0, TextFocus.count + if (it.isFocused) 1 else -1)
                focused = it.isFocused
            },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).background(C.bg)
                    .padding(horizontal = 10.dp, vertical = 9.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = C.dim.copy(alpha = 0.6f), fontSize = 14.sp)
                    inner()
                }
            })
    }
}

fun fmtNum(d: Double): String = if (d == Math.floor(d) && Math.abs(d) < 1e12) d.toLong().toString() else "%.3f".format(d).trimEnd('0').trimEnd('.')
