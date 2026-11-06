package com.nebula.studio.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.studio.SpriteColors
import com.nebula.studio.SpriteSession
import com.nebula.studio.SpriteTool
import com.nebula.studio.Studio
import kotlinx.coroutines.delay
import nebula.core.Argb

private fun bitmapOf(px: IntArray, w: Int, h: Int): ImageBitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * The built-in sprite editor (a wide in-window dialog): draw on a picture made of one or more frames, with onion skinning for animating.
 * One finger uses the selected tool, two fingers pan and zoom. Changes are saved automatically (and when you press Done).
 */
@Composable
fun SpriteEditor(studio: Studio) {
    val s = studio.sprite
    if (s == null) { Text("No picture is open.", color = C.dim); return }
    s.tick
    val doc = s.doc
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(s.path.substringAfterLast('/'), color = C.text, fontSize = 18.sp, maxLines = 1)
                Text("${doc.width} x ${doc.height} px, ${doc.frameCount} frame" + (if (doc.frameCount == 1) "" else "s") + if (s.dirty) "  (saving...)" else "  saved", color = C.dim, fontSize = 11.sp)
            }
            TextBtn("Undo", { s.undo() }, enabled = doc.canUndo)
            TextBtn("Redo", { s.redo() }, enabled = doc.canRedo)
            TextBtn("Done", { studio.closeSprite() }, primary = true)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (maxWidth >= 640.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpriteCanvas(s, Modifier.fillMaxWidth().weight(1f))
                        FramesPanel(s)
                    }
                    Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { ToolsPanel(s) }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpriteCanvas(s, Modifier.fillMaxWidth().weight(1f))
                    FramesPanel(s)
                    Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { ToolsPanel(s) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- canvas
@Composable
private fun SpriteCanvas(s: SpriteSession, modifier: Modifier) {
    val doc = s.doc
    val tick = s.tick
    val w = doc.width; val h = doc.height
    // the picture as drawn now (re-made on every change, also while a stroke is in progress)
    val image = remember(tick) { bitmapOf(s.live, w, h) }
    // onion skin frames: made again only when a frame is committed or the onion settings change
    val ghosts = remember(doc.version, s.onion, s.onionBefore, s.onionAfter, s.onionLoop) {
        if (!s.onion) emptyList() else doc.onion(s.onionBefore, s.onionAfter, s.onionLoop).map { o ->
            val tint = if (o.offset < 0) 0xFFFF4D4D.toInt() else 0xFF4DFF4D.toInt()
            bitmapOf(Argb.ghost(doc.frames[o.index], tint, (o.alpha * 1.6f).coerceAtMost(0.75f)), w, h)
        }
    }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(C.bg)
        .onSizeChanged { s.viewW = it.width.toFloat(); s.viewH = it.height.toFloat(); if (s.zoom == 0f) s.refit() }
        .pointerInput(s) {                                   // mouse wheel zoom
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type == PointerEventType.Scroll && s.zoom > 0f) {
                        val c = ev.changes.first(); val dy = c.scrollDelta.y
                        if (dy != 0f) { s.transform(c.position, if (dy < 0f) 1.15f else 1f / 1.15f, Offset.Zero); c.consume() }
                    }
                }
            }
        }
        .pointerInput(s) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (s.zoom == 0f) return@awaitEachGesture
                var multi = false
                val (sx, sy) = s.pixelAt(down.position)
                s.begin(sx, sy)
                do {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.size >= 2) {                 // two fingers: pan and zoom, and whatever the first finger drew is dropped
                        if (!multi) { multi = true; s.cancel() }
                        s.transform(ev.calculateCentroid(), ev.calculateZoom(), ev.calculatePan())
                        ev.changes.forEach { it.consume() }
                    } else if (!multi) {
                        val c = ev.changes.firstOrNull { it.id == down.id }
                        if (c != null && c.pressed) { val (x, y) = s.pixelAt(c.position); s.move(x, y); c.consume() }
                    }
                } while (ev.changes.any { it.pressed })
                if (!multi) s.end()
            }
        }) {
        if (s.zoom == 0f) return@Canvas
        val z = s.zoom; val ox = s.panX; val oy = s.panY
        val pw = w * z; val ph = h * z
        val x0 = Math.max(ox, 0f); val x1 = Math.min(ox + pw, size.width); val y0 = Math.max(oy, 0f); val y1 = Math.min(oy + ph, size.height)
        if (x1 > x0 && y1 > y0) clipRect(x0, y0, x1, y1) {
            // checkerboard: shows what is see-through
            val cell = 12f
            drawRect(Color(0xFF2A2D4F), Offset(x0, y0), Size(x1 - x0, y1 - y0))
            var j = Math.floor(((y0 - oy) / cell).toDouble()).toInt()
            while (oy + j * cell < y1) {
                var i = Math.floor(((x0 - ox) / cell).toDouble()).toInt()
                while (ox + i * cell < x1) {
                    if ((i + j) % 2 == 0) drawRect(Color(0xFF3A3E66), Offset(ox + i * cell, oy + j * cell), Size(cell, cell))
                    i++
                }
                j++
            }
            translate(ox, oy) {
                scale(z, z, Offset.Zero) {
                    for (g in ghosts) drawImage(g, filterQuality = FilterQuality.None)          // earlier frames red, later frames green
                    drawImage(image, filterQuality = FilterQuality.None)
                }
            }
            if (s.grid && z >= 6f) {
                val gc = Color(0x33FFFFFF)
                for (i in Math.max(0, Math.floor(((x0 - ox) / z).toDouble()).toInt())..Math.min(w, Math.ceil(((x1 - ox) / z).toDouble()).toInt())) drawLine(gc, Offset(ox + i * z, y0), Offset(ox + i * z, y1), 1f)
                for (i in Math.max(0, Math.floor(((y0 - oy) / z).toDouble()).toInt())..Math.min(h, Math.ceil(((y1 - oy) / z).toDouble()).toInt())) drawLine(gc, Offset(x0, oy + i * z), Offset(x1, oy + i * z), 1f)
            }
        }
        drawRect(Color(0x88FFFFFF), Offset(ox, oy), Size(pw, ph), style = Stroke(1f))
    }
}

// ---------------------------------------------------------------------- tools, options, colour
@Composable
private fun ToolsPanel(s: SpriteSession) {
    s.tick
    for (row in SpriteTool.values().toList().chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in row) TextBtn(t.label, { s.tool = t }, Modifier.weight(1f), primary = s.tool == t)
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
    }
    Column {
        Text("Brush size: ${s.size} px", color = C.dim, fontSize = 12.sp)
        Slider(value = s.size.toFloat(), onValueChange = { s.size = Math.round(it).coerceIn(1, 64) }, valueRange = 1f..64f)
    }
    Column {
        Text("Opacity: ${Math.round(s.opacity * 100)}%", color = C.dim, fontSize = 12.sp)
        Slider(value = s.opacity, onValueChange = { s.opacity = it.coerceIn(0.05f, 1f) }, valueRange = 0.05f..1f)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextBtn(if (s.pixel) "Pixel brush" else "Soft brush", { s.pixel = !s.pixel }, Modifier.weight(1f))
        if (s.tool == SpriteTool.Rect || s.tool == SpriteTool.Ellipse) TextBtn(if (s.filled) "Filled" else "Outline", { s.filled = !s.filled }, Modifier.weight(1f))
    }

    // colour: swatch, hex, hue / saturation / brightness, palette, recently used
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF3A3E66)).border(1.dp, C.dim, RoundedCornerShape(10.dp))) { Box(Modifier.fillMaxSize().background(Color(s.color))) }
        Field(Argb.hex(s.color), { v -> Argb.parse(v)?.let { s.setColor(it) } }, Modifier.weight(1f), label = "Colour (hex, #RRGGBB)", mono = true)
    }
    val hsv = s.hsv()
    Column {
        Text("Hue", color = C.dim, fontSize = 11.sp)
        Slider(value = hsv[0], onValueChange = { s.setHsv(it, hsv[1], hsv[2]) }, valueRange = 0f..359f)
        Text("Saturation", color = C.dim, fontSize = 11.sp)
        Slider(value = hsv[1], onValueChange = { s.setHsv(s.hue, it, hsv[2]) }, valueRange = 0f..1f)
        Text("Brightness", color = C.dim, fontSize = 11.sp)
        Slider(value = hsv[2], onValueChange = { s.setHsv(s.hue, hsv[1], it) }, valueRange = 0f..1f)
    }
    Swatches("Palette", SpriteColors.palette, s)
    if (s.recent.isNotEmpty()) Swatches("Recent", s.recent.toList(), s)
    TextBtn(if (s.grid) "Pixel grid: on" else "Pixel grid: off", { s.grid = !s.grid }, Modifier.fillMaxWidth())
    TextBtn("Fit picture to view", { s.refit() }, Modifier.fillMaxWidth())
    Text("One finger draws, two fingers move and zoom.", color = C.dim, fontSize = 11.sp)
}

@Composable
private fun Swatches(title: String, colors: List<Int>, s: SpriteSession) {
    Column {
        Text(title, color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
        for (row in colors.chunked(8)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            for (c in row) Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(c))
                .border(if (c == s.color) 2.dp else 1.dp, if (c == s.color) C.accent2 else C.dim.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .clickable { s.setColor(c) })
        }
    }
}

// ---------------------------------------------------------------------- frames and onion skin
@Composable
private fun FramesPanel(s: SpriteSession) {
    s.tick
    val doc = s.doc
    var playIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.playing, s.fps, doc.frameCount) {
        playIndex = 0
        while (s.playing && doc.frameCount > 1) { delay(1000L / s.fps.coerceIn(1, 60)); playIndex = (playIndex + 1) % doc.frameCount }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f).height(60.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(doc.frames) { i, f ->
                    val bmp = remember(f) { bitmapOf(f, doc.width, doc.height) }
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2D4F))
                        .border(if (i == doc.current) 2.dp else 1.dp, if (i == doc.current) C.accent2 else C.dim.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .clickable { s.edit { it.select(i) } }) {
                        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                            val sc = Math.min(size.width / doc.width, size.height / doc.height)
                            translate((size.width - doc.width * sc) / 2f, (size.height - doc.height * sc) / 2f) { scale(sc, sc, Offset.Zero) { drawImage(bmp, filterQuality = FilterQuality.None) } }
                        }
                        Text("${i + 1}", color = C.text, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 1.dp))
                    }
                }
            }
            if (s.playing && doc.frameCount > 1) {
                val frame = doc.frames[playIndex.coerceIn(0, doc.frameCount - 1)]
                val bmp = remember(frame) { bitmapOf(frame, doc.width, doc.height) }
                Canvas(Modifier.size(60.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2D4F)).border(1.dp, C.ok, RoundedCornerShape(6.dp))) {
                    val sc = Math.min(size.width / doc.width, size.height / doc.height)
                    translate((size.width - doc.width * sc) / 2f, (size.height - doc.height * sc) / 2f) { scale(sc, sc, Offset.Zero) { drawImage(bmp, filterQuality = FilterQuality.None) } }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextBtn("+ Blank", { s.edit { it.addFrame(copy = false) } })
            TextBtn("+ Copy", { s.edit { it.addFrame(copy = true) } })
            TextBtn("Delete", { s.edit { it.deleteFrame() } }, enabled = doc.frameCount > 1)
            TextBtn("< Move", { s.edit { it.moveFrame(-1) } }, enabled = doc.current > 0)
            TextBtn("Move >", { s.edit { it.moveFrame(1) } }, enabled = doc.current < doc.frameCount - 1)
            TextBtn("Clear", { s.edit { it.clearFrame() } })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextBtn(if (s.onion) "Onion skin: on" else "Onion skin: off", { s.onion = !s.onion }, primary = s.onion)
            if (s.onion) {
                TextBtn("Before: ${s.onionBefore}", { s.onionBefore = (s.onionBefore + 1) % 4 })
                TextBtn("After: ${s.onionAfter}", { s.onionAfter = (s.onionAfter + 1) % 4 })
                TextBtn(if (s.onionLoop) "Loops" else "No loop", { s.onionLoop = !s.onionLoop })
            }
            TextBtn(if (s.playing) "Stop" else "Play", { s.playing = !s.playing }, enabled = doc.frameCount > 1)
            TextBtn("${s.fps} fps", { s.fps = when (s.fps) { 4 -> 8; 8 -> 12; 12 -> 24; else -> 4 } })
        }
    }
}

// ---------------------------------------------------------------------- new sprite
/** Size first (buttons or the two number fields), then the name: pressing Done on the keyboard creates it. */
@Composable
fun NewSpriteDialog(studio: Studio) {
    var w by remember { mutableStateOf("32") }
    var h by remember { mutableStateOf("32") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("New sprite", color = C.text, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in listOf(16, 32, 64, 128)) TextBtn("$n x $n", { w = n.toString(); h = n.toString() }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(w, { w = it }, Modifier.weight(1f), label = "Width (px)", number = true)
            Field(h, { h = it }, Modifier.weight(1f), label = "Height (px)", number = true)
        }
        Field("sprite", { name ->
            if (name.isNotBlank()) {
                val ww = w.toDoubleOrNull()?.toInt() ?: 32; val hh = h.toDoubleOrNull()?.toInt() ?: 32
                studio.modal = null; studio.newSprite(name, ww, hh)
            }
        }, label = "Name (press Done on the keyboard to create)")
        TextBtn("Cancel", { studio.modal = null })
    }
}
