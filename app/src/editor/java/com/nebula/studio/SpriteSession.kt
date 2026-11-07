package com.nebula.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import nebula.core.FrameDoc
import nebula.core.SpriteDoc
import nebula.core.VShape
import nebula.core.VectorDoc

enum class SpriteTool(val label: String) {
    Brush("Brush"), Eraser("Eraser"), Fill("Fill"), Pick("Eyedropper"), Line("Line"), Rect("Rectangle"), Ellipse("Ellipse")
}

/**
 * The sprite editor's state for one picture: the document (a [SpriteDoc] of pixels or a [VectorDoc] of shapes, never both) plus what the
 * toolbar has selected and how the canvas is panned and zoomed. The finger handling ([begin] / [move] / [end] / [cancel]) lives here so the
 * composable only forwards positions. [path] is the project path of the PNG it saves to (a vector sprite also saves a `.nvec` next to it).
 */
class SpriteSession(val path: String, val doc: FrameDoc, var cols: Int, var rows: Int) {
    /** The pixel picture, or null for a vector sprite. */
    val pixels: SpriteDoc? get() = doc as? SpriteDoc
    /** The vector picture, or null for a pixel sprite. */
    val vector: VectorDoc? get() = doc as? VectorDoc
    val isVector: Boolean get() = doc is VectorDoc

    /** Bumped on every change (also while a stroke is in progress) so the canvas redraws. */
    var tick by mutableIntStateOf(0)

    var tool by mutableStateOf(SpriteTool.Brush)
    var color by mutableIntStateOf(0xFF000000.toInt())
    var hue by mutableFloatStateOf(0f)
    /** Brush size in pixels; for a vector sprite it is the line width. */
    var size by mutableIntStateOf(1)
    var opacity by mutableFloatStateOf(1f)
    /** Hard-edged pixel brush (true) or soft anti-aliased brush (false). Pixel sprites only. */
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

    /** [FrameDoc.version] at the last save (-1 = never saved, so the first save always writes the file). */
    var savedVersion by mutableIntStateOf(-1)
    val dirty get() = doc.version != savedVersion

    // pixel sprites: the stroke in progress
    private var stroke: SpriteDoc.Stroke? = null
    private var startX = 0; private var startY = 0; private var lastX = 0; private var lastY = 0
    private val px: SpriteDoc get() = doc as SpriteDoc

    // vector sprites: the shape being drawn, and the shapes the eraser is passing over (removed when the finger lifts)
    var draft by mutableStateOf<VShape?>(null)
    var erasing by mutableStateOf<Set<Int>>(emptySet())
    private val vPts = ArrayList<Float>()
    private var vx0 = 0f; private var vy0 = 0f; private var vx1 = 0f; private var vy1 = 0f
    private var rawX = 0f; private var rawY = 0f

    /** What to draw for the current frame of a pixel sprite: the stroke in progress, or the picture. */
    val live: IntArray get() = stroke?.preview ?: (pixels?.frame ?: IntArray(0))

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

    // ------------------------------------------------------------------ finger (the composable passes screen positions)
    fun begin(p: Offset) {
        if (isVector) vBegin((p.x - panX) / zoom, (p.y - panY) / zoom) else { val (x, y) = pixelAt(p); pBegin(x, y) }
    }

    fun move(p: Offset) {
        if (isVector) vMove((p.x - panX) / zoom, (p.y - panY) / zoom) else { val (x, y) = pixelAt(p); pMove(x, y) }
    }

    /** The finger lifted: the stroke or shape is committed (one undo step), a fill is poured where the finger was, the eraser removes what it touched. */
    fun end() { if (isVector) vEnd() else pEnd() }

    /** A second finger came down (pan / zoom): whatever the first finger was drawing is dropped. */
    fun cancel() { stroke = null; draft = null; erasing = emptySet(); vPts.clear(); tick++ }

    fun undo() { cancel(); doc.undo() }
    fun redo() { cancel(); doc.redo() }
    /** Run an edit of the picture (frames, clear) and redraw. */
    fun edit(op: (FrameDoc) -> Unit) { cancel(); op(doc); tick++ }

    // ------------------------------------------------------------------ pixel sprites
    private fun cx(x: Int) = x.coerceIn(0, doc.width - 1)
    private fun cy(y: Int) = y.coerceIn(0, doc.height - 1)

    private fun pickAt(x: Int, y: Int) { val c = pixels?.pick(x, y) ?: return; if (SpriteColors.alpha(c) > 0) chooseColor(c) }

    private fun shape(x: Int, y: Int) {
        val s = stroke ?: return
        val sx = cx(startX); val sy = cy(startY); val ex = cx(x); val ey = cy(y)
        when (tool) {
            SpriteTool.Line -> s.line(sx, sy, ex, ey)
            SpriteTool.Rect -> s.rect(sx, sy, ex, ey, filled)
            else -> s.ellipse(sx, sy, ex, ey, filled)
        }
    }

    private fun pBegin(x: Int, y: Int) {
        startX = x; startY = y; lastX = x; lastY = y
        when (tool) {
            SpriteTool.Brush, SpriteTool.Eraser -> stroke = px.stroke(brush()).also { it.dab(x, y) }
            SpriteTool.Line, SpriteTool.Rect, SpriteTool.Ellipse -> { stroke = px.stroke(brush()); shape(x, y) }
            SpriteTool.Pick -> pickAt(x, y)
            SpriteTool.Fill -> {}
        }
        tick++
    }

    private fun pMove(x: Int, y: Int) {
        if (x == lastX && y == lastY) return
        when (tool) {
            SpriteTool.Brush, SpriteTool.Eraser -> stroke?.line(lastX, lastY, x, y)
            SpriteTool.Line, SpriteTool.Rect, SpriteTool.Ellipse -> { stroke?.reset(); shape(x, y) }
            SpriteTool.Pick -> pickAt(x, y)
            SpriteTool.Fill -> {}
        }
        lastX = x; lastY = y; tick++
    }

    private fun pEnd() {
        when (tool) {
            SpriteTool.Fill -> if (px.fill(lastX, lastY, color, opacity)) rememberColor(color)
            SpriteTool.Pick -> {}
            SpriteTool.Eraser -> stroke?.commit()
            else -> if (stroke?.commit() == true) rememberColor(color)
        }
        stroke = null; tick++
    }

    // ------------------------------------------------------------------ vector sprites (positions are in picture units, not pixels)
    private fun ink() = VectorDoc.tint(color, opacity)
    private fun eraseTol() = maxOf(size / 2f, 1f)

    private fun pathShape() = VShape(VShape.Kind.Path, vPts.toFloatArray(), ink(), 0, size.toFloat())

    /** The line, rectangle or ellipse from the point where the finger went down to where it is now. */
    private fun boxShape(): VShape = when (tool) {
        SpriteTool.Line -> VShape(VShape.Kind.Line, floatArrayOf(vx0, vy0, vx1, vy1), ink(), 0, size.toFloat())
        else -> {
            val kind = if (tool == SpriteTool.Rect) VShape.Kind.Rect else VShape.Kind.Ellipse
            val box = floatArrayOf(minOf(vx0, vx1), minOf(vy0, vy1), maxOf(vx0, vx1), maxOf(vy0, vy1))
            if (filled) VShape(kind, box, 0, ink(), size.toFloat()) else VShape(kind, box, ink(), 0, size.toFloat())
        }
    }

    private fun vPick(x: Float, y: Float) { val c = vector?.colorAt(x, y) ?: return; if (SpriteColors.alpha(c) > 0) chooseColor(c) }

    private fun vBegin(x: Float, y: Float) {
        val d = vector ?: return
        val cx = x.coerceIn(0f, d.width.toFloat()); val cy = y.coerceIn(0f, d.height.toFloat())
        vx0 = cx; vy0 = cy; vx1 = cx; vy1 = cy; rawX = x; rawY = y
        vPts.clear()
        when (tool) {
            SpriteTool.Brush -> { vPts.add(cx); vPts.add(cy); draft = pathShape() }
            SpriteTool.Line, SpriteTool.Rect, SpriteTool.Ellipse -> draft = boxShape()
            SpriteTool.Eraser -> erasing = d.hitAll(x, y, eraseTol()).toSet()
            SpriteTool.Pick -> vPick(x, y)
            SpriteTool.Fill -> {}
        }
        tick++
    }

    private fun vMove(x: Float, y: Float) {
        val d = vector ?: return
        val cx = x.coerceIn(0f, d.width.toFloat()); val cy = y.coerceIn(0f, d.height.toFloat())
        when (tool) {
            SpriteTool.Brush -> if (vPts.size >= 2) {
                val step = maxOf(0.3f, 1.5f / maxOf(zoom, 0.1f))           // about 1.5 screen pixels between points
                val dx = cx - vPts[vPts.size - 2]; val dy = cy - vPts[vPts.size - 1]
                if (dx * dx + dy * dy >= step * step) { vPts.add(cx); vPts.add(cy); draft = pathShape() }
            }
            SpriteTool.Line, SpriteTool.Rect, SpriteTool.Ellipse -> { vx1 = cx; vy1 = cy; draft = boxShape() }
            SpriteTool.Eraser -> {
                // look along the way from the last position too, so a quick swipe cannot skip over a thin shape
                val tol = eraseTol()
                val n = Math.ceil(Math.sqrt(((x - rawX) * (x - rawX) + (y - rawY) * (y - rawY)).toDouble()) / maxOf(tol, 0.5f)).toInt().coerceIn(1, 200)
                var hit = erasing
                for (k in 1..n) { val t = k.toFloat() / n; hit = hit + d.hitAll(rawX + (x - rawX) * t, rawY + (y - rawY) * t, tol) }
                erasing = hit
            }
            SpriteTool.Pick -> vPick(x, y)
            SpriteTool.Fill -> {}
        }
        vx1 = cx; vy1 = cy; rawX = x; rawY = y; tick++
    }

    private fun vEnd() {
        val d = vector
        if (d != null) when (tool) {
            SpriteTool.Brush -> if (vPts.size >= 2) { d.add(pathShape()); rememberColor(color) }       // a tap makes a dot
            SpriteTool.Line, SpriteTool.Rect, SpriteTool.Ellipse -> {
                if (Math.abs(vx1 - vx0) + Math.abs(vy1 - vy0) >= 0.5f) { d.add(boxShape()); rememberColor(color) }
            }
            SpriteTool.Eraser -> d.remove(erasing)
            SpriteTool.Fill -> if (d.fillAt(vx1, vy1, color, opacity)) rememberColor(color)
            SpriteTool.Pick -> {}
        }
        draft = null; erasing = emptySet(); vPts.clear(); tick++
    }
}

object SpriteColors {
    fun alpha(c: Int) = c ushr 24
    /** The 16 colours of the PICO-8 palette: a handy default for pixel art. */
    val palette = listOf(
        0xFF000000, 0xFF1D2B53, 0xFF7E2553, 0xFF008751, 0xFFAB5236, 0xFF5F574F, 0xFFC2C3C7, 0xFFFFF1E8,
        0xFFFF004D, 0xFFFFA300, 0xFFFFEC27, 0xFF00E436, 0xFF29ADFF, 0xFF83769C, 0xFFFF77A8, 0xFFFFCCAA,
    ).map { it.toInt() }
}
