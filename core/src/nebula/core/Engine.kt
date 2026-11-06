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
    /** Play / stop requests of `Sound` nodes (volume, pitch, loop). */
    private val soundCtl: (SoundCmd) -> Unit = { },
) {
    val tree = SceneTree()
    val input = Input()
    val saves = HashMap<String, String>()
    /** Sprite sheet animations (`.nanim`). Clear it when the files change. */
    val anims = AnimLibrary(readFile)
    var screenW = 1280.0
    var screenH = 720.0

    /** The view the game is drawn with: the world point at the centre of the screen and the zoom. Updated every frame (see updateCamera). */
    var camX = 640.0; private set
    var camY = 360.0; private set
    var camZoom = 1.0; private set
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
    private val tileTouching = HashSet<Long>()
    private val pendingFree = LinkedHashSet<Long>()
    private class AnimState(var set: String, var clip: String, var time: Double, var done: Boolean)
    private val animStates = HashMap<Long, AnimState>()
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
        updateCamera(0.0, true)
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
        animate(0.0)
        for (n in created) if (n.type == "Sound" && n.b("autoplay")) playNode(n)
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
        for (n in tree.all()) if (n.type == "Sound") soundCtl(SoundCmd(n.id, n.s("file"), 0.0, 1.0, false, true))
        sched.clear(); touching.clear(); tileTouching.clear(); pendingFree.clear(); animStates.clear(); pendingScene = null
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
        animate(dt)
        serviceSounds()
        serviceSpawnables(dt)
        physics(dt)
        updateCamera(dt, false)
        host?.pump(); check()
        flush()
    }

    private fun flush() {
        for (n in tree.all()) if (n.tileDirty) TileGrid.sync(n)
        if (pendingFree.isNotEmpty()) {
            val ids = pendingFree.toList(); pendingFree.clear()
            for (id in ids) tree.get(id)?.let { doFree(it) }
        }
        pendingScene?.let { p -> pendingScene = null; loadScene(p) }
    }

    private fun doFree(n: SceneNode) {
        for (x in n.walkList().asReversed()) {
            host?.freed(x.id); sched.remove(x.id); animStates.remove(x.id)
            if (x.type == "Sound") soundCtl(SoundCmd(x.id, x.s("file"), 0.0, 1.0, false, true))
        }
        check()
        tree.remove(n)
    }
    private fun SceneNode.walkList(): List<SceneNode> { val l = ArrayList<SceneNode>(); walk { l.add(it) }; return l }

    // ------------------------------------------------------------ animation and sound nodes
    /** Advances every Sprite that has an animation set: writes its `frame`, fires `animationFinished` when a `once` clip ends. */
    private fun animate(dt: Double) {
        for (n in tree.all()) {
            if (n.type != "Sprite") continue
            val path = n.s("animations")
            if (path.isEmpty()) { animStates.remove(n.id); continue }
            val clip = anims.get(path)?.clip(n.s("animation")) ?: continue
            val name = clip.name
            val st = animStates.getOrPut(n.id) { AnimState(path, "", 0.0, false) }
            if (st.set != path || st.clip != name) { st.set = path; st.clip = name; st.time = 0.0; st.done = false }
            else if (st.done && n.b("playing")) { st.time = 0.0; st.done = false }     // animate("x") again after it finished
            if (n.b("playing") && !st.done) st.time += dt * n.f("speed").coerceAtLeast(0.0)
            val (frame, finished) = clip.frameAt(st.time)
            n.props["frame"] = frame.toDouble()
            if (finished && !st.done) {
                st.done = true; n.props["playing"] = false
                host?.fire(n.id, "animationFinished", 0.0, 0.0, 0); check()
            }
        }
    }

    /** Sound nodes are driven by the runtime flags `_play` / `_stop` that Sound.start() and Sound.stop() set. */
    private fun serviceSounds() {
        for (n in tree.all()) {
            if (n.type != "Sound") continue
            if (n.b("_stop")) { n.props["_stop"] = false; soundCtl(SoundCmd(n.id, n.s("file"), 0.0, 1.0, false, true)) }
            if (n.b("_play")) { n.props["_play"] = false; playNode(n) }
        }
    }

    private fun playNode(n: SceneNode) {
        val file = n.s("file")
        if (file.isEmpty()) return
        soundCtl(SoundCmd(n.id, file, n.f("volume").coerceIn(0.0, 1.0), n.f("pitch").coerceIn(0.5, 2.0), n.b("loop"), false))
    }

    // ------------------------------------------------------------ geometry, hit-testing, physics
    fun worldPos(n: SceneNode): Pair<Double, Double> = n.worldPos()

    fun sizeOf(n: SceneNode): Pair<Double, Double> {
        val w = n.f("width"); val h = n.f("height")
        return (if (w > 0) w else 64.0) * n.f("scale").let { if (it == 0.0) 1.0 else it } to (if (h > 0) h else 64.0) * n.f("scale").let { if (it == 0.0) 1.0 else it }
    }

    /** Fingers currently on the screen (game coordinates): refreshes touches and which on-screen buttons are held. */
    fun setPointers(points: List<Pair<Double, Double>>) {
        val world = points.map { toWorld(it.first, it.second) }          // scripts' touches() are world positions
        input.touches.clear(); input.touches.addAll(world)
        input.virtual.clear()
        if (points.isEmpty()) return
        for (n in tree.all()) {
            if (n.type != "Button" || !n.b("visible")) continue
            val action = n.s("action"); if (action.isEmpty()) continue
            val (px, py) = worldPos(n); val w = n.f("width"); val h = n.f("height")
            val pts = if (n.isFixed()) points else world
            if (pts.any { Math.abs(it.first - px) <= w / 2 && Math.abs(it.second - py) <= h / 2 }) input.virtual.add(action)
        }
    }

    /** A point on the screen (game units, 0..screenW x 0..screenH) to the world point under it, through the camera. */
    fun toWorld(gx: Double, gy: Double): Pair<Double, Double> = (camX + (gx - screenW / 2) / camZoom) to (camY + (gy - screenH / 2) / camZoom)

    private fun cameraTarget(cam: SceneNode): SceneNode? {
        val t = cam.s("target").trim()
        if (t.isEmpty()) return null
        return if (t.startsWith("#")) tree.tagged(t.substring(1)).firstOrNull() else tree.find(cam, t)
    }

    /** Moves the view: follows the camera's target (smoothed, per axis, with offset), keeps it inside the limits, and writes the result back to the Camera node. */
    private fun updateCamera(dt: Double, snap: Boolean) {
        val cam = tree.all().firstOrNull { it.type == "Camera" }
        if (cam == null) { camX = screenW / 2; camY = screenH / 2; camZoom = 1.0; return }
        val zoom = cam.f("zoom").takeIf { it > 0 } ?: 1.0
        var (wx, wy) = cam.worldPos()
        val forceSnap = snap || cam.b("_snap")
        if (cam.b("_snap")) cam.props["_snap"] = false
        val follow = cam.b("follow")
        if (follow) {
            val target = cameraTarget(cam)
            if (target != null) {
                val (tx, ty) = target.worldPos()
                val tau = cam.f("smoothing")
                val k = if (forceSnap || tau <= 0.0) 1.0 else 1.0 - Math.exp(-dt / tau)
                if (cam.b("followX")) wx += (tx + cam.f("offsetX") - wx) * k
                if (cam.b("followY")) wy += (ty + cam.f("offsetY") - wy) * k
            }
        }
        val limits = cam.b("limits")
        if (limits) {
            val hw = screenW / 2 / zoom; val hh = screenH / 2 / zoom
            val l = cam.f("limitLeft"); val r = cam.f("limitRight"); val t = cam.f("limitTop"); val b = cam.f("limitBottom")
            wx = if (r - l <= 2 * hw) (l + r) / 2 else wx.coerceIn(l + hw, r - hw)       // a level smaller than the screen is centred
            wy = if (b - t <= 2 * hh) (t + b) / 2 else wy.coerceIn(t + hh, b - hh)
        }
        if (follow || limits) {
            val (cx, cy) = cam.worldPos()
            cam.props["x"] = cam.f("x") + (wx - cx); cam.props["y"] = cam.f("y") + (wy - cy)     // so the node's own position stays what the view really is
        }
        camX = wx; camY = wy; camZoom = zoom
    }

    /** A tap at a screen position (game units): the topmost visible touchable node under it gets `tapped` (fixed nodes are hit on the screen, the rest through the camera). */
    fun tap(gx: Double, gy: Double): Boolean {
        val (wx, wy) = toWorld(gx, gy)
        var tx = wx; var ty = wy
        val hit = tree.all().filter { it.b("touchable") && it.b("visible") }.sortedByDescending { it.f("layer") }.firstOrNull {
            val (px, py) = worldPos(it); val (w, h) = sizeOf(it)
            val fixed = it.isFixed()
            val x = if (fixed) gx else wx; val y = if (fixed) gy else wy
            val inside = Math.abs(x - px) <= w / 2 && Math.abs(y - py) <= h / 2
            if (inside) { tx = x; ty = y }
            inside
        } ?: return false
        host?.fire(hit.id, "tapped", tx, ty, 0); check()
        return true
    }

    /** SpawnableBody: age it, and when its lifetime runs out tell the script (`expired`) and free it. */
    private fun serviceSpawnables(dt: Double) {
        for (n in tree.all()) {
            if (n.type != "SpawnableBody") continue
            val age = n.f("_age") + dt
            n.props["_age"] = age
            val life = n.f("lifetime")
            if (life > 0 && age >= life && n.id !in pendingFree && !n.b("_dead")) {
                n.props["_dead"] = true
                host?.fire(n.id, "expired", 0.0, 0.0, 0); check()
                pendingFree.add(n.id)
            }
        }
    }

    /**
     * A body's hitbox as [centre offset x, centre offset y, width, height]. A CollisionBox child replaces the body's own width x height box
     * (its x, y is the offset from the body's position); the first one wins. Without one the box is centred on the body.
     */
    private fun hitbox(b: SceneNode): DoubleArray {
        val box = b.children.firstOrNull { it.type == "CollisionBox" }
            ?: return doubleArrayOf(0.0, 0.0, b.f("width"), b.f("height"))
        return doubleArrayOf(box.f("x"), box.f("y"), box.f("width").coerceAtLeast(0.0), box.f("height").coerceAtLeast(0.0))
    }

    private fun physics(dt: Double) {
        val all = tree.all()
        val bodies = all.filter { it.type in NodeTypes.bodyTypes }
        val maps = all.filter { it.type == "TileMap" && it.b("solid") }.map { TileSolid(it) }
        val tileContacts = HashSet<Long>()
        for (b in bodies) {
            b.props["onGround"] = false
            if (b.b("static")) continue
            b.props["vy"] = b.f("vy") + b.f("gravity") * dt
            b.props["x"] = b.f("x") + b.f("vx") * dt
            if (maps.isNotEmpty()) tileCollide(b, maps, true, tileContacts)       // each axis on its own, so bodies slide along flat tiles without snagging
            b.props["y"] = b.f("y") + b.f("vy") * dt
            if (maps.isNotEmpty()) tileCollide(b, maps, false, tileContacts)
        }
        for (key in tileContacts) if (key !in tileTouching) { host?.fire(key / 1_000_000L, "collided", 0.0, 0.0, key % 1_000_000L); check() }
        tileTouching.retainAll(tileContacts); tileTouching.addAll(tileContacts)
        // world positions are looked up once per frame (not once per pair) so hundreds of bodies stay cheap
        val n = bodies.size
        val wx = DoubleArray(n); val wy = DoubleArray(n)          // centre of each hitbox (a CollisionBox child moves it away from the body's own position)
        val offX = DoubleArray(n); val offY = DoubleArray(n); val ww = DoubleArray(n); val hh = DoubleArray(n)
        for (i in 0 until n) {
            val (px, py) = worldPos(bodies[i]); val hb = hitbox(bodies[i])
            offX[i] = hb[0]; offY[i] = hb[1]; ww[i] = hb[2]; hh[i] = hb[3]
            wx[i] = px + offX[i]; wy[i] = py + offY[i]
        }
        val overlapping = HashSet<Long>()
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = bodies[i]; val b = bodies[j]
            val dx = wx[i] - wx[j]; val dy = wy[i] - wy[j]
            val ox = (ww[i] + ww[j]) / 2 - Math.abs(dx)
            if (ox <= 0) continue
            val oy = (hh[i] + hh[j]) / 2 - Math.abs(dy)
            if (oy <= 0) continue
            val key = minOf(a.id, b.id) * 1_000_000L + maxOf(a.id, b.id)
            overlapping.add(key)
            if (key !in touching) {
                host?.fire(a.id, "collided", 0.0, 0.0, b.id); check()
                host?.fire(b.id, "collided", 0.0, 0.0, a.id); check()
            }
            val aSolid = a.b("solid") && a.b("static"); val bSolid = b.b("solid") && b.b("static")
            if (bSolid && !a.b("static")) { resolve(a, dx, dy, ox, oy); worldPos(a).let { wx[i] = it.first + offX[i]; wy[i] = it.second + offY[i] } }
            else if (aSolid && !b.b("static")) { resolve(b, -dx, -dy, ox, oy); worldPos(b).let { wx[j] = it.first + offX[j]; wy[j] = it.second + offY[j] } }
        }
        touching.retainAll(overlapping); touching.addAll(overlapping)
    }

    /** A solid TileMap seen by the physics step: where its cells are in the world and which of them stop bodies. */
    private class TileSolid(val node: SceneNode) {
        val grid = TileGrid.of(node)
        val tw = node.f("tileWidth").coerceAtLeast(1.0); val th = node.f("tileHeight").coerceAtLeast(1.0)
        val left: Double; val top: Double
        val only = TileGrid.solidSet(node)
        init { val (x, y) = node.worldPos(); left = x - grid.cols * tw / 2; top = y - grid.rows * th / 2 }
        fun solidAt(c: Int, r: Int): Boolean { val t = grid[c, r]; return t != 0 && (only == null || t in only) }
    }

    /** Pushes body [b] out of solid tiles along one axis (the other axis was not moved yet, so a body on flat ground never snags a seam). */
    private fun tileCollide(b: SceneNode, maps: List<TileSolid>, horizontal: Boolean, contacts: MutableSet<Long>) {
        val eps = 1e-3                                  // touching is not overlapping; keeps a resting body from being shoved sideways
        val hb = hitbox(b)
        val bounce = b.f("bounce"); val hw = hb[2] / 2; val hh = hb[3] / 2
        for (m in maps) {
            val (wx, wy) = b.worldPos()
            var x = wx + hb[0]; var y = wy + hb[1]          // centre of the hitbox; shifting it shifts the body by the same amount
            val c0 = Math.floor((x - hw - m.left) / m.tw).toInt().coerceAtLeast(0); val c1 = Math.floor((x + hw - m.left - eps) / m.tw).toInt().coerceAtMost(m.grid.cols - 1)
            val r0 = Math.floor((y - hh - m.top) / m.th).toInt().coerceAtLeast(0); val r1 = Math.floor((y + hh - m.top - eps) / m.th).toInt().coerceAtMost(m.grid.rows - 1)
            var hit = false
            for (r in r0..r1) for (c in c0..c1) {
                if (!m.solidAt(c, r)) continue
                val cl = m.left + c * m.tw; val ct = m.top + r * m.th; val cr = cl + m.tw; val cb = ct + m.th
                if (x + hw <= cl + eps || x - hw >= cr - eps || y + hh <= ct + eps || y - hh >= cb - eps) continue
                hit = true
                if (horizontal) {
                    val vx = b.f("vx")
                    val shift = if (vx > 0 || (vx == 0.0 && x < (cl + cr) / 2)) cl - (x + hw) else cr - (x - hw)
                    x += shift; b.props["x"] = b.f("x") + shift
                    if (vx != 0.0) b.props["vx"] = -vx * bounce
                } else {
                    val vy = b.f("vy"); val down = vy > 0 || (vy == 0.0 && y < (ct + cb) / 2)
                    val shift = if (down) ct - (y + hh) else cb - (y - hh)
                    y += shift; b.props["y"] = b.f("y") + shift
                    if (down) { b.props["onGround"] = true; if (vy > 0) b.props["vy"] = -vy * bounce } else if (vy < 0) b.props["vy"] = 0.0
                }
            }
            if (hit) contacts.add(b.id * 1_000_000L + m.node.id)
        }
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

    /** "tile:col,row" (1-based) addresses one cell of a TileMap through the ordinary get/set calls. */
    private fun tileCell(prop: String): Pair<Int, Int>? {
        val p = prop.substring(5).split(',')
        if (p.size != 2) return null
        return (p[0].trim().toIntOrNull() ?: return null) - 1 to (p[1].trim().toIntOrNull() ?: return null) - 1
    }
    private fun slot(n: SceneNode, prop: String): Any? =
        if (prop.startsWith("tweak:")) n.tweaks[prop.substring(6)]
        else if (prop == "name") n.name
        else if (prop == "path") n.path()
        else if (prop.startsWith("tile:")) { if (n.type == "TileMap") tileCell(prop)?.let { TileGrid.of(n)[it.first, it.second] } else null }
        else n.props[prop]
    fun apiGetF(id: Long, prop: String): Double = (tree.get(id)?.let { slot(it, prop) } as? Number)?.toDouble() ?: 0.0
    fun apiGetS(id: Long, prop: String): String = tree.get(id)?.let { if (prop == "tiles") TileGrid.sync(it); slot(it, prop) }?.toString() ?: ""
    fun apiGetB(id: Long, prop: String): Boolean = tree.get(id)?.let { slot(it, prop) } as? Boolean ?: false
    fun apiSet(id: Long, prop: String, value: Any) {
        val n = tree.get(id) ?: return
        when {
            prop == "name" -> n.name = value.toString()
            prop.startsWith("tile:") -> { if (n.type == "TileMap") tileCell(prop)?.let { (c, r) ->
                val g = TileGrid.of(n); val v = ((value as? Number)?.toInt() ?: 0).coerceAtLeast(0)
                if (c in 0 until g.cols && r in 0 until g.rows && g[c, r] != v) { g[c, r] = v; n.tileDirty = true }
            } }
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
