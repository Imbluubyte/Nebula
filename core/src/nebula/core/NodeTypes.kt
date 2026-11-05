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
            b("visible", true), b("touchable", false)), listOf(BcDef("tapped", listOf("Float", "Float"))), "+"),
        NodeType("Sprite", "Node2D", listOf(s("image", "", "image"), b("flipX", false), f("width", 0.0), f("height", 0.0),
            f("hframes", 1.0), f("vframes", 1.0), f("frame", 1.0),
            s("animations", "", "animations"), s("animation", "", "animation"), b("playing", true), f("speed", 1.0)),
            listOf(BcDef("animationFinished")), "S"),
        NodeType("Shape", "Node2D", listOf(s("shape", "rect", "shape"), f("width", 64.0), f("height", 64.0), s("color", "#4FC3F7", "color")), icon = "R"),
        NodeType("Label", "Node2D", listOf(s("text", "Label"), f("fontSize", 24.0), s("color", "#FFFFFF", "color")), icon = "T"),
        NodeType("Button", "Node2D", listOf(s("text", "Button"), f("width", 160.0), f("height", 64.0),
            f("fontSize", 24.0), s("color", "#3949AB", "color"), s("action", "", "action")), icon = "B"),
        NodeType("Body", "Node2D", listOf(
            f("vx", 0.0), f("vy", 0.0), f("gravity", 0.0), f("width", 32.0), f("height", 32.0),
            b("solid", false), b("static", false), f("bounce", 0.0), b("onGround", false), s("color", "", "color")),
            listOf(BcDef("collided", listOf("Node"))), "P"),
        // A Body made to be spawned over and over (bullets, enemies, pickups). The engine counts its lifetime down;
        // health, damage and the launch/chase helpers live in the script API (api/nebula.sn).
        NodeType("SpawnableBody", "Body", listOf(
            f("lifetime", 0.0, "seconds until it expires by itself, 0 = never"), f("health", 1.0), f("maxHealth", 1.0), f("damage", 1.0)),
            listOf(BcDef("expired"), BcDef("died"), BcDef("damaged", listOf("Float"))), "*"),
        NodeType("Camera", "Node2D", listOf(f("zoom", 1.0)), icon = "C"),
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
