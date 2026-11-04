package nebula.core

import nebula.lang.ScriptMeta

/** What the engine needs from the script runtime (libscripts.so through JNA on Android, a fake in tests). */
interface ScriptHost {
    fun attach(id: Long, script: String)
    fun ready(id: Long)
    fun frame(id: Long, delta: Double)
    fun tick(id: Long, rate: Int, delta: Double)
    fun every(id: Long, ms: Int, delta: Double)
    fun fire(id: Long, name: String, a: Double, b: Double, other: Long)
    fun freed(id: Long)
    fun pump()
    /** Text of the last script error since the previous call, or null. */
    fun takeError(): String?
}

/** Actions come from the keyboard (`keys`) and from on-screen Buttons that have an `action` (`virtual`). */
class Input {
    val keys = HashSet<String>()
    val virtual = HashSet<String>()
    val touches = ArrayList<Pair<Double, Double>>()      // game coordinates
    fun isDown(action: String) = action in keys || action in virtual
    fun axis(neg: String, pos: String): Double = (if (isDown(pos)) 1.0 else 0.0) - (if (isDown(neg)) 1.0 else 0.0)
}

/**
 * The running game: scene tree, script scheduling (onFrame / onTick / onEvery), simple 2D physics, input,
 * and the node API the scripts call back into. Android-free.
 */
class Engine(
    private val readFile: (String) -> String?,
    metas: Map<String, ScriptMeta>,
    var host: ScriptHost?,
    private val logSink: (String) -> Unit = { println(it) },
    private val mapError: (String) -> String = { it },
    private val soundSink: (String) -> Unit = { },
) {
    val tree = SceneTree()
    val input = Input()
    val saves = HashMap<String, String>()
    var screenW = 1280.0
    var screenH = 720.0
    var time = 0.0
        private set
    var sceneTitle = ""
        private set
    private val metasByName = metas
    private val metasByFile = metas.values.associateBy { it.file }

    private class Sched(val id: Long, val frame: Boolean, val ticks: IntArray, val every: IntArray) {
        val tickAcc = DoubleArray(ticks.size); val everyAcc = DoubleArray(every.size)
    }
    private val sched = LinkedHashMap<Long, Sched>()
    private val touching = HashSet<Long>()
    private val pendingFree = LinkedHashSet<Long>()
    private var pendingScene: String? = null

    fun log(msg: String) = logSink(msg)
    fun playSound(file: String) = soundSink(file)

    // ------------------------------------------------------------ loading
    fun loadScene(path: String): Boolean {
        shutdown()
        val def = try { SceneFile.parse(readFile(path) ?: run { log("error: scene '$path' not found"); return false }) }
                  catch (e: SceneParseException) { log("error: $path: ${e.message}"); return false }
        sceneTitle = def.title
        val created = ArrayList<SceneNode>()
        val root = build(def.root, null, created, 0) ?: return false
        tree.setRoot(root)
        start(created)
        return true
    }

    fun spawn(path: String, parent: SceneNode): SceneNode? {
        val def = try { SceneFile.parse(readFile(path) ?: run { log("error: scene '$path' not found"); return null }) }
                  catch (e: SceneParseException) { log("error: $path: ${e.message}"); return null }
        val created = ArrayList<SceneNode>()
        val n = build(def.root, parent, created, 0) ?: return null
        start(created)
        return n
    }

    private fun build(def: NodeDef, parent: SceneNode?, created: MutableList<SceneNode>, depth: Int): SceneNode? {
        if (depth > 32) { log("error: scenes are nested too deeply (does a scene contain itself?)"); return null }
        var d = def
        var overrideDef: NodeDef? = null
        if (def.instance != null) {
            val inner = try { SceneFile.parse(readFile(def.instance!!) ?: run { log("error: scene '${def.instance}' not found"); return null }) }
                        catch (e: SceneParseException) { log("error: ${def.instance}: ${e.message}"); return null }
            d = inner.root; overrideDef = def
        }
        val n = tree.create(d.type, overrideDef?.name ?: d.name)
        n.props.putAll(d.props); n.tweaks.putAll(d.tweaks); n.tags.addAll(d.tags); n.script = d.script
        if (overrideDef != null) { n.props.putAll(overrideDef.props); n.tweaks.putAll(overrideDef.tweaks); n.tags.addAll(overrideDef.tags)
            if (overrideDef.script != null) n.script = overrideDef.script }
        if (parent != null) tree.add(parent, n)
        created.add(n)
        for (c in d.children) build(c, n, created, depth + 1)
        return n
    }

    /** Attach scripts, then ready() children before parents, then register scheduling. */
    private fun start(created: List<SceneNode>) {
        val h = host
        for (n in created) {
            val path = n.script ?: continue
            val meta = metasByFile[path]
            if (meta == null) { log("error: script '$path' is not part of the build (build the project again)"); continue }
            h?.attach(n.id, meta.name); check()
            registerSched(n.id, meta)
        }
        for (n in created.asReversed()) if (n.script != null && metasByFile[n.script] != null) { h?.ready(n.id); check() }
    }

    private fun registerSched(id: Long, meta: ScriptMeta) {
        val chain = ArrayList<ScriptMeta>(); var m: ScriptMeta? = meta
        while (m != null && chain.size < 64) { chain.add(m); m = metasByName[m.base] }
        val ticks = chain.flatMap { it.ticks }.distinct().toIntArray()
        val every = chain.flatMap { it.everyMs }.distinct().toIntArray()
        val frame = chain.any { it.frame }
        if (frame || ticks.isNotEmpty() || every.isNotEmpty()) sched[id] = Sched(id, frame, ticks, every)
    }

    fun shutdown() {
        for (n in tree.all().asReversed()) { host?.freed(n.id) }
        check()
        sched.clear(); touching.clear(); pendingFree.clear(); pendingScene = null
        tree.root?.let { tree.remove(it) }
    }

    private fun check() {
        host?.takeError()?.let { log(mapError(it).let { e -> "script error: $e" }) }
    }

    // ------------------------------------------------------------ per frame
    fun update(dt: Double) {
        time += dt
        val h = host
        if (h != null) {
            for (s in sched.values.toList()) {
                if (!tree.alive(s.id)) continue
                for (i in s.ticks.indices) {
                    val step = 1.0 / s.ticks[i]
                    s.tickAcc[i] += dt
                    var guard = 0
                    while (s.tickAcc[i] >= step && guard++ < 8) { s.tickAcc[i] -= step; h.tick(s.id, s.ticks[i], step); check() }
                    if (s.tickAcc[i] > step * 8) s.tickAcc[i] = 0.0
                }
                for (i in s.every.indices) {
                    val step = s.every[i] / 1000.0
                    s.everyAcc[i] += dt
                    var guard = 0
                    while (s.everyAcc[i] >= step && guard++ < 4) { s.everyAcc[i] -= step; h.every(s.id, s.every[i], step); check() }
                    if (s.everyAcc[i] > step * 4) s.everyAcc[i] = 0.0
                }
                if (s.frame && tree.alive(s.id)) { h.frame(s.id, dt); check() }
            }
        }
        physics(dt)
        host?.pump(); check()
        flush()
    }

    private fun flush() {
        if (pendingFree.isNotEmpty()) {
            val ids = pendingFree.toList(); pendingFree.clear()
            for (id in ids) tree.get(id)?.let { doFree(it) }
        }
        pendingScene?.let { p -> pendingScene = null; loadScene(p) }
    }

    private fun doFree(n: SceneNode) {
        for (x in n.walkList().asReversed()) { host?.freed(x.id); sched.remove(x.id) }
        check()
        tree.remove(n)
    }
    private fun SceneNode.walkList(): List<SceneNode> { val l = ArrayList<SceneNode>(); walk { l.add(it) }; return l }

    // ------------------------------------------------------------ geometry, hit-testing, physics
    fun worldPos(n: SceneNode): Pair<Double, Double> {
        var x = 0.0; var y = 0.0; var c: SceneNode? = n
        while (c != null) { if (NodeTypes.isA(c.type, "Node2D")) { x += c.f("x"); y += c.f("y") }; c = c.parent }
        return x to y
    }

    fun sizeOf(n: SceneNode): Pair<Double, Double> {
        val w = n.f("width"); val h = n.f("height")
        return (if (w > 0) w else 64.0) * n.f("scale").let { if (it == 0.0) 1.0 else it } to (if (h > 0) h else 64.0) * n.f("scale").let { if (it == 0.0) 1.0 else it }
    }

    /** Fingers currently on the screen (game coordinates): refreshes touches and which on-screen buttons are held. */
    fun setPointers(points: List<Pair<Double, Double>>) {
        input.touches.clear(); input.touches.addAll(points)
        input.virtual.clear()
        if (points.isEmpty()) return
        for (n in tree.all()) {
            if (n.type != "Button" || !n.b("visible")) continue
            val action = n.s("action"); if (action.isEmpty()) continue
            val (px, py) = worldPos(n); val w = n.f("width"); val h = n.f("height")
            if (points.any { Math.abs(it.first - px) <= w / 2 && Math.abs(it.second - py) <= h / 2 }) input.virtual.add(action)
        }
    }

    /** A tap at world coordinates: the topmost visible touchable node under it gets `tapped`. */
    fun tap(wx: Double, wy: Double): Boolean {
        val hit = tree.all().filter { it.b("touchable") && it.b("visible") }.sortedByDescending { it.f("layer") }.firstOrNull {
            val (px, py) = worldPos(it); val (w, h) = sizeOf(it)
            Math.abs(wx - px) <= w / 2 && Math.abs(wy - py) <= h / 2
        } ?: return false
        host?.fire(hit.id, "tapped", wx, wy, 0); check()
        return true
    }

    private fun physics(dt: Double) {
        val bodies = tree.all().filter { it.type == "Body" }
        for (b in bodies) {
            b.props["onGround"] = false
            if (b.b("static")) continue
            b.props["vy"] = b.f("vy") + b.f("gravity") * dt
            b.props["x"] = b.f("x") + b.f("vx") * dt
            b.props["y"] = b.f("y") + b.f("vy") * dt
        }
        val overlapping = HashSet<Long>()
        for (i in bodies.indices) for (j in i + 1 until bodies.size) {
            val a = bodies[i]; val b = bodies[j]
            val (ax, ay) = worldPos(a); val (bx, by) = worldPos(b)
            val dx = ax - bx; val dy = ay - by
            val ox = (a.f("width") + b.f("width")) / 2 - Math.abs(dx)
            val oy = (a.f("height") + b.f("height")) / 2 - Math.abs(dy)
            if (ox <= 0 || oy <= 0) continue
            val key = minOf(a.id, b.id) * 1_000_000L + maxOf(a.id, b.id)
            overlapping.add(key)
            if (key !in touching) {
                host?.fire(a.id, "collided", 0.0, 0.0, b.id); check()
                host?.fire(b.id, "collided", 0.0, 0.0, a.id); check()
            }
            val aSolid = a.b("solid") && a.b("static"); val bSolid = b.b("solid") && b.b("static")
            if (bSolid && !a.b("static")) resolve(a, dx, dy, ox, oy)
            else if (aSolid && !b.b("static")) resolve(b, -dx, -dy, ox, oy)
        }
        touching.retainAll(overlapping); touching.addAll(overlapping)
    }

    private fun resolve(d: SceneNode, dx: Double, dy: Double, ox: Double, oy: Double) {
        val bounce = d.f("bounce")
        if (ox < oy) {
            d.props["x"] = d.f("x") + (if (dx < 0) -ox else ox)
            d.props["vx"] = -d.f("vx") * bounce
        } else if (dy < 0) {
            d.props["y"] = d.f("y") - oy
            if (d.f("vy") > 0) d.props["vy"] = -d.f("vy") * bounce
            d.props["onGround"] = true
        } else {
            d.props["y"] = d.f("y") + oy
            if (d.f("vy") < 0) d.props["vy"] = 0.0
        }
    }

    // ------------------------------------------------------------ API the scripts call (externs)
    fun apiFind(from: Long, path: String): Long { val n = tree.get(from) ?: return 0; return tree.find(n, path)?.id ?: 0 }
    fun apiParent(id: Long) = tree.get(id)?.parent?.id ?: 0
    fun apiType(id: Long) = tree.get(id)?.type ?: ""
    fun apiChildCount(id: Long) = (tree.get(id)?.children?.size ?: 0).toLong()
    fun apiChildAt(id: Long, i: Long) = tree.get(id)?.children?.getOrNull((i - 1).toInt())?.id ?: 0
    fun apiAdd(parent: Long, kind: String, name: String): Long {
        val p = tree.get(parent) ?: return 0
        if (!NodeTypes.exists(kind)) { log("error: unknown node type '$kind'"); return 0 }
        val n = tree.create(kind, name); tree.add(p, n); return n.id
    }
    fun apiSpawn(scene: String, parent: Long): Long { val p = tree.get(parent) ?: return 0; return spawn(scene, p)?.id ?: 0 }
    fun apiFree(id: Long) { if (tree.alive(id)) pendingFree.add(id) }
    fun apiAlive(id: Long) = tree.alive(id) && id !in pendingFree
    fun apiHas(id: Long, prop: String): Boolean = tree.get(id)?.let { if (prop.startsWith("tweak:")) it.tweaks.containsKey(prop.substring(6)) else it.props.containsKey(prop) } ?: false

    private fun slot(n: SceneNode, prop: String): Any? = if (prop.startsWith("tweak:")) n.tweaks[prop.substring(6)] else if (prop == "name") n.name else n.props[prop]
    fun apiGetF(id: Long, prop: String): Double = (tree.get(id)?.let { slot(it, prop) } as? Number)?.toDouble() ?: 0.0
    fun apiGetS(id: Long, prop: String): String = tree.get(id)?.let { slot(it, prop) }?.toString() ?: ""
    fun apiGetB(id: Long, prop: String): Boolean = tree.get(id)?.let { slot(it, prop) } as? Boolean ?: false
    fun apiSet(id: Long, prop: String, value: Any) {
        val n = tree.get(id) ?: return
        when {
            prop == "name" -> n.name = value.toString()
            prop.startsWith("tweak:") -> n.tweaks[prop.substring(6)] = value
            else -> n.props[prop] = value
        }
    }
    fun apiTag(id: Long, tag: String, on: Boolean) { tree.get(id)?.let { if (on) it.tags.add(tag) else it.tags.remove(tag) } }
    fun apiHasTag(id: Long, tag: String) = tree.get(id)?.tags?.contains(tag) ?: false
    fun apiTaggedCount(tag: String) = tree.tagged(tag).size.toLong()
    fun apiTaggedAt(tag: String, i: Long) = tree.tagged(tag).getOrNull((i - 1).toInt())?.id ?: 0
    fun apiGoto(scene: String) { pendingScene = scene }
    fun apiDown(action: String) = input.isDown(action)
    fun apiAxis(neg: String, pos: String) = input.axis(neg, pos)
    fun apiTouchCount() = input.touches.size.toLong()
    fun apiTouchX(i: Long) = input.touches.getOrNull((i - 1).toInt())?.first ?: 0.0
    fun apiTouchY(i: Long) = input.touches.getOrNull((i - 1).toInt())?.second ?: 0.0
}
