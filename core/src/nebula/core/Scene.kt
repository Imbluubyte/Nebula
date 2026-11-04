package nebula.core

/** One node in the scene tree. Ids are never reused within a run. */
class SceneNode(val id: Long, var name: String, val type: String) {
    var parent: SceneNode? = null
    val children = ArrayList<SceneNode>()
    val props = LinkedHashMap<String, Any>()       // built-in properties (Double / String / Boolean)
    val tweaks = LinkedHashMap<String, Any>()      // values for the script's `tweak` variables
    val tags = LinkedHashSet<String>()
    var script: String? = null                      // path of the .nbs file attached to this node
    var instanceOf: String? = null                  // path of a .nscn this node was instanced from (editor only)

    fun path(): String {
        val parts = ArrayList<String>()
        var n: SceneNode? = this
        while (n != null) { parts.add(n.name); n = n.parent }
        return "/" + parts.reversed().joinToString("/")
    }

    fun f(key: String): Double = (props[key] as? Number)?.toDouble() ?: 0.0
    fun s(key: String): String = props[key]?.toString() ?: ""
    fun b(key: String): Boolean = props[key] as? Boolean ?: false

    fun child(name: String): SceneNode? = children.firstOrNull { it.name == name }
    fun walk(visit: (SceneNode) -> Unit) { visit(this); for (c in children.toList()) c.walk(visit) }
}

/** The tree plus id allocation and path lookup. Android-free so it can be tested on a desktop JVM. */
class SceneTree {
    private var nextId = 1L
    private val byId = HashMap<Long, SceneNode>()
    var root: SceneNode? = null
        private set

    fun get(id: Long): SceneNode? = byId[id]
    fun alive(id: Long) = byId.containsKey(id)

    fun create(type: String, name: String): SceneNode {
        require(NodeTypes.exists(type)) { "unknown node type '$type'" }
        val n = SceneNode(nextId++, name, type)
        for (p in NodeTypes.propsOf(type)) n.props[p.name] = p.default
        byId[n.id] = n
        return n
    }

    fun setRoot(n: SceneNode) { root = n }

    fun uniqueName(parent: SceneNode, wanted: String): String {
        if (parent.child(wanted) == null) return wanted
        var i = 2
        while (parent.child("$wanted$i") != null) i++
        return "$wanted$i"
    }

    fun add(parent: SceneNode, child: SceneNode, index: Int = -1) {
        child.parent?.children?.remove(child)
        child.name = uniqueName(parent, child.name)
        child.parent = parent
        if (index in 0..parent.children.size) parent.children.add(index, child) else parent.children.add(child)
    }

    /** Removes the node and its subtree from the tree; returns every removed node (deepest first). */
    fun remove(n: SceneNode): List<SceneNode> {
        val gone = ArrayList<SceneNode>()
        fun collect(x: SceneNode) { for (c in x.children) collect(c); gone.add(x) }
        collect(n)
        n.parent?.children?.remove(n)
        n.parent = null
        for (g in gone) byId.remove(g.id)
        if (root === n) root = null
        return gone
    }

    /**
     * Paths: "Name/Child" relative, "/World/Player" absolute (the root's own name is optional), ".." parent, "." self.
     */
    fun find(from: SceneNode, path: String): SceneNode? {
        var cur: SceneNode? = from
        var p = path
        var segs: List<String>
        if (p.startsWith("/")) {
            cur = root; segs = p.trimStart('/').split('/').filter { it.isNotEmpty() }
            if (segs.firstOrNull() == root?.name) segs = segs.drop(1)   // "/World/Jump" and "/Jump" both work
        } else segs = p.split('/')
        if (segs.isEmpty()) return cur
        for (seg in segs) {
            if (seg.isEmpty() || seg == ".") continue
            cur = if (seg == "..") cur?.parent else cur?.child(seg)
            if (cur == null) return null
        }
        return cur
    }

    fun tagged(tag: String): List<SceneNode> {
        val out = ArrayList<SceneNode>()
        root?.walk { if (tag in it.tags) out.add(it) }
        return out
    }

    fun all(): List<SceneNode> { val out = ArrayList<SceneNode>(); root?.walk { out.add(it) }; return out }
}
