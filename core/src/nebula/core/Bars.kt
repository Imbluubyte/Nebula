package nebula.core

import nebula.lang.ScriptMeta

/**
 * DynamicBar support shared by the engine, the renderer and the editor. A bar has no picture of its own: a script of DynamicBar says
 * which child Sprite is the empty bar and which the full bar (`empty @Back`, `full @Fill`), and the full sprite is cropped to the
 * bar's value when it is drawn. The roles live in the script metadata, so [register] is called with the metadata of the project
 * (by the Engine, and by the editor after every build of the scripts).
 */
object Bars {
    class Roles(val empty: String, val full: String)

    private var byFile: Map<String, Roles> = emptyMap()

    /** Remembers the sprite roles of every script of DynamicBar, including the ones it inherits from another script. */
    fun register(metas: Collection<ScriptMeta>) {
        val byName = metas.associateBy { it.name }
        val out = HashMap<String, Roles>()
        for (m in metas) {
            var empty = ""; var full = ""
            var c: ScriptMeta? = m; var guard = 0
            while (c != null && guard++ < 64) {
                if (empty.isEmpty()) empty = c.barEmpty
                if (full.isEmpty()) full = c.barFull
                c = byName[c.base]
            }
            if (empty.isNotEmpty() || full.isNotEmpty()) out[m.file] = Roles(empty, full)
        }
        byFile = out
    }

    fun rolesOf(bar: SceneNode): Roles? = if (bar.type == "DynamicBar") bar.script?.let { byFile[it] } else null

    /** A relative node path ("Fill", "Frame/Fill", "../X") followed down from [from]; null when nothing is there. */
    fun resolve(from: SceneNode, path: String): SceneNode? {
        var cur: SceneNode? = from
        for (seg in path.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            cur = if (seg == "..") cur?.parent else cur?.child(seg)
            if (cur == null) return null
        }
        return cur
    }

    /** The bar's Sprite showing the empty bar, or null when the script does not declare one (or it is missing). */
    fun emptySprite(bar: SceneNode): SceneNode? = rolesOf(bar)?.empty?.takeIf { it.isNotEmpty() }?.let { resolve(bar, it) }?.takeIf { it.type == "Sprite" }

    /** The bar's Sprite showing the full bar, or null. */
    fun fullSprite(bar: SceneNode): SceneNode? = rolesOf(bar)?.full?.takeIf { it.isNotEmpty() }?.let { resolve(bar, it) }?.takeIf { it.type == "Sprite" }

    /** How full the bar is, 0.0 to 1.0: (value - minValue) / (maxValue - minValue). An empty or backwards range counts as empty. */
    fun fraction(bar: SceneNode): Double = fraction(bar.f("value"), bar.f("minValue"), bar.f("maxValue"))

    fun fraction(value: Double, min: Double, max: Double): Double {
        if (!(max > min)) return 0.0
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    }

    /** If [sprite] is the full-bar sprite of a DynamicBar above it: that bar's fraction and direction; otherwise null (draw it whole). */
    fun fillOf(sprite: SceneNode): Pair<Double, String>? {
        if (byFile.isEmpty()) return null
        var p = sprite.parent
        while (p != null) {
            if (p.type == "DynamicBar" && fullSprite(p) === sprite) return fraction(p) to p.s("direction")
            p = p.parent
        }
        return null
    }
}
