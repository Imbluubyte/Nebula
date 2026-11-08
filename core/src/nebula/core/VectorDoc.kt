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
    fun withStroke(c: Int) = VShape(kind, pts, c, fill, width)

    /** The line the shape is drawn along as x,y pairs (a rectangle or ellipse becomes a closed many-sided line). Do not change the array. */
    fun outline(): FloatArray = poly

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

    /**
     * Replaces the shapes of the current frame (one undo step), for example with what [VectorErase.along] left of them.
     * Returns false, and records nothing, when [shapes] are the very same shapes as now.
     */
    fun replaceShapes(shapes: List<VShape>): Boolean {
        if (shapes.size == frame.size && shapes.indices.all { shapes[it] === frame[it] }) return false
        checkpoint(); replaceCurrent(shapes); return true
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
 * The vector eraser: a round brush that rubs out the part of a line it passes over instead of the whole shape. Android-free so it is
 * tested in CoreTests. The editor calls [along] for every bit of the finger's way and shows the result at once; when the finger lifts,
 * the last result becomes the frame ([VectorDoc.replaceShapes], one undo step).
 */
object VectorErase {
    /**
     * [shapes] after an eraser of radius [r] moved from ([x0], [y0]) to ([x1], [y1]). Shapes it did not touch come back as the very same
     * objects, and when it touched nothing at all the very same list comes back.
     *
     * * a line, an outline or a freehand stroke is cut: the part under the eraser goes, what is left on either side stays as strokes of its own
     * * a shape that is only a fill goes whole when touched (an area cannot be rubbed out in pieces)
     * * a shape with a line and a fill loses just the part of its line (the fill stays), unless the eraser is well inside the fill: then it goes whole
     */
    fun along(shapes: List<VShape>, x0: Float, y0: Float, x1: Float, y1: Float, r: Float): List<VShape> {
        var changed = false
        val out = ArrayList<VShape>(shapes.size + 2)
        for (s in shapes) {
            val res = shape(s, x0, y0, x1, y1, r)
            if (res == null) out.add(s) else { changed = true; out.addAll(res) }
        }
        return if (changed) out else shapes
    }

    /** What is left of [s], or null when the eraser did not touch it (an empty list: all of it went). */
    private fun shape(s: VShape, x0: Float, y0: Float, x1: Float, y1: Float, r: Float): List<VShape>? {
        val p = s.outline()
        val reach = r + (if (s.stroke != 0) s.width / 2f else 0f)      // a stroke's round ends reach half its width past where it was cut
        if (p.size < 2) return null
        if (p.size == 2) return if (pointNear(p[0], p[1], x0, y0, x1, y1, reach)) emptyList() else null      // a dot
        val touches = touches(p, x0, y0, x1, y1, reach)
        if (s.fill != 0) {
            val inside = s.closed && (s.contains(x0, y0) || s.contains(x1, y1))
            if (s.stroke == 0) return if (inside || touches) emptyList() else null
            val deep = (s.closed && s.contains(x0, y0) && s.outlineDistance(x0, y0) > reach) ||
                (s.closed && s.contains(x1, y1) && s.outlineDistance(x1, y1) > reach)
            if (deep) return emptyList()
            if (!touches) return null
            return listOf(s.withStroke(0)) + cut(s, p, x0, y0, x1, y1, reach)
        }
        if (!touches) return null
        return cut(s, p, x0, y0, x1, y1, reach)
    }

    private fun pointNear(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float, r: Float): Boolean =
        cover(px, py, px, py, x0, y0, x1, y1, r) != null

    private fun touches(p: FloatArray, x0: Float, y0: Float, x1: Float, y1: Float, r: Float): Boolean {
        var k = 0
        while (k + 3 < p.size) { if (cover(p[k], p[k + 1], p[k + 2], p[k + 3], x0, y0, x1, y1, r) != null) return true; k += 2 }
        return false
    }

    /** The pieces of the line [p] that are left outside the eraser, as strokes of [s]'s colour and width (a closed line cut once stays one piece). */
    private fun cut(s: VShape, p: FloatArray, x0: Float, y0: Float, x1: Float, y1: Float, r: Float): List<VShape> {
        val pieces = ArrayList<FloatArray>()
        var cur = ArrayList<Float>()
        fun emit() { if (cur.size >= 4) pieces.add(cur.toFloatArray()); cur = ArrayList() }
        fun put(x: Float, y: Float) { if (cur.size >= 2 && cur[cur.size - 2] == x && cur[cur.size - 1] == y) return; cur.add(x); cur.add(y) }
        var k = 0
        while (k + 3 < p.size) {
            val ax = p[k]; val ay = p[k + 1]; val bx = p[k + 2]; val by = p[k + 3]
            val iv = cover(ax, ay, bx, by, x0, y0, x1, y1, r)
            if (iv == null) { put(ax, ay); put(bx, by) }
            else {
                val lo = iv[0]; val hi = iv[1]
                if (lo > EPS) { put(ax, ay); put(ax + (bx - ax) * lo, ay + (by - ay) * lo) }
                emit()
                if (hi < 1f - EPS) { put(ax + (bx - ax) * hi, ay + (by - ay) * hi); put(bx, by) }
            }
            k += 2
        }
        emit()
        // a closed line that was cut once starts and ends at the same corner: join its two ends into one stroke
        val n = p.size
        if (pieces.size >= 2 && p[0] == p[n - 2] && p[1] == p[n - 1]) {
            val first = pieces.first(); val last = pieces.last()
            if (first[0] == p[0] && first[1] == p[1] && last[last.size - 2] == p[0] && last[last.size - 1] == p[1]) {
                pieces[0] = last + first.copyOfRange(2, first.size)
                pieces.removeAt(pieces.size - 1)
            }
        }
        return pieces.map { VShape(VShape.Kind.Path, it, s.stroke, 0, s.width) }
    }

    private const val EPS = 1e-3f

    /**
     * The part of the segment A to B (as t from 0 to 1) that lies within [r] of the eraser's way, the segment from (x0, y0) to (x1, y1),
     * or null when none does. The eraser's way with its round brush is convex, so what is inside on a straight line is one stretch.
     */
    private fun cover(ax: Float, ay: Float, bx: Float, by: Float, x0: Float, y0: Float, x1: Float, y1: Float, r: Float): FloatArray? {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        fun add(t: FloatArray?) { if (t != null) { lo = minOf(lo, t[0]); hi = maxOf(hi, t[1]) } }
        add(disc(ax, ay, bx, by, x0, y0, r))
        if (x1 != x0 || y1 != y0) { add(disc(ax, ay, bx, by, x1, y1, r)); add(strip(ax, ay, bx, by, x0, y0, x1, y1, r)) }
        return if (lo <= hi) floatArrayOf(lo, hi) else null
    }

    /** Where the segment A to B is inside the circle of radius [r] around ([cx], [cy]). */
    private fun disc(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, r: Float): FloatArray? {
        val dx = bx - ax; val dy = by - ay; val fx = ax - cx; val fy = ay - cy
        val a = dx * dx + dy * dy; val c = fx * fx + fy * fy - r * r
        if (a < 1e-12f) return if (c <= 0f) floatArrayOf(0f, 1f) else null
        val b = 2f * (fx * dx + fy * dy)
        val d = b * b - 4f * a * c
        if (d < 0f) return null
        val q = Math.sqrt(d.toDouble()).toFloat()
        val lo = maxOf((-b - q) / (2f * a), 0f); val hi = minOf((-b + q) / (2f * a), 1f)
        return if (lo <= hi) floatArrayOf(lo, hi) else null
    }

    /** Where the segment A to B is inside the rectangle that is the eraser's way (the segment from (x0, y0) to (x1, y1)) made [r] wide on both sides. */
    private fun strip(ax: Float, ay: Float, bx: Float, by: Float, x0: Float, y0: Float, x1: Float, y1: Float, r: Float): FloatArray? {
        val ex = x1 - x0; val ey = y1 - y0
        val len = Math.sqrt((ex * ex + ey * ey).toDouble()).toFloat()
        if (len < 1e-6f) return null
        val ux = ex / len; val uy = ey / len           // along the eraser's way
        val nx = -uy; val ny = ux                      // across it
        val dx = bx - ax; val dy = by - ay
        val t = floatArrayOf(0f, 1f)
        if (!band((ax - x0) * ux + (ay - y0) * uy, dx * ux + dy * uy, 0f, len, t)) return null
        if (!band((ax - x0) * nx + (ay - y0) * ny, dx * nx + dy * ny, -r, r, t)) return null
        return t
    }

    /** Narrows [t] (a start and an end) to where lo <= c0 + t * dc <= hi. False when nothing is left. */
    private fun band(c0: Float, dc: Float, lo: Float, hi: Float, t: FloatArray): Boolean {
        if (Math.abs(dc) < 1e-9f) return c0 >= lo && c0 <= hi
        var t0 = (lo - c0) / dc; var t1 = (hi - c0) / dc
        if (t0 > t1) { val x = t0; t0 = t1; t1 = x }
        t[0] = maxOf(t[0], t0); t[1] = minOf(t[1], t1)
        return t[0] <= t[1]
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
