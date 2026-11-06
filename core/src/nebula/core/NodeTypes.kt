package nebula.core

/** Kinds of node property. The same three kinds cross the script ABI. */
enum class Kind { F, S, B }

data class PropDef(val name: String, val kind: Kind, val default: Any, val hint: String = "")

/** A broadcast every node of this type owns. `types` are the parameter types `fn onHear(...)` gives its handler. */
data class BcDef(val name: String, val types: List<String> = emptyList()) { val arity get() = types.size }

data class NodeType(
    val name: String,
    val parent: String?,
    val props: List<PropDef>,
    val broadcasts: List<BcDef> = emptyList(),
    val icon: String = "N",
)

/** Registry of built-in node types. Adding a type: add it here, a class in api/nebula.sn, a `wrap` case and an `asX` cast. */
object NodeTypes {
    private fun f(n: String, d: Double, hint: String = "") = PropDef(n, Kind.F, d, hint)
    private fun s(n: String, d: String, hint: String = "") = PropDef(n, Kind.S, d, hint)
    private fun b(n: String, d: Boolean) = PropDef(n, Kind.B, d)

    val all: List<NodeType> = listOf(
        NodeType("Node", null, emptyList(), listOf(BcDef("freed")), "N"),
        NodeType("Node2D", "Node", listOf(
            f("x", 0.0), f("y", 0.0), f("rotation", 0.0), f("scale", 1.0), f("layer", 0.0),
            b("visible", true), b("touchable", false), b("fixed", false)), listOf(BcDef("tapped", listOf("Float", "Float"))), "+"),
        NodeType("Sprite", "Node2D", listOf(s("image", "", "image"), b("flipX", false), f("width", 0.0), f("height", 0.0),
            f("hframes", 1.0), f("vframes", 1.0), f("frame", 1.0),
            s("animations", "", "animations"), s("animation", "", "animation"), b("playing", true), f("speed", 1.0)),
            listOf(BcDef("animationFinished")), "S"),
        NodeType("Shape", "Node2D", listOf(s("shape", "rect", "shape"), f("width", 64.0), f("height", 64.0), s("color", "#4FC3F7", "color")), icon = "R"),
        NodeType("Label", "Node2D", listOf(s("text", "Label"), f("fontSize", 24.0), s("color", "#FFFFFF", "color")), icon = "T"),
        NodeType("Button", "Node2D", listOf(s("text", "Button"), f("width", 160.0), f("height", 64.0),
            f("fontSize", 24.0), s("color", "#3949AB", "color"), s("action", "", "action")), icon = "B"),
        // A bar that fills by itself, like Godot's ProgressBar, with no appearance of its own. Its look is two Sprite nodes (children, anywhere below it):
        // one picture of the empty bar (with its border) and one of the full bar (no border). The script says which is which with `empty @Back` and
        // `full @Fill` (see Bars). The full sprite is cropped to (value - minValue) / (maxValue - minValue); `direction` is the side it grows toward.
        NodeType("DynamicBar", "Node2D", listOf(f("value", 100.0), f("minValue", 0.0), f("maxValue", 100.0),
            s("direction", "right", "direction")), icon = "%"),
        NodeType("Body", "Node2D", listOf(
            f("vx", 0.0), f("vy", 0.0), f("gravity", 0.0), f("width", 32.0), f("height", 32.0),
            b("solid", false), b("static", false), f("bounce", 0.0), b("onGround", false), s("color", "", "color")),
            listOf(BcDef("collided", listOf("Node"))), "P"),
        // A Body made to be spawned over and over (bullets, enemies, pickups). The engine counts its lifetime down;
        // health, damage and the launch/chase helpers live in the script API (api/nebula.sn).
        NodeType("SpawnableBody", "Body", listOf(
            f("lifetime", 0.0, "seconds until it expires by itself, 0 = never"), f("health", 1.0), f("maxHealth", 1.0), f("damage", 1.0)),
            listOf(BcDef("expired"), BcDef("died"), BcDef("damaged", listOf("Float"))), "*"),
        // A hitbox. As a direct child of a Body (or SpawnableBody) it replaces that body's own width x height box: x, y is the offset of the box
        // from the body's centre and width, height its size (scale and rotation are ignored, like on a Body). The first CollisionBox child wins.
        NodeType("CollisionBox", "Node2D", listOf(f("width", 32.0), f("height", 32.0)), icon = "H"),
        // A grid of tiles cut from one picture (see TileGrid). x, y is the centre of the map. `tiles` is edited with the tile painter.
        NodeType("TileMap", "Node2D", listOf(s("image", "", "image"), f("tileWidth", 32.0), f("tileHeight", 32.0),
            f("hframes", 1.0), f("vframes", 1.0), f("columns", 20.0), f("rows", 12.0),
            b("solid", true), s("solidTiles", "", "which tile numbers are solid, like 1, 2, 7 (empty = every painted tile)"), s("tiles", "", "tiles")),
            listOf(), "#"),
        // The view. Without `follow` it stays where it is placed (x, y is the world point at the centre of the screen). With `follow` it tracks `target`
        // (a node path like /World/Player, or #tag for the first node with that tag): `smoothing` is how many seconds it takes to close about 63% of the
        // gap (0 = locked on), followX / followY pick the axes it moves on, offsetX / offsetY shift the view away from the target, and `limits` keeps
        // the view inside limitLeft..limitRight x limitTop..limitBottom. The engine moves the Camera node itself, so scripts can read its position.
        NodeType("Camera", "Node2D", listOf(f("zoom", 1.0), b("follow", false), s("target", "", "target"), b("followX", true), b("followY", true),
            f("smoothing", 0.15), f("offsetX", 0.0), f("offsetY", 0.0),
            b("limits", false), f("limitLeft", 0.0), f("limitTop", 0.0), f("limitRight", 1280.0), f("limitBottom", 720.0)), icon = "C"),
        NodeType("Sound", "Node", listOf(s("file", "", "sound"), f("volume", 1.0), f("pitch", 1.0), b("loop", false), b("autoplay", false)), icon = "A"),
    )

    private val byName = all.associateBy { it.name }

    /** Every type that moves and collides like a Body (the engine's physics loop). */
    val bodyTypes: Set<String> by lazy { all.filter { isA(it.name, "Body") }.map { it.name }.toSet() }
    fun get(name: String): NodeType? = byName[name]
    fun exists(name: String) = name in byName

    /** The type and all its ancestors, nearest first. */
    fun chain(name: String): List<NodeType> {
        val out = ArrayList<NodeType>()
        var t = byName[name]
        while (t != null) { out.add(t); t = t.parent?.let { byName[it] } }
        return out
    }

    fun isA(name: String, ancestor: String) = chain(name).any { it.name == ancestor }

    /** Every property of the type, ancestors first. */
    fun propsOf(name: String): List<PropDef> = chain(name).reversed().flatMap { it.props }

    fun broadcastsOf(name: String): List<BcDef> = chain(name).flatMap { it.broadcasts }

    /** Names of classes that live in the `nebula` module (used to qualify type names in scripts). */
    val scriptClassNames: Set<String> = all.map { it.name }.toSet() + "Vec2"
}
