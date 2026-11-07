package nebula.core

/**
 * What the sprite editor needs from a picture, whether it is made of pixels ([SpriteDoc]) or of shapes ([VectorDoc]):
 * frames, selecting one, and undo / redo. A sprite is one or the other for its whole life.
 */
interface FrameDoc {
    val width: Int
    val height: Int
    val frameCount: Int
    val current: Int
    /** Bumped on every change, so the UI knows when to redraw. */
    val version: Int
    val canUndo: Boolean
    val canRedo: Boolean
    fun undo()
    fun redo()
    fun select(i: Int)
    /** Adds a frame after the current one (a copy of it, or blank) and selects it. */
    fun addFrame(copy: Boolean)
    fun deleteFrame()
    /** Moves the current frame one place earlier (-1) or later (+1). */
    fun moveFrame(delta: Int)
    fun clearFrame()
    fun onion(before: Int, after: Int, wrap: Boolean): List<SpriteDoc.Onion>
}

/**
 * Frames to show faintly while drawing: [before] earlier and [after] later ones out of [n], fading with distance. With [wrap]
 * the animation counts as a loop (the frame before the first is the last), which is how a looping animation is drawn.
 */
fun onionFrames(n: Int, current: Int, before: Int, after: Int, wrap: Boolean): List<SpriteDoc.Onion> {
    val out = ArrayList<SpriteDoc.Onion>()
    if (n <= 1) return out
    val seen = HashSet<Int>().also { it.add(current) }
    fun add(off: Int) {
        var i = current + off
        if (wrap) i = ((i % n) + n) % n else if (i !in 0 until n) return
        if (!seen.add(i)) return
        out.add(SpriteDoc.Onion(i, off, 0.5f / Math.abs(off)))
    }
    for (k in 1..before.coerceIn(0, 8)) add(-k)
    for (k in 1..after.coerceIn(0, 8)) add(k)
    return out
}
