package com.nebula.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import nebula.core.SpriteDoc

enum class SpriteTool(val label: String) {
    Brush("Brush"), Eraser("Eraser"), Fill("Fill"), Pick("Eyedropper"), Line("Line"), Rect("Rectangle"), Ellipse("Ellipse")
}

/**
 * The sprite editor's state for one picture: the [SpriteDoc] (pixels, frames, undo) plus what the toolbar has selected and how the canvas
 * is panned and zoomed. The finger handling ([begin] / [move] / [end] / [cancel]) lives here so the composable only forwards positions.
 * [path] is the project path of the PNG it saves to.
 */
class SpriteSession(val path: String, val doc: SpriteDoc, var cols: Int, var rows: Int) {
    /** Bumped on every change (also while a stroke is in progress) so the canvas redraws. */
    var tick by mutableIntStateOf(0)

    var tool by mutableStateOf(SpriteTool.Brush)
    var color by mutableIntStateOf(0xFF000000.toInt())
    var hue by mutableFloatStateOf(0f)
    var size by mutableIntStateOf(1)
    var opacity by mutableFloatStateOf(1f)
    /** Hard-edged pixel brush (true) or soft anti-aliased brush (false). */
    var pixel by mutableStateOf(true)
    var filled by mutableStateOf(false)
    var grid by mutableStateOf(true)
    val recent = mutableStateListOf<Int>()

    var onion by mutableStateOf(false)
    var onionBefore by mutableIntStateOf(1)
    var onionAfter by mutableIntStateOf(0)
    var onionLoop by mutableStateOf(true)
    var playing by mutableStateOf(false)
    var fps by mutableIntStateOf(8)

    /** Pixels on screen per picture pixel; 0 until the canvas has been measured and the picture fitted. */
    var zoom by mutableFloatStateOf(0f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)

    /** [SpriteDoc.version] at the last save (-1 = never saved, so the first save always writes the file). */
    var savedVersion by mutableIntStateOf(-1)
    val dirty get() = doc.version != savedVersion

    private var stroke: SpriteDoc.Stroke? = null
    private var startX = 0; private var startY = 0; private var lastX = 0; private var lastY = 0

    /** What to draw for the current frame: the stroke in progress, or the picture. */
    val live: IntArray get() = stroke?.preview ?: doc.frame

    fun brush() = SpriteDoc.Brush(size, opacity, color, pixel, erase = tool == SpriteTool.Eraser)

    /** (columns, rows) the frames are saved in: the grid the sheet came with while the frame count still fits it, else one row. */
    fun layout(): Pair<Int, Int> = if (doc.frameCount == cols * rows) cols to rows else doc.frameCount to 1

    // ------------------------------------------------------------------ colour
    fun chooseColor(c: Int) {
        color = c
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv)
        if (hsv[1] > 0.01f && hsv[2] > 0.01f) hue = hsv[0]
    }
    fun setHsv(h: Float, s: Float, v: Float) {
        hue = h
        color = (android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 359.9f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))) and 0xFFFFFF) or (color and 0xFF000000.toInt())
    }
    fun hsv(): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it); it[0] = hue }
    private fun rememberColor(c: Int) { recent.remove(c); recent.add(0, c); while (recent.size > 12) recent.removeAt(recent.size - 1) }

    // ------------------------------------------------------------------ view
    /** Size of the canvas on screen, set by the composable. */
    var viewW = 0f
    var viewH = 0f
    fun refit() { if (viewW > 0f && viewH > 0f) fit(viewW, viewH) }

    fun fit(viewW: Float, viewH: Float) {
        val z = Math.min(viewW / doc.width, viewH / doc.height) * 0.92f
        zoom = if (z >= 2f) Math.floor(z.toDouble()).toFloat() else z.coerceAtLeast(0.25f)
        panX = (viewW - doc.width * zoom) / 2f; panY = (viewH - doc.height * zoom) / 2f
    }

    /** Two-finger gesture: zoom about [centroid] and move by [pan]. */
    fun transform(centroid: Offset, zoomChange: Float, pan: Offset) {
        val nz = (zoom * zoomChange).coerceIn(0.25f, 96f)
        val px = (centroid.x - panX) / zoom; val py = (centroid.y - panY) / zoom       // the picture point under the fingers stays there
        panX = centroid.x - px * nz + pan.x; panY = centroid.y - py * nz + pan.y; zoom = nz
    }

    fun pixelAt(p: Offset): Pair<Int, Int> = Math.floor(((p.x - panX) / zoom).toDouble()).toInt() to Math.floor(((p.y - panY) / zoom).toDouble()).toInt()

    // ------------------------------------------------------------------ finger
    private fun cx(x: Int) = x.coerceIn(0, doc.width - 1)
    private fun cy(y: Int) = y.coerceIn(0, doc.height - 1)

    private fun pickAt(x: Int, y: Int) { val c = doc.pick(x, y) ?: return; if (SpriteColors.alpha(c) > 0) chooseColor(c) }

    private fun shape(x: Int, y: Int) {
        val s = stroke ?: return
        val sx = cx(startX); val sy = cy(startY); val ex = cx(x); val ey = cy(y)
        when (tool) {
            SpriteTool.Line -> s.line(sx, sy, ex, ey)
            SpriteTool.Rect -> s.rect(sx, sy, ex, ey, filled)
            else -> s.ellipse(sx, sy, ex, ey, filled)
        }
    }

    fun begin(x: Int, y: Int) {
        startX = x; startY = y; lastX = x; lastY = y
        when (tool) {
            SpriteTool.Brush, SpriteTool.Eraser -> stroke = doc.stroke(brush()).also { it.dab(x, y) }
            SpriteTool.Line, SpriteTool.Rect, SpriteTool.Ellipse -> { stroke = doc.stroke(brush()); shape(x, y) }
            SpriteTool.Pick -> pickAt(x, y)
            SpriteTool.Fill -> {}
        }
        tick++
    }

    fun move(x: Int, y: Int) {
        if (x == lastX && y == lastY) return
        when (tool) {
            SpriteTool.Brush, SpriteTool.Eraser -> stroke?.line(lastX, lastY, x, y)
            SpriteTool.Line, SpriteTool.Rect, SpriteTool.Ellipse -> { stroke?.reset(); shape(x, y) }
            SpriteTool.Pick -> pickAt(x, y)
            SpriteTool.Fill -> {}
        }
        lastX = x; lastY = y; tick++
    }

    /** The finger lifted: the stroke or shape is committed (one undo step), a fill is poured where the finger was. */
    fun end() {
        when (tool) {
            SpriteTool.Fill -> if (doc.fill(lastX, lastY, color, opacity)) rememberColor(color)
            SpriteTool.Pick -> {}
            SpriteTool.Eraser -> stroke?.commit()
            else -> if (stroke?.commit() == true) rememberColor(color)
        }
        stroke = null; tick++
    }

    /** A second finger came down (pan / zoom): whatever the first finger was drawing is dropped. */
    fun cancel() { stroke = null; tick++ }

    fun undo() { cancel(); doc.undo() }
    fun redo() { cancel(); doc.redo() }
    /** Run an edit of the picture (frames, clear) and redraw. */
    fun edit(op: (SpriteDoc) -> Unit) { cancel(); op(doc); tick++ }
}

object SpriteColors {
    fun alpha(c: Int) = c ushr 24
    /** The 16 colours of the PICO-8 palette: a handy default for pixel art. */
    val palette = listOf(
        0xFF000000, 0xFF1D2B53, 0xFF7E2553, 0xFF008751, 0xFFAB5236, 0xFF5F574F, 0xFFC2C3C7, 0xFFFFF1E8,
        0xFFFF004D, 0xFFFFA300, 0xFFFFEC27, 0xFF00E436, 0xFF29ADFF, 0xFF83769C, 0xFFFF77A8, 0xFFFFCCAA,
    ).map { it.toInt() }
}
