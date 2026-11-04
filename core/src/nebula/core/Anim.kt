package nebula.core

/**
 * One named animation on a sprite sheet. Frames are numbered from 1, left to right and top to bottom.
 * `from` may be bigger than `to` to play a clip backwards.
 */
class Clip(val name: String, val from: Int, val to: Int, val fps: Double, val loop: Boolean) {
    val count get() = Math.abs(to - from) + 1

    /** Frame shown [time] seconds after the clip started, and whether a non-looping clip has run out. */
    fun frameAt(time: Double): Pair<Int, Boolean> {
        val step = if (to >= from) 1 else -1
        val idx = if (fps <= 0.0 || time <= 0.0) 0 else Math.floor(time * fps).toInt()
        if (loop) return (from + step * (idx % count)) to false
        if (idx >= count) return to to true
        return (from + step * idx) to false
    }
}

/** A sprite sheet (image + grid) and its clips: the content of one `.nanim` file. */
class AnimSet(var sheet: String = "", var cols: Int = 1, var rows: Int = 1, val clips: MutableList<Clip> = ArrayList()) {
    val frameCount get() = cols * rows
    fun clip(name: String): Clip? = clips.firstOrNull { it.name == name }

    fun toText(): String {
        val sb = StringBuilder("# Nebula animation set\n")
        sb.append("sheet ").append(sheet).append('\n')
        sb.append("grid ").append(cols).append(' ').append(rows).append('\n')
        for (c in clips) {
            val fps = if (c.fps == Math.floor(c.fps)) c.fps.toLong().toString() else c.fps.toString()
            sb.append("anim ").append(c.name).append(' ').append(c.from).append(' ').append(c.to).append(' ')
                .append(fps).append(' ').append(if (c.loop) "loop" else "once").append('\n')
        }
        return sb.toString()
    }

    companion object {
        /** Lenient: lines that do not make sense are skipped. */
        fun parse(text: String): AnimSet {
            val set = AnimSet()
            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val w = line.split(Regex("\\s+"))
                when (w[0]) {
                    "sheet" -> set.sheet = line.removePrefix("sheet").trim()
                    "grid" -> if (w.size >= 3) {
                        set.cols = (w[1].toIntOrNull() ?: 1).coerceIn(1, 512)
                        set.rows = (w[2].toIntOrNull() ?: 1).coerceIn(1, 512)
                    }
                    "anim" -> if (w.size >= 5) {
                        val from = w[2].toIntOrNull(); val to = w[3].toIntOrNull(); val fps = w[4].toDoubleOrNull()
                        if (from != null && to != null && fps != null)
                            set.clips.add(Clip(w[1], from, to, fps.coerceIn(0.0, 240.0), w.getOrNull(5) != "once"))
                    }
                }
            }
            return set
        }

        fun cleanName(raw: String): String = raw.trim().replace(Regex("[^A-Za-z0-9_]"), "_")
    }
}

/** Parsed `.nanim` files by project path (misses are remembered too; call [clear] after files change). */
class AnimLibrary(private val read: (String) -> String?) {
    private val cache = HashMap<String, AnimSet?>()

    fun get(path: String): AnimSet? {
        if (path.isEmpty()) return null
        if (path in cache) return cache[path]
        val set = read(path)?.let { AnimSet.parse(it) }
        cache[path] = set
        return set
    }

    fun clear() = cache.clear()
}

/** What the engine asks the sound player to do for a `Sound` node (`key` is the node id). */
class SoundCmd(val key: Long, val file: String, val volume: Double, val pitch: Double, val loop: Boolean, val stop: Boolean)
