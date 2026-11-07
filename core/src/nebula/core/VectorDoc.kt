package nebula.core

/**
 * One shape of a vector sprite. [pts] holds x,y pairs in picture units (1 unit = 1 pixel of the saved PNG):
 * Path = the points of a freehand stroke, Line = 2 points, Rect / Ellipse = 2 opposite corners (smaller values first).
 * [stroke] and [fill] are ARGB colours with the opacity already in the alpha; 0 = not painted. [width] is the line width.
 * Shapes never change once made (an edit makes a new one), so an undo step is just a list of references.
 */
class VShape(val kind: Kind, val pts: FloatArray, val stroke: Int, val fill: Int, val width: Float) {
    enum class Kind { Path, Line, Rect, Ellipse }

    fun withFill(c: Int) = VShape(kind, pts, stroke, c, width)

    /** Shapes that have an inside to paint (a freehand stroke counts as closed when it has three points or more). */
    val closed: Boolean get() = kind == Kind.Rect || kind == Kind.Ellipse || (kind == Kind.Path && pts.size >= 6)

    /** The line the shape is drawn along, as x,y pairs (an ellipse becomes a many-sided polygon). */
    private val poly: FloatArray by lazy {
        when (kind) {
            Kind.Rect -> floatArrayOf(pts[0], pts[1], pts[2], pts[1], pts[2], pts[3], pts[0], pts[3], pts[0], pts[1])
            Kind.Ellipse -> {
                val cx = (pts[0] + pts[2]) / 2f; val cy = (pts[1] + pts[3]) / 2f
                val rx = Math.abs(pts[2] - pts[0]) / 2f; val ry = Math.abs(pts[3] - pts[1]) / 2f
                val n = 48; val o = FloatArray((n + 1) * 2)
                for (k in 0..n) { val t = k * 2.0 * Math.PI / n; o[k * 2] = (cx + rx * Math.cos(t)).toFloat(); o[k * 2 + 1] = (cy + ry * Math.sin(t)).toFloat() }
                o
            }
            else -> pts
        }
    }

    /** True when ([x], [y]) is inside the shape's area (only closed shapes have one). */
    fun contains(x: Float, y: Float): Boolean {
        if (!closed) return false
        if (kind == Kind.Rect) return x >= pts[0] && x <= pts[2] && y >= pts[1] && y <= pts[3]
        if (kind == Kind.Ellipse) {
            val rx = (pts[2] - pts[0]) / 2f; val ry = (pts[3] - pts[1]) / 2f
            if (rx <= 0f || ry <= 0f) return false
            val u = (x - (pts[0] + pts[2]) / 2f) / rx; val v = (y - (pts[1] + pts[3]) / 2f) / ry
            return u * u + v * v <= 1f
        }
        // freehand stroke: even-odd rule on the polygon it makes when closed
        var inside = false; val n = pts.size / 2; var j = n - 1
        for (i in 0 until n) {
            val xi = pts[i * 2]; val yi = pts[i * 2 + 1]; val xj = pts[j * 2]; val yj = pts[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Distance from ([x], [y]) to the line the shape is drawn along. */
    fun outlineDistance(x: Float, y: Float): Float {
        val p = poly
        if (p.size < 4) return if (p.size == 2) dist(x - p[0], y - p[1]) else Float.MAX_VALUE
        var best = Float.MAX_VALUE; var k = 0
        while (k + 3 < p.size) { best = minOf(best, segDist(x, y, p[k], p[k + 1], p[k + 2], p[k + 3])); k += 2 }
        return best
    }

    /** True when ([x], [y]) touches the shape: inside its painted area, or within [tol] of its painted line. */
    fun hits(x: Float, y: Float, tol: Float): Boolean {
        if (fill != 0 && contains(x, y)) return true
        return if (stroke != 0) outlineDistance(x, y) <= tol + width / 2f else (fill != 0 && outlineDistance(x, y) <= tol)
    }

    private companion object {
        fun dist(dx: Float, dy: Float): Float = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
            return dist(ax + t * dx - px, ay + t * dy - py)
        }
    }
}

/**
 * A vector picture for the sprite editor: one or more frames, each a list of [VShape]s drawn in order (the last one on top), all on a
 * canvas of [width] x [height] units. Android-free (the app draws the shapes and rasterises them into the PNG the engine uses), so the
 * editing logic is tested in CoreTests. Saved as text next to the PNG by [VectorFile].
 */
class VectorDoc(override val width: Int, override val height: Int, frames: List<List<VShape>>) : FrameDoc {
    init { require(width in 1..2048 && height in 1..2048) { "the picture must be 1 to 2048 units wide and high" }; require(frames.isNotEmpty()) { "a picture needs a frame" } }

    var frames: List<List<VShape>> = frames.toList()
        private set
    override var current = 0
        private set
    override var version = 0
        private set

    val frame: List<VShape> get() = frames[current]
    override val frameCount get() = frames.size

    // ------------------------------------------------------------------ undo / redo
    private class Snapshot(val frames: List<List<VShape>>, val current: Int)
    private val undoStack = ArrayList<Snapshot>()
    private val redoStack = ArrayList<Snapshot>()
    override val canUndo get() = undoStack.isNotEmpty()
    override val canRedo get() = redoStack.isNotEmpty()

    private fun checkpoint() {
        undoStack.add(Snapshot(frames, current)); if (undoStack.size > SpriteDoc.MAX_UNDO) undoStack.removeAt(0)
        redoStack.clear()
    }
    override fun undo() { val s = undoStack.removeLastOrNull() ?: return; redoStack.add(Snapshot(frames, current)); frames = s.frames; current = s.current; version++ }
    override fun redo() { val s = redoStack.removeLastOrNull() ?: return; undoStack.add(Snapshot(frames, current)); frames = s.frames; current = s.current; version++ }

    private fun replaceCurrent(shapes: List<VShape>) { frames = frames.toMutableList().also { it[current] = shapes }; version++ }

    // ------------------------------------------------------------------ frames
    override fun select(i: Int) { val n = i.coerceIn(0, frames.size - 1); if (n != current) { current = n; version++ } }

    override fun addFrame(copy: Boolean) {
        checkpoint()
        val f: List<VShape> = if (copy) frame else emptyList()
        frames = frames.toMutableList().also { it.add(current + 1, f) }; current++; version++
    }
    override fun deleteFrame() {
        if (frames.size <= 1) return
        checkpoint(); frames = frames.toMutableList().also { it.removeAt(current) }; current = current.coerceAtMost(frames.size - 1); version++
    }
    override fun moveFrame(delta: Int) {
        val to = current + delta; if (to !in frames.indices) return
        checkpoint(); frames = frames.toMutableList().also { val f = it.removeAt(current); it.add(to, f) }; current = to; version++
    }
    override fun clearFrame() { checkpoint(); replaceCurrent(emptyList()) }

    // ------------------------------------------------------------------ shapes
    /** Puts [shape] on top of the current frame (one undo step). */
    fun add(shape: VShape) { checkpoint(); replaceCurrent(frame + shape) }

    /** Removes the shapes at these positions of the current frame (one undo step). Returns false when there was nothing to remove. */
    fun remove(indexes: Collection<Int>): Boolean {
        val drop = indexes.filter { it in frame.indices }.toSet()
        if (drop.isEmpty()) return false
        checkpoint(); replaceCurrent(frame.filterIndexed { i, _ -> i !in drop }); return true
    }

    /** Positions of every shape of the current frame that ([x], [y]) touches (see [VShape.hits]). */
    fun hitAll(x: Float, y: Float, tol: Float): List<Int> = frame.indices.filter { frame[it].hits(x, y, tol) }

    /** Position of the top-most shape that ([x], [y]) touches, or -1. */
    fun topAt(x: Float, y: Float, tol: Float): Int = frame.indices.lastOrNull { frame[it].hits(x, y, tol) } ?: -1

    /**
     * Paint bucket: gives the top-most closed shape under ([x], [y]) this fill colour (with [opacity] in its alpha). A shape that was only
     * an outline keeps its line. Returns false when no shape is there or it already has that fill.
     */
    fun fillAt(x: Float, y: Float, color: Int, opacity: Float): Boolean {
        val c = tint(color, opacity)
        for (i in frame.indices.reversed()) {
            val s = frame[i]
            if (s.closed && s.contains(x, y)) {
                if (s.fill == c) return false
                checkpoint(); replaceCurrent(frame.toMutableList().also { it[i] = s.withFill(c) }); return true
            }
        }
        return false
    }

    /** Eyedropper: the colour of the top-most shape under ([x], [y]) (its fill inside, its line on the edge), or null when there is none. */
    fun colorAt(x: Float, y: Float): Int? {
        for (i in frame.indices.reversed()) {
            val s = frame[i]
            if (s.fill != 0 && s.contains(x, y)) return s.fill
            if (s.stroke != 0 && s.outlineDistance(x, y) <= s.width / 2f + 0.5f) return s.stroke
        }
        return null
    }

    override fun onion(before: Int, after: Int, wrap: Boolean): List<SpriteDoc.Onion> = onionFrames(frames.size, current, before, after, wrap)

    companion object {
        fun blank(w: Int, h: Int) = VectorDoc(w, h, listOf(emptyList()))

        /** [color] with its alpha scaled by [opacity] (0..1): the way a colour and the opacity slider become one ARGB value. */
        fun tint(color: Int, opacity: Float): Int =
            Argb.make(Math.round(Argb.a(color) * opacity.coerceIn(0f, 1f)), Argb.r(color), Argb.g(color), Argb.b(color))
    }
}

/**
 * The text file a vector sprite is kept in, next to its PNG (`hero.png` + `hero.nvec`). The PNG is what the game uses; this file is
 * what the sprite editor opens again, so the shapes stay editable. One line per shape, after a `size` line and a `frame` line per frame:
 * `kind stroke fill width x y x y ...` with colours as #RRGGBB or #AARRGGBB (`#00000000` = none).
 */
object VectorFile {
    fun extensionOf(png: String) = png.substringBeforeLast('.') + ".nvec"

    private fun num(f: Float): String {
        val r = Math.round(f * 100f) / 100f
        return if (r == Math.floor(r.toDouble()).toFloat()) r.toLong().toString() else r.toString()
    }

    fun toText(d: VectorDoc): String {
        val sb = StringBuilder("# Nebula vector sprite\n")
        sb.append("size ").append(d.width).append(' ').append(d.height).append('\n')
        for (f in d.frames) {
            sb.append("frame\n")
            for (s in f) {
                sb.append(s.kind.name.lowercase()).append(' ').append(Argb.hex(s.stroke)).append(' ').append(Argb.hex(s.fill)).append(' ').append(num(s.width))
                for (v in s.pts) sb.append(' ').append(num(v))
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    /** Lenient: lines that do not make sense are skipped. Null when there is no usable `size` line. */
    fun parse(text: String): VectorDoc? {
        var w = 0; var h = 0
        val frames = ArrayList<MutableList<VShape>>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val t = line.split(Regex("\\s+"))
            when (t[0]) {
                "size" -> if (t.size >= 3) { w = t[1].toIntOrNull() ?: 0; h = t[2].toIntOrNull() ?: 0 }
                "frame" -> if (frames.size < 4096) frames.add(ArrayList())
                else -> shapeOf(t)?.let { s -> if (frames.isEmpty()) frames.add(ArrayList()); frames.last().add(s) }
            }
        }
        if (w !in 1..2048 || h !in 1..2048) return null
        if (frames.isEmpty()) frames.add(ArrayList())
        return VectorDoc(w, h, frames)
    }

    private fun shapeOf(t: List<String>): VShape? {
        val kind = when (t[0]) {
            "path" -> VShape.Kind.Path; "line" -> VShape.Kind.Line; "rect" -> VShape.Kind.Rect; "ellipse" -> VShape.Kind.Ellipse
            else -> return null
        }
        if (t.size < 5) return null
        val stroke = Argb.parse(t[1]) ?: return null
        val fill = Argb.parse(t[2]) ?: return null
        val width = t[3].toFloatOrNull() ?: return null
        val nums = t.drop(4).map { it.toFloatOrNull() ?: return null }.toFloatArray()
        if (!width.isFinite() || nums.any { !it.isFinite() }) return null
        val pts = when (kind) {
            VShape.Kind.Path -> if (nums.size >= 2 && nums.size % 2 == 0) nums else return null
            VShape.Kind.Line -> if (nums.size == 4) nums else return null
            else -> if (nums.size == 4) floatArrayOf(minOf(nums[0], nums[2]), minOf(nums[1], nums[3]), maxOf(nums[0], nums[2]), maxOf(nums[1], nums[3])) else return null
        }
        return VShape(kind, pts, stroke, fill, width.coerceIn(0f, 512f))
    }
}
