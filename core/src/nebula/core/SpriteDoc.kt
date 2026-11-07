package nebula.core

/**
 * The picture being edited in the built-in sprite editor: one or more frames of ARGB pixels (all the same size), the drawing tools, and
 * undo / redo. Android-free (the app turns it into bitmaps and PNG files), so everything here is tested in CoreTests.
 *
 * Frames are never changed in place: a stroke draws into a copy that replaces the frame when the stroke ends, so an undo step is just a
 * list of references to the frames as they were (cheap even with many frames).
 */
class SpriteDoc(override val width: Int, override val height: Int, frames: List<IntArray>) : FrameDoc {
    init { require(width in 1..2048 && height in 1..2048) { "the picture must be 1 to 2048 pixels wide and high" } ; require(frames.isNotEmpty() && frames.all { it.size == width * height }) { "frames do not fit the picture" } }

    var frames: List<IntArray> = frames.map { it.copyOf() }
        private set
    override var current = 0
        private set
    /** Bumped on every change, so the UI knows when to redraw. */
    override var version = 0
        private set

    val frame: IntArray get() = frames[current]
    override val frameCount get() = frames.size

    // ------------------------------------------------------------------ undo / redo
    private class Snapshot(val frames: List<IntArray>, val current: Int)
    private val undoStack = ArrayList<Snapshot>()
    private val redoStack = ArrayList<Snapshot>()
    override val canUndo get() = undoStack.isNotEmpty()
    override val canRedo get() = redoStack.isNotEmpty()

    private fun checkpoint() {
        undoStack.add(Snapshot(frames, current)); if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
        redoStack.clear()
    }
    override fun undo() { val s = undoStack.removeLastOrNull() ?: return; redoStack.add(Snapshot(frames, current)); frames = s.frames; current = s.current; version++ }
    override fun redo() { val s = redoStack.removeLastOrNull() ?: return; undoStack.add(Snapshot(frames, current)); frames = s.frames; current = s.current; version++ }

    private fun replaceCurrent(px: IntArray) { frames = frames.toMutableList().also { it[current] = px }; version++ }

    // ------------------------------------------------------------------ frames
    override fun select(i: Int) { val n = i.coerceIn(0, frames.size - 1); if (n != current) { current = n; version++ } }

    /** Adds a frame after the current one (a copy of it, or blank) and selects it. */
    override fun addFrame(copy: Boolean) {
        checkpoint()
        val f = if (copy) frame.copyOf() else IntArray(width * height)
        frames = frames.toMutableList().also { it.add(current + 1, f) }; current++; version++
    }
    override fun deleteFrame() {
        if (frames.size <= 1) return
        checkpoint(); frames = frames.toMutableList().also { it.removeAt(current) }; current = current.coerceAtMost(frames.size - 1); version++
    }
    /** Moves the current frame one place earlier (-1) or later (+1). */
    override fun moveFrame(delta: Int) {
        val to = current + delta; if (to !in frames.indices) return
        checkpoint(); frames = frames.toMutableList().also { val f = it.removeAt(current); it.add(to, f) }; current = to; version++
    }
    override fun clearFrame() { checkpoint(); replaceCurrent(IntArray(width * height)) }

    // ------------------------------------------------------------------ tools
    /** How a brush paints. [pixel] = hard edged (pixel art), otherwise soft anti-aliased. [erase] removes paint instead of adding it. */
    class Brush(val size: Int, val opacity: Float, val color: Int, val pixel: Boolean, val erase: Boolean = false)

    /**
     * One stroke (a drag of the brush, or a shape being dragged out). Within a stroke a pixel is never painted twice, so a see-through brush
     * gives an even stroke instead of darker spots where it overlaps itself. Nothing reaches the picture (or the undo history) until [commit].
     */
    inner class Stroke internal constructor(private val brush: Brush) {
        private val base = frame
        private var out = base.copyOf()
        private val cov = FloatArray(width * height)
        private var touched = false

        /** The current frame with this stroke painted on it so far (what to show while the finger is down). Do not change it. */
        val preview: IntArray get() = out

        /** Starts over from the picture as it was (used to redraw a shape while it is dragged). */
        fun reset() { out = base.copyOf(); java.util.Arrays.fill(cov, 0f); touched = false }

        private fun paint(i: Int, c: Float) {
            if (c <= cov[i]) return
            cov[i] = c; touched = true
            out[i] = if (brush.erase) Argb.erase(base[i], brush.opacity * c) else Argb.over(base[i], brush.color, brush.opacity * c)
        }

        /** One dab of the brush centred on pixel ([x], [y]). */
        fun dab(x: Int, y: Int) = dabAt(x.toDouble(), y.toDouble())

        private fun dabAt(px: Double, py: Double) {
            val s = brush.size.coerceIn(1, 256); val r = s / 2.0
            // pixel brush: odd sizes centre on a pixel, even sizes on a pixel corner, so 1 is one pixel and 2 is a 2x2 block
            val cx = if (brush.pixel) Math.floor(px) + (if (s % 2 == 1) 0.5 else 1.0) else px + 0.5
            val cy = if (brush.pixel) Math.floor(py) + (if (s % 2 == 1) 0.5 else 1.0) else py + 0.5
            val x0 = Math.floor(cx - r - 1).toInt().coerceAtLeast(0); val x1 = Math.ceil(cx + r + 1).toInt().coerceAtMost(width - 1)
            val y0 = Math.floor(cy - r - 1).toInt().coerceAtLeast(0); val y1 = Math.ceil(cy + r + 1).toInt().coerceAtMost(height - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x + 0.5 - cx; val dy = y + 0.5 - cy
                val d = Math.sqrt(dx * dx + dy * dy)
                val c = if (brush.pixel) (if (s == 1) (if (x == Math.floor(px).toInt() && y == Math.floor(py).toInt()) 1f else 0f) else if (d <= r + 1e-9) 1f else 0f)
                        else (r + 0.5 - d).coerceIn(0.0, 1.0).toFloat()
                if (c > 0f) paint(y * width + x, c)
            }
        }

        /** A straight line of dabs from ([x0], [y0]) to ([x1], [y1]) (inclusive). */
        fun line(x0: Int, y0: Int, x1: Int, y1: Int) {
            var x = x0; var y = y0
            val dx = Math.abs(x1 - x0); val dy = -Math.abs(y1 - y0); val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
            var err = dx + dy
            while (true) {
                dab(x, y)
                if (x == x1 && y == y1) break
                val e2 = 2 * err
                if (e2 >= dy) { err += dy; x += sx }
                if (e2 <= dx) { err += dx; y += sy }
            }
        }

        private fun fillBox(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(height - 1)) for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(width - 1)) paint(y * width + x, 1f)
        }

        /** A rectangle with these two opposite corners (inclusive); outline in the brush size, or solid. */
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, filled: Boolean) {
            val ax = minOf(x0, x1); val bx = maxOf(x0, x1); val ay = minOf(y0, y1); val by = maxOf(y0, y1)
            if (filled) { fillBox(ax, ay, bx, by); return }
            line(ax, ay, bx, ay); line(bx, ay, bx, by); line(bx, by, ax, by); line(ax, by, ax, ay)
        }

        /** An ellipse that fits the box with these two opposite corners (inclusive). */
        fun ellipse(x0: Int, y0: Int, x1: Int, y1: Int, filled: Boolean) {
            val ax = minOf(x0, x1); val bx = maxOf(x0, x1); val ay = minOf(y0, y1); val by = maxOf(y0, y1)
            val rx = (bx - ax + 1) / 2.0; val ry = (by - ay + 1) / 2.0
            val cx = ax + rx; val cy = ay + ry
            if (filled) {
                for (y in ay.coerceAtLeast(0)..by.coerceAtMost(height - 1)) for (x in ax.coerceAtLeast(0)..bx.coerceAtMost(width - 1)) {
                    val u = (x + 0.5 - cx) / rx; val v = (y + 0.5 - cy) / ry
                    if (u * u + v * v <= 1.0) paint(y * width + x, 1f)
                }
                return
            }
            // outline: dabs along the curve, close enough together that there are no gaps
            val steps = (Math.PI * 2 * Math.max(rx, ry) * 2).toInt().coerceAtLeast(16)
            for (k in 0 until steps) {
                val t = k * 2 * Math.PI / steps
                dabAt(Math.floor(cx + (rx - 0.5) * Math.cos(t)), Math.floor(cy + (ry - 0.5) * Math.sin(t)))
            }
        }

        /** Ends the stroke: the picture changes and one undo step is recorded. Returns false when nothing was painted. */
        fun commit(): Boolean {
            if (!touched) return false
            checkpoint(); replaceCurrent(out); return true
        }
    }

    fun stroke(brush: Brush): Stroke = Stroke(brush)

    /** Colour of pixel ([x], [y]) in the current frame (ARGB, 0 = transparent), or null outside the picture: the eyedropper. */
    fun pick(x: Int, y: Int): Int? = if (x in 0 until width && y in 0 until height) frame[y * width + x] else null

    /**
     * Paint bucket: paints the area of similar colour around ([x], [y]) (4-way connected). [tolerance] is how far each channel may differ
     * (0 = the exact same colour only). Returns false when nothing changed.
     */
    fun fill(x: Int, y: Int, color: Int, opacity: Float, tolerance: Int = 0): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        val src = frame; val target = src[y * width + x]
        val out = src.copyOf(); val seen = BooleanArray(width * height)
        val stack = IntArray(width * height * 2 + 4); var sp = 0
        fun similar(c: Int) = Argb.near(c, target, tolerance)
        fun push(px: Int, py: Int) { if (px in 0 until width && py in 0 until height && !seen[py * width + px] && similar(src[py * width + px])) { seen[py * width + px] = true; stack[sp++] = py * width + px } }
        push(x, y); var changed = false
        while (sp > 0) {
            val i = stack[--sp]; val px = i % width; val py = i / width
            val n = Argb.over(src[i], color, opacity); if (n != src[i]) { out[i] = n; changed = true }
            push(px + 1, py); push(px - 1, py); push(px, py + 1); push(px, py - 1)
        }
        if (!changed) return false
        checkpoint(); replaceCurrent(out); return true
    }

    // ------------------------------------------------------------------ onion skin
    /** A neighbouring frame shown faintly behind the current one. [offset] is negative for earlier frames and positive for later ones. */
    class Onion(val index: Int, val offset: Int, val alpha: Float)

    /**
     * Frames to show faintly while drawing: [before] earlier and [after] later ones, fading with distance. With [wrap] the animation
     * counts as a loop (the frame before the first is the last), which is how a looping animation is drawn.
     */
    override fun onion(before: Int, after: Int, wrap: Boolean): List<Onion> = onionFrames(frames.size, current, before, after, wrap)

    // ------------------------------------------------------------------ sprite sheets
    /** The frames laid out as a sheet [cols] frames wide: pixels, width, height. */
    fun toSheet(cols: Int = frames.size): Triple<IntArray, Int, Int> {
        val c = cols.coerceIn(1, frames.size); val rows = (frames.size + c - 1) / c
        val w = c * width; val h = rows * height; val px = IntArray(w * h)
        for ((k, f) in frames.withIndex()) {
            val ox = (k % c) * width; val oy = (k / c) * height
            for (y in 0 until height) System.arraycopy(f, y * width, px, (oy + y) * w + ox, width)
        }
        return Triple(px, w, h)
    }

    companion object {
        const val MAX_UNDO = 100

        fun blank(w: Int, h: Int) = SpriteDoc(w, h, listOf(IntArray(w * h)))

        /** Splits a picture into frames: a grid of [cols] x [rows] equal cells, left to right then top to bottom (the same numbering as animations). */
        fun fromSheet(px: IntArray, w: Int, h: Int, cols: Int = 1, rows: Int = 1): SpriteDoc {
            val c = cols.coerceAtLeast(1); val r = rows.coerceAtLeast(1)
            val fw = w / c; val fh = h / r
            if (fw < 1 || fh < 1 || c * r > 4096) return SpriteDoc(w, h, listOf(px))
            val frames = ArrayList<IntArray>()
            for (k in 0 until c * r) {
                val f = IntArray(fw * fh); val ox = (k % c) * fw; val oy = (k / c) * fh
                for (y in 0 until fh) System.arraycopy(px, (oy + y) * w + ox, f, y * fw, fw)
                frames.add(f)
            }
            return SpriteDoc(fw, fh, frames)
        }
    }
}

/** ARGB colour maths (0xAARRGGBB, not premultiplied). */
object Argb {
    fun a(c: Int) = c ushr 24
    fun r(c: Int) = (c shr 16) and 255
    fun g(c: Int) = (c shr 8) and 255
    fun b(c: Int) = c and 255
    fun make(a: Int, r: Int, g: Int, b: Int) = (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** [src] painted over [dst] with an extra [alpha] (0..1) on top of its own alpha. */
    fun over(dst: Int, src: Int, alpha: Float): Int {
        val sa = (a(src) / 255f) * alpha.coerceIn(0f, 1f)
        if (sa <= 0f) return dst
        val da = a(dst) / 255f
        val oa = sa + da * (1f - sa)
        if (oa <= 0f) return 0
        fun ch(s: Int, d: Int) = Math.round((s * sa + d * da * (1f - sa)) / oa)
        return make(Math.round(oa * 255f), ch(r(src), r(dst)), ch(g(src), g(dst)), ch(b(src), b(dst)))
    }

    /** [dst] with [amount] (0..1) of its alpha taken away. */
    fun erase(dst: Int, amount: Float): Int {
        val na = Math.round(a(dst) * (1f - amount.coerceIn(0f, 1f)))
        return if (na <= 0) 0 else (na shl 24) or (dst and 0xFFFFFF)
    }

    /** True when every channel of [x] and [y] is within [tol]. Two fully transparent pixels always match. */
    fun near(x: Int, y: Int, tol: Int): Boolean {
        if (a(x) == 0 && a(y) == 0) return true
        return Math.abs(a(x) - a(y)) <= tol && Math.abs(r(x) - r(y)) <= tol && Math.abs(g(x) - g(y)) <= tol && Math.abs(b(x) - b(y)) <= tol
    }

    /** A copy of [px] tinted towards [tint] and made see-through: how onion skin frames are drawn. */
    fun ghost(px: IntArray, tint: Int, alpha: Float): IntArray = IntArray(px.size) { i ->
        val c = px[i]; val ca = a(c)
        if (ca == 0) 0 else make(Math.round(ca * alpha), (r(c) + r(tint)) / 2, (g(c) + g(tint)) / 2, (b(c) + b(tint)) / 2)
    }

    fun parse(hex: String): Int? {
        val h = hex.trim().removePrefix("#")
        val v = h.toLongOrNull(16) ?: return null
        return when (h.length) { 6 -> (0xFF000000 or v).toInt(); 8 -> v.toInt(); else -> null }
    }
    fun hex(c: Int): String = if (a(c) == 255) "#%06X".format(c and 0xFFFFFF) else "#%08X".format(c)
}
