package nebula.core

/**
 * Which Sprite children of a Button are shown. A Button whose children include a Sprite called `Pressed` has two looks: while it is held only
 * `Pressed` is shown (the other Sprites are hidden), otherwise everything except `Pressed` is shown. Without a `Pressed` Sprite nothing changes.
 * The editor shows every Sprite (so both looks can be lined up); only the running game switches.
 */
object ButtonSkin {
    const val PRESSED = "Pressed"

    /** True when this node is a Sprite (or any node) that its Button parent wants hidden right now. */
    fun hidden(n: SceneNode, editor: Boolean): Boolean {
        if (editor) return false
        val b = n.parent ?: return false
        if (b.type != "Button" || n.type != "Sprite") return false
        val pressedSprite = b.children.any { it.type == "Sprite" && it.name == PRESSED }
        if (!pressedSprite) return false
        val held = b.b("_pressed")
        return if (n.name == PRESSED) !held else held
    }
}
