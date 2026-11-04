package com.nebula.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.studio.Studio
import com.nebula.studio.runtime.Renderer

/**
 * The built-in animation manager: pick a sprite sheet, tell it how many columns and rows it has, then define clips
 * (first frame, last frame, speed, loop). Tap the sheet to choose a clip's frames. Frames are numbered from 1.
 */
@Composable
fun AnimationManager(studio: Studio) {
    val doc = studio.animDoc
    if (doc == null) { Text("No animation set is open.", color = C.dim); return }
    val set = doc.set
    val bmp = studio.images?.get(set.sheet)
    val clip = set.clips.getOrNull(doc.selected)
    var firstTap by remember { mutableStateOf(true) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Animations: " + doc.path.substringAfterLast('/'), color = C.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextBtn("Done", { studio.closeAnimation() }, primary = true)
        }

        TextBtn(if (set.sheet.isEmpty()) "Choose sprite sheet..." else set.sheet, { studio.pickSheet() }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Field(set.cols.toString(), { v -> v.toIntOrNull()?.let { doc.edit(cols = it) } }, Modifier.weight(1f), label = "Columns", number = true)
            Field(set.rows.toString(), { v -> v.toIntOrNull()?.let { doc.edit(rows = it) } }, Modifier.weight(1f), label = "Rows", number = true)
            Text(if (bmp != null) "Frame: ${bmp.width / set.cols} x ${bmp.height / set.rows} px" else "", color = C.dim, fontSize = 12.sp,
                modifier = Modifier.weight(1.4f).padding(bottom = 12.dp))
        }

        if (bmp != null) {
            val ratio = bmp.width.toFloat() / bmp.height.toFloat()
            Canvas(Modifier.height(220.dp).aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .pointerInput(set.cols, set.rows, doc.selected) {
                    detectTapGestures { o ->
                        if (clip == null) return@detectTapGestures
                        val col = (o.x / size.width * set.cols).toInt().coerceIn(0, set.cols - 1)
                        val row = (o.y / size.height * set.rows).toInt().coerceIn(0, set.rows - 1)
                        val frame = row * set.cols + col + 1
                        if (firstTap) doc.updateClip(doc.selected, from = frame, to = frame) else doc.updateClip(doc.selected, to = frame)
                        firstTap = !firstTap
                    }
                }) {
                drawImage(bmp, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                val cw = size.width / set.cols; val ch = size.height / set.rows
                if (clip != null) for (f in minOf(clip.from, clip.to)..maxOf(clip.from, clip.to)) {
                    val i = f - 1
                    drawRect(Color(0x667C4DFF), Offset((i % set.cols) * cw, (i / set.cols) * ch), Size(cw, ch))
                }
                for (c in 0..set.cols) drawLine(Color(0x88FFFFFF), Offset(c * cw, 0f), Offset(c * cw, size.height), 1f)
                for (r in 0..set.rows) drawLine(Color(0x88FFFFFF), Offset(0f, r * ch), Offset(size.width, r * ch), 1f)
                if (cw >= 22f && ch >= 16f) with(Renderer) {
                    for (i in 0 until set.cols * set.rows) text((i + 1).toString(), (i % set.cols) * cw + 11f, (i / set.cols) * ch + 10f, 11f, Color.White, true)
                }
            }
        } else if (set.sheet.isNotEmpty()) Text("Cannot read ${set.sheet}.", color = C.err, fontSize = 13.sp)

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            set.clips.forEachIndexed { i, c -> TextBtn(c.name, { doc.selected = i; firstTap = true }, primary = i == doc.selected) }
            TextBtn("+ Clip", { doc.addClip(); firstTap = true })
        }

        if (clip == null) {
            Text("Add a clip, then tap the sheet: the first tap sets the clip's first frame, the second tap its last frame.", color = C.dim, fontSize = 12.sp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(clip.name, { doc.updateClip(doc.selected, name = it) }, Modifier.weight(2f), label = "Clip name", mono = true)
                Field(clip.from.toString(), { v -> v.toIntOrNull()?.let { doc.updateClip(doc.selected, from = it) } }, Modifier.weight(1f), label = "First", number = true)
                Field(clip.to.toString(), { v -> v.toIntOrNull()?.let { doc.updateClip(doc.selected, to = it) } }, Modifier.weight(1f), label = "Last", number = true)
                Field(fmtNum(clip.fps), { v -> v.toDoubleOrNull()?.let { doc.updateClip(doc.selected, fps = it) } }, Modifier.weight(1f), label = "FPS", number = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Loop", color = C.text, fontSize = 14.sp)
                Switch(clip.loop, { doc.updateClip(doc.selected, loop = it) }, colors = SwitchDefaults.colors(checkedTrackColor = C.accent))
                Spacer(Modifier.weight(1f))
                TextBtn("Delete clip", { doc.removeClip(doc.selected); firstTap = true })
            }
            ClipPreview(set, clip, bmp)
        }
        Text("Use it: give a Sprite this animation set and a clip in the Inspector, or call animate(\"${clip?.name ?: "run"}\") from a script.",
            color = C.dim, fontSize = 12.sp)
    }
}

/** Plays the clip in a small box, restarting a non-looping clip after a short pause. */
@Composable
private fun ClipPreview(set: nebula.core.AnimSet, clip: nebula.core.Clip, bmp: androidx.compose.ui.graphics.ImageBitmap?) {
    var time by remember { mutableStateOf(0.0) }
    LaunchedEffect(clip) {
        var last = 0L; time = 0.0
        while (true) withFrameNanos { n -> if (last != 0L) time += (n - last) / 1e9; last = n }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(120.dp)) {
            drawRect(Color(0xFF101820))
            if (bmp == null) return@Canvas
            val length = clip.count / maxOf(clip.fps, 0.001)
            val t = if (clip.loop) time else time % (length + 0.6)
            val f = (clip.frameAt(t).first - 1).coerceIn(0, set.frameCount - 1)
            val cw = (bmp.width / set.cols).coerceAtLeast(1); val ch = (bmp.height / set.rows).coerceAtLeast(1)
            val k = minOf(size.width / cw, size.height / ch)
            val dw = (cw * k).toInt(); val dh = (ch * k).toInt()
            drawImage(bmp, srcOffset = IntOffset((f % set.cols) * cw, (f / set.cols) * ch), srcSize = IntSize(cw, ch),
                dstOffset = IntOffset(((size.width - dw) / 2f).toInt(), ((size.height - dh) / 2f).toInt()), dstSize = IntSize(dw, dh))
            drawRect(Color(0x33FFFFFF), style = Stroke(1f))
        }
        Text("${clip.count} frame(s) at ${fmtNum(clip.fps)} fps\n" + (if (clip.loop) "loops" else "plays once, then sends animationFinished"),
            color = C.dim, fontSize = 12.sp)
    }
}
