package nebula.core

/**
 * Editable scene for the studio: a SceneTree plus undo/redo (text snapshots) and the edit operations.
 * Instanced scenes appear as a single node (`instanceOf` set) whose own properties are overrides.
 */
class SceneEditor(private val readFile: (String) -> String?) {
    var tree = SceneTree(); private set
    var title = "Scene"
    var path: String? = null
    var dirty = false; private set
    private val undoStack = ArrayList<String>()
    private val redoStack = ArrayList<String>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun load(path: String, text: String) { this.path = path; restore(text); undoStack.clear(); redoStack.clear(); dirty = false }

    fun newScene(path: String, title: String, rootType: String = "Node2D") {
        this.path = path; this.title = title
        tree = SceneTree(); tree.setRoot(tree.create(rootType, "Root"))
        undoStack.clear(); redoStack.clear(); dirty = true
    }

    fun toText(): String = SceneFile.write(SceneFile.fromTree(title, tree.root ?: tree.create("Node2D", "Root")))
    fun markSaved() { dirty = false }

    private fun restore(text: String) {
        val def = SceneFile.parse(text)
        title = def.title
        tree = SceneTree()
        tree.setRoot(buildNode(def.root, null))
    }

    private fun buildNode(def: NodeDef, parent: SceneNode?): SceneNode {
        var d = def
        if (def.instance != null) {
            val inner = readFile(def.instance!!)?.let { runCatching { SceneFile.parse(it) }.getOrNull() }
            if (inner != null) d = inner.root.also { it.name = def.name }
        }
        val n = tree.create(d.type, def.name)
        n.props.putAll(d.props); n.tweaks.putAll(d.tweaks); n.tags.addAll(d.tags); n.script = d.script
        if (def.instance != null) { n.instanceOf = def.instance; n.props.putAll(def.props); n.tweaks.putAll(def.tweaks); n.tags.addAll(def.tags); if (def.script != null) n.script = def.script }
        if (parent != null) tree.add(parent, n)
        if (def.instance == null) for (c in d.children) buildNode(c, n)
        return n
    }

    /** Runs [edit] as one undoable step. */
    fun <T> edit(edit: () -> T): T {
        undoStack.add(toText()); if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear(); dirty = true
        return edit()
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.add(toText()); restore(prev); dirty = true; return true
    }
    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.add(toText()); restore(next); dirty = true; return true
    }

    // ---- operations (each is one undo step)
    fun addNode(parent: SceneNode, type: String, name: String = type): SceneNode = edit {
        val n = tree.create(type, name); tree.add(parent, n)
        if (type == "Button") n.props["fixed"] = true      // on-screen buttons stay on the screen when a camera moves
        n
    }

    fun addInstance(parent: SceneNode, scenePath: String, name: String): SceneNode? = edit {
        val inner = readFile(scenePath)?.let { runCatching { SceneFile.parse(it) }.getOrNull() } ?: return@edit null
        val n = tree.create(inner.root.type, name)
        n.props.putAll(inner.root.props); n.tweaks.putAll(inner.root.tweaks); n.tags.addAll(inner.root.tags)
        n.script = inner.root.script; n.instanceOf = scenePath
        tree.add(parent, n); n
    }

    fun delete(n: SceneNode): Boolean {
        if (n === tree.root) return false
        edit { tree.remove(n) }; return true
    }

    fun rename(n: SceneNode, name: String) {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { return }
        if (clean == n.name) return
        edit { n.name = tree.uniqueName(n.parent ?: return@edit, clean) }
    }

    fun duplicate(n: SceneNode): SceneNode? {
        val parent = n.parent ?: return null
        return edit {
            val copy = copyTree(n)
            tree.add(parent, copy, parent.children.indexOf(n) + 1); copy
        }
    }

    private fun copyTree(n: SceneNode): SceneNode {
        val c = tree.create(n.type, n.name)
        c.props.putAll(n.props); c.tweaks.putAll(n.tweaks); c.tags.addAll(n.tags); c.script = n.script; c.instanceOf = n.instanceOf
        for (k in n.children) { val kc = copyTree(k); kc.parent = c; c.children.add(kc) }
        return c
    }

    fun move(n: SceneNode, delta: Int): Boolean {
        val p = n.parent ?: return false
        val i = p.children.indexOf(n); val j = i + delta
        if (j !in p.children.indices) return false
        edit { p.children.removeAt(i); p.children.add(j, n) }; return true
    }

    /** Make [n] the last child of the node above it (indent). */
    fun indent(n: SceneNode): Boolean {
        val p = n.parent ?: return false
        val i = p.children.indexOf(n); if (i <= 0) return false
        val newParent = p.children[i - 1]
        edit { tree.add(newParent, n) }; return true
    }

    /** Make [n] a sibling after its parent (outdent). */
    fun outdent(n: SceneNode): Boolean {
        val p = n.parent ?: return false; val g = p.parent ?: return false
        edit { tree.add(g, n, g.children.indexOf(p) + 1) }; return true
    }

    fun reparent(n: SceneNode, newParent: SceneNode, index: Int = -1): Boolean {
        var x: SceneNode? = newParent
        while (x != null) { if (x === n) return false; x = x.parent }
        if (n === tree.root) return false
        edit { tree.add(newParent, n, index) }; return true
    }

    fun setProp(n: SceneNode, key: String, value: Any) { if (n.props[key] != value) edit { n.props[key] = value } }
    fun setTweak(n: SceneNode, key: String, value: Any) { if (n.tweaks[key] != value) edit { n.tweaks[key] = value } }
    fun setScript(n: SceneNode, path: String?) { if (n.script != path) edit { n.script = path } }
    fun setTags(n: SceneNode, tags: List<String>) { edit { n.tags.clear(); n.tags.addAll(tags) } }

    /** Moves without an undo step per tiny change (used while dragging in the viewport). */
    fun beginDrag() { undoStack.add(toText()); if (undoStack.size > 200) undoStack.removeAt(0); redoStack.clear(); dirty = true }
}
