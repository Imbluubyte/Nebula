import nebula.core.*
import nebula.lang.*
import java.io.File

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "ok   " else "FAIL ") + name + (if (!ok && detail.isNotEmpty()) "\n     $detail" else ""))
    if (!ok) failures++
}

class FakeHost : ScriptHost {
    val calls = ArrayList<String>()
    override fun attach(id: Long, script: String) { calls.add("attach $id $script") }
    override fun ready(id: Long) { calls.add("ready $id") }
    override fun frame(id: Long, delta: Double) { calls.add("frame $id") }
    override fun tick(id: Long, rate: Int, delta: Double) { calls.add("tick $id $rate") }
    override fun every(id: Long, ms: Int, delta: Double) { calls.add("every $id $ms") }
    override fun fire(id: Long, name: String, a: Double, b: Double, other: Long) { calls.add("fire $id $name $other") }
    override fun freed(id: Long) { calls.add("freed $id") }
    override fun pump() {}
    override fun takeError(): String? = null
}

fun main(args: Array<String>) {
    val snc = args.getOrNull(0) ?: "snc"
    val apiDir = File(args.getOrNull(1) ?: "api")
    val tmp = File(args.getOrNull(2) ?: "/tmp/nebula-test").also { it.deleteRecursively(); it.mkdirs() }

    // --- scene file
    val text = "scene \"T\"\nnode W Node2D\n\tnode P Body script \"scripts/p.nbs\" tags \"a\" \"b\"\n\t\tx = 5\n\t\ttweak speed = 2.5\n\t\ttweak name = \"hi\"\n\tnode E scene \"e.nscn\"\n\t\ty = 3\n"
    val def = SceneFile.parse(text)
    check("scene parse", def.root.children.size == 2 && def.root.children[0].tags == listOf("a", "b") && def.root.children[1].instance == "e.nscn")
    check("scene roundtrip", SceneFile.parse(SceneFile.write(def)).let { SceneFile.write(it) } == SceneFile.write(def))
    check("scene bad type", runCatching { SceneFile.parse("scene \"x\"\nnode A Wat\n") }.isFailure)

    // --- tree paths
    val tree = SceneTree()
    val root = tree.create("Node2D", "Root"); tree.setRoot(root)
    val w = tree.create("Node2D", "World"); tree.add(root, w)
    val p = tree.create("Body", "Player"); tree.add(w, p)
    val e = tree.create("Body", "Player"); tree.add(w, e)
    check("unique names", e.name == "Player2")
    check("find abs/rel/parent", tree.find(p, "/World/Player2") === e && tree.find(p, "../Player2") === e && tree.find(w, "Player") === p && tree.find(p, "..") === w)
    check("find missing", tree.find(p, "Nope") == null)

    // --- transpiler diagnostics
    fun lowerOne(src: String) = Transpiler.lower(listOf(ScriptSource("t.nbs", src)))
    check("bad header", lowerOne("class X:\n").diags.isNotEmpty())
    check("dup onEvery", lowerOne("script A of Node2D:\n\tfn onEvery(2):\n\t\tpass\n\tfn onEvery(2.0):\n\t\tpass\n").diags.any { "twice" in it.message })
    check("unknown base", lowerOne("script A of Wat:\n\tpass\n").diags.any { "unknown base" in it.message })
    fun lowerMany(vararg src: String) = Transpiler.lower(src.mapIndexed { i, t -> ScriptSource("t$i.nbs", t) })
    val enemySrc = "script Enemy of Node2D:\n\tbroadcast hit(damage: Int, accuracy: Int)\n"
    val hearSrc = "script Player of Node2D:\n\tvar hp = 100\n" +
        "\tfn onHear(@/World/Enemy.hit, damage, accuracy):\n\t\thp -= damage\n\tfn onHear(@Other.hit, damage):\n\t\thp -= damage\n" +
        "\tfn onHear(tapped):\n\t\tpass\n\tfn onHear(@/World/Enemy.freed):\n\t\tpass\n"
    val hearOut = lowerMany(enemySrc, hearSrc)
    check("onHear lowers", hearOut.ok && "fn nbHear_Player_1_hit(damage: Int, accuracy: Int):" in hearOut.source &&
        "_cast_Enemy(nebula.nodeAt(id, \"/World/Enemy\")).hit.connect(fn(a1, a2) => nbHear_Player_1_hit(a1, a2), id)" in hearOut.source &&
        "fn nbHear_Player_2_hit(damage: Int):" in hearOut.source && "tapped.connect(fn(a1, a2) => nbHear_Player_3_tapped(), id)" in hearOut.source)
    check("onHear same broadcast twice", lowerMany(enemySrc, hearSrc + "\tfn onHear(@/World/Enemy.hit, damage):\n\t\tpass\n").diags.any { "declared twice" in it.message })
    check("onHear too many params", lowerMany("script E of Node2D:\n\tbroadcast hit(a: Int)\n", "script P of Node2D:\n\tfn onHear(@X.hit, a, b):\n\t\tpass\n").diags.any { "sends 1" in it.message })
    check("onHear ambiguous owner", lowerMany("script A of Node2D:\n\tbroadcast hit\n", "script B of Node2D:\n\tbroadcast hit\n", "script P of Node2D:\n\tfn onHear(@X.hit):\n\t\tpass\n").diags.any { "say which" in it.message })
    check("onHear as Type", lowerMany("script A of Node2D:\n\tbroadcast hit\n", "script B of Node2D:\n\tbroadcast hit\n", "script P of Node2D:\n\tfn onHear(@X as B.hit):\n\t\tpass\n").ok)
    check("onHear unknown broadcast", lowerOne("script P of Node2D:\n\tfn onHear(@X.nope):\n\t\tpass\n").diags.any { "no node type has a broadcast" in it.message })
    check("old hear rejected", lowerOne("script A of Node2D:\n\tfn f():\n\t\thear tapped -> f\n").diags.any { "onHear" in it.message })
    check("shout unknown", lowerOne("script A of Node2D:\n\tfn f():\n\t\tshout nope\n").diags.any { "not a broadcast" in it.message })
    check("lone @", lowerOne("script A of Node2D:\n\tfn f():\n\t\tvar x = @\n").diags.any { "node path" in it.message })
    val lo = lowerOne("script A of Node2D:\n\tfn f():\n\t\tvar s = \"mail@x.com\"\n\t\tlog(f\"{@Foo/Bar}\")  # @Ignored\n")
    check("@ in strings/comments", lo.ok && "mail@x.com" in lo.source && "nodeAt(id, \"Foo/Bar\")" in lo.source && "# @Ignored" in lo.source, lo.diags.toString() + lo.source)

    // --- starter project builds with snc
    val proj = Project.create(File(tmp, "game"), "Test Game", preset = Preset.BLANK)
    for ((path, text) in Fixtures.demo()) File(proj.dir, path).also { it.parentFile.mkdirs() }.writeText(text)
    val lowered = Builder(proj, ProcessRunner(), apiDir.absoluteFile, snc).lower()
    check("starter lowers", lowered.ok, lowered.diags.toString())
    val res = Builder(proj, ProcessRunner(), apiDir.absoluteFile, snc).build()
    check("starter compiles with snc", res.ok, res.messages.joinToString("\n     "))
    check("meta file", MetaFile.read(File(proj.buildDir, "scripts.meta").readText()).let { it["Hero"]?.tweaks?.size == 2 && it["Score"]?.everyMs == listOf(5000) && it["Coin"]?.broadcasts?.size == 1 })

    // error remapping: a SuperNova type error must point at the .nbs line
    File(proj.dir, "scripts/hero.nbs").appendText("\tfn broken():\n\t\tvar n: Int = \"text\"\n")
    val bad = Builder(proj, ProcessRunner(), apiDir.absoluteFile, snc).build()
    check("errors remap to .nbs", !bad.ok && bad.messages.any { "scripts/hero.nbs:" in it }, bad.messages.joinToString("\n     "))
    File(proj.dir, "scripts/hero.nbs").writeText(Fixtures.demo()["scripts/hero.nbs"]!!)

    // the new Sprite animation and Sound API must compile through snc
    val apiProj = Project.create(File(tmp, "apitest"), "Api Test")
    File(apiProj.dir, "scripts/probe.nbs").writeText("script Probe of Sprite:\n\tfn onHear(animationFinished):\n\t\tlog(\"done\")\n\tfn onReady():\n\t\tanimate(\"run\")\n\t\tsetAnimations(\"animations/hero.nanim\")\n\t\tsetAnimSpeed(0.5)\n\t\tsetFrame(2.0)\n\t\tlog(frame())\n\t\tlog(isAnimating())\n\t\tlog(animation())\n\t\tstopAnimating()\n")
    File(apiProj.dir, "scripts/jingle.nbs").writeText("script Jingle of Sound:\n\tfn onReady():\n\t\tsetFile(\"assets/a.wav\")\n\t\tsetVolume(0.5)\n\t\tsetPitch(1.5)\n\t\tsetLoop(true)\n\t\tstart()\n\t\tstop()\n")
    val apiRes = Builder(apiProj, ProcessRunner(), apiDir.absoluteFile, snc).build()
    check("sprite animation + Sound API compile with snc", apiRes.ok, apiRes.messages.joinToString("\n     "))

    // --- folders in the project
    val sfx = proj.createFolder("assets", "sfx!")
    check("folder created and sanitised", sfx == "assets/sfx_" && proj.file(sfx).isDirectory)
    check("nested folder listed (empty ones too)", proj.createFolder(sfx, "hits") == "assets/sfx_/hits" && proj.folders("assets") == listOf("assets/sfx_", "assets/sfx_/hits"), proj.folders("assets").toString())
    check("duplicate folder refused", runCatching { proj.createFolder("assets", "sfx!") }.isFailure)
    check("folders only inside managed roots", runCatching { proj.createFolder("build", "x") }.isFailure && runCatching { proj.createFolder("assets/../scripts", "x") }.isFailure)
    check("empty name refused", runCatching { proj.createFolder("scenes", "  ") }.isFailure)
    File(proj.dir, "scripts/ai").mkdirs(); File(proj.dir, "scripts/ai/enemy.nbs").writeText("script Enemy of Node2D:\n\tpass\n")
    check("scripts in folders are found", proj.scripts().contains("scripts/ai/enemy.nbs"))
    File(proj.dir, "scripts/ai").deleteRecursively()

    // --- engine with a fake host
    val metas = MetaFile.read(File(proj.buildDir, "scripts.meta").readText())
    val host = FakeHost()
    val logs = ArrayList<String>()
    val eng = Engine({ proj.readText(it) }, metas, host, { logs.add(it) })
    check("load scene", eng.loadScene("scenes/main.nscn"), logs.toString())
    val heroId = eng.tree.tagged("hero").single().id
    check("attach order", host.calls.filter { it.startsWith("attach") }.size == 3)
    check("ready children first", host.calls.filter { it.startsWith("ready") }.size == 3)
    host.calls.clear()
    repeat(60) { eng.update(1.0 / 60) }
    check("onFrame each frame", host.calls.count { it == "frame $heroId" } == 60)
    val scoreId = eng.apiFind(heroId, "/World/Score")
    host.calls.clear()
    repeat(300) { eng.update(1.0 / 60) }   // 5 more seconds
    check("onEvery(5) fires once in 5s", host.calls.count { it == "every $scoreId 5000" } == 1, host.calls.filter { it.startsWith("every") }.toString())
    check("hero lands on ground", eng.tree.get(heroId)!!.b("onGround") && eng.tree.get(heroId)!!.f("y") < 680)
    // fixed ticks: scripts without onTick get none
    check("no ticks for scripts without onTick", host.calls.none { it.startsWith("tick") })
    // collision -> collided broadcast, free queued until end of frame
    val coin = eng.tree.get(eng.apiFind(heroId, "/World/Coin"))!!
    eng.tree.get(heroId)!!.props["x"] = coin.f("x"); eng.tree.get(heroId)!!.props["y"] = coin.f("y")
    host.calls.clear(); eng.update(1.0 / 60)
    check("collided fired on both", host.calls.count { it.startsWith("fire") && "collided" in it } == 2, host.calls.toString())
    eng.apiFree(coin.id); check("free is deferred", eng.tree.alive(coin.id)); eng.update(0.016)
    check("freed after frame", !eng.tree.alive(coin.id) && host.calls.contains("freed ${coin.id}"))
    // tap hit-testing
    val jump = eng.tree.find(eng.tree.root!!, "/World/Jump")!!
    jump.props["touchable"] = true
    host.calls.clear(); eng.tap(jump.f("x"), jump.f("y"))
    check("tap reaches button", host.calls.any { it == "fire ${jump.id} tapped 0" }, host.calls.toString())
    // tick scheduling
    val m2 = metas + ("Ticker" to ScriptMeta("Ticker", "Node2D", "scripts/t.nbs", emptyList(), listOf(30, 60), emptyList(), false, emptyList()))
    File(proj.dir, "scenes/t.nscn").writeText("scene \"t\"\nnode R Node2D script \"scripts/t.nbs\"\n")
    val h2 = FakeHost(); val eng2 = Engine({ proj.readText(it) }, m2, h2, { })
    eng2.loadScene("scenes/t.nscn"); repeat(120) { eng2.update(1.0 / 60) }
    check("onTick(30) = 60 calls / 2s", h2.calls.count { it.endsWith(" 30") && it.startsWith("tick") } == 60, h2.calls.count { it.startsWith("tick") }.toString())
    check("onTick(60) = 120 calls / 2s", h2.calls.count { it.endsWith(" 60") && it.startsWith("tick") } in 119..120)

    // --- sprite sheet animation and Sound nodes
    check("anim set roundtrip", AnimSet.parse("sheet assets/s.png\ngrid 4 2\nanim run 1 4 10 loop\nanim hit 5 7 10 once\n").let { AnimSet.parse(it.toText()).toText() == it.toText() && it.clips.size == 2 && it.sheet == "assets/s.png" && it.frameCount == 8 })
    check("anim set ignores junk", AnimSet.parse("nonsense\nanim x a b c\ngrid 0 -3\n").let { it.clips.isEmpty() && it.cols == 1 && it.rows == 1 })
    check("clip backwards", Clip("b", 4, 1, 10.0, true).let { it.frameAt(0.0).first == 4 && it.frameAt(0.35).first == 1 && it.frameAt(0.4).first == 4 })
    val files = HashMap<String, String>()
    files["a.nanim"] = "sheet assets/s.png\ngrid 4 2\nanim run 1 4 10 loop\nanim hit 5 7 10 once\n"
    files["s.nscn"] = "scene \"s\"\nnode R Node2D\n\tnode P Sprite\n\t\tanimations = \"a.nanim\"\n\t\tanimation = \"run\"\n\tnode S Sound\n\t\tfile = \"assets/b.wav\"\n\t\tvolume = 0.5\n\t\tloop = true\n\t\tautoplay = true\n"
    val soundCmds = ArrayList<SoundCmd>(); val h3 = FakeHost()
    val eng3 = Engine({ files[it] }, emptyMap(), h3, { }, { it }, { }, { soundCmds.add(it) })
    check("animation scene loads", eng3.loadScene("s.nscn"))
    val sp = eng3.tree.find(eng3.tree.root!!, "P")!!
    check("clip starts on its first frame", sp.f("frame") == 1.0, sp.f("frame").toString())
    eng3.update(0.25); check("animation advances", sp.f("frame") == 3.0, sp.f("frame").toString())
    eng3.update(0.5); check("looping clip wraps", sp.f("frame") == 4.0, sp.f("frame").toString())
    sp.props["animation"] = "hit"; sp.props["playing"] = true; eng3.update(0.0)
    check("switching clips restarts", sp.f("frame") == 5.0, sp.f("frame").toString())
    eng3.update(0.35)
    check("once clip stops on its last frame", sp.f("frame") == 7.0 && !sp.b("playing"), sp.f("frame").toString())
    check("animationFinished fired once", h3.calls.count { it == "fire ${sp.id} animationFinished 0" } == 1, h3.calls.toString())
    eng3.update(0.1); check("finished clip stays put", sp.f("frame") == 7.0 && h3.calls.count { it.contains("animationFinished") } == 1)
    sp.props["playing"] = true; eng3.update(0.0)
    check("animate() again replays a finished clip", sp.f("frame") == 5.0 && sp.b("playing"), sp.f("frame").toString())
    check("autoplay Sound starts looping at its volume", soundCmds.any { !it.stop && it.file == "assets/b.wav" && it.volume == 0.5 && it.loop }, soundCmds.size.toString())
    soundCmds.clear()
    val snd = eng3.tree.find(eng3.tree.root!!, "S")!!
    snd.props["_play"] = true; eng3.update(0.016)
    check("Sound.start() plays", soundCmds.size == 1 && !soundCmds[0].stop && soundCmds[0].key == snd.id, soundCmds.size.toString())
    snd.props["_stop"] = true; eng3.update(0.016)
    check("Sound.stop() stops", soundCmds.last().stop && !snd.b("_stop"))
    soundCmds.clear(); eng3.shutdown()
    check("shutdown stops sounds", soundCmds.any { it.stop })

    // --- scene editor
    val ed = SceneEditor { proj.readText(it) }
    ed.load("scenes/main.nscn", proj.readText("scenes/main.nscn")!!)
    val before = ed.toText()
    check("editor load/save is stable", SceneFile.write(SceneFile.parse(before)) == before)
    val world = ed.tree.root!!
    val added = ed.addNode(world, "Sprite", "Ship")
    check("add node", world.child("Ship") === added)
    ed.rename(added, "Ship One!"); check("rename sanitises", added.name == "Ship_One_")
    val dup = ed.duplicate(added)!!; check("duplicate", world.child("Ship_One_2") === dup)
    check("indent/outdent", ed.indent(dup) && dup.parent === added && ed.outdent(dup) && dup.parent === world)
    check("cannot reparent into own subtree", !ed.reparent(world, added))
    check("undo restores", (1..5).all { ed.undo() } && ed.toText() == before)
    check("redo", ed.redo() && ed.tree.root!!.children.any { it.name == "Ship" })
    ed.setProp(ed.tree.root!!.child("Hero")!!, "x", 321.0)
    check("prop edit survives roundtrip", SceneFile.parse(ed.toText()).root.children.first { it.name == "Hero" }.props["x"] == 321.0)
    check("delete root refused", !ed.delete(ed.tree.root!!))

    // --- presets: every preset lowers, builds and plays
    for (preset in Preset.values()) {
        val pp = Project.create(File(tmp, "preset-${preset.id}"), "P ${preset.label}", preset = preset)
        val low = Builder(pp, ProcessRunner(), apiDir.absoluteFile, snc).lower()
        check("preset ${preset.id} lowers", low.ok, low.diags.toString())
        val built = Builder(pp, ProcessRunner(), apiDir.absoluteFile, snc).build()
        check("preset ${preset.id} compiles with snc", built.ok, built.messages.joinToString("\n     "))
        check("preset ${preset.id} scene parses", runCatching { SceneFile.parse(pp.readText("scenes/main.nscn")!!) }.isSuccess)
    }
    check("blank preset has no scripts", Project(File(tmp, "preset-blank")).scripts().isEmpty())
    check("presets found by id", Preset.byId("topdown") == Preset.TOP_DOWN && Preset.byId("nonsense") == Preset.PLATFORMER)
    for (preset in listOf(Preset.PLATFORMER, Preset.TOP_DOWN)) {
        val pp = Project(File(tmp, "preset-${preset.id}"))
        val text = pp.readText("scripts/player.nbs")!!
        check("preset ${preset.id} script is commented", text.lines().count { it.trim().startsWith("#") } >= 12, text.lines().count { it.trim().startsWith("#") }.toString())
        val meta = MetaFile.read(File(pp.buildDir, "scripts.meta").readText())
        check("preset ${preset.id} player has a speed tweak", meta["Player"]?.tweaks?.any { it.name == "speed" } == true && meta["Player"]?.frame == true)
    }
    run {   // platformer: the player lands on the ground, and a dropped player rests on the platform
        val pp = Project(File(tmp, "preset-platformer"))
        val metas2 = MetaFile.read(File(pp.buildDir, "scripts.meta").readText())
        val e = Engine({ pp.readText(it) }, metas2, FakeHost(), { })
        check("platformer loads", e.loadScene("scenes/main.nscn"))
        val player = e.tree.tagged("player").single()
        repeat(120) { e.update(1.0 / 60) }
        check("platformer player stands on the ground", player.b("onGround") && Math.abs(player.f("y") - 636.0) < 1.5, player.props.toString())
        player.props["x"] = 900.0; player.props["y"] = 400.0; player.props["vy"] = 0.0
        repeat(120) { e.update(1.0 / 60) }
        check("platformer player stands on the platform", player.b("onGround") && Math.abs(player.f("y") - 524.0) < 1.5, player.props.toString())
    }
    run {   // top down: no gravity, the walls keep the player inside the room
        val pp = Project(File(tmp, "preset-topdown"))
        val metas2 = MetaFile.read(File(pp.buildDir, "scripts.meta").readText())
        val e = Engine({ pp.readText(it) }, metas2, FakeHost(), { })
        check("top down loads", e.loadScene("scenes/main.nscn"))
        val player = e.tree.tagged("player").single()
        repeat(60) { e.update(1.0 / 60) }
        check("top down player does not fall", Math.abs(player.f("y") - 360.0) < 0.5 && Math.abs(player.f("x") - 640.0) < 0.5)
        for (dir in listOf(1.0 to 0.0, -1.0 to 0.0, 0.0 to 1.0, 0.0 to -1.0)) {
            player.props["x"] = 640.0; player.props["y"] = 360.0
            repeat(150) { player.props["vx"] = dir.first * 900.0; player.props["vy"] = dir.second * 900.0; e.update(1.0 / 60) }
            check("top down walls stop the player (${dir.first},${dir.second})", player.f("x") in 39.0..1241.0 && player.f("y") in 39.0..681.0 && (player.f("x") !in 635.0..645.0 || player.f("y") !in 355.0..365.0), player.props.toString())
        }
    }

    // --- SpawnableBody
    check("SpawnableBody is a Body", NodeTypes.exists("SpawnableBody") && NodeTypes.isA("SpawnableBody", "Body") && "SpawnableBody" in NodeTypes.bodyTypes)
    check("SpawnableBody broadcasts", NodeTypes.broadcastsOf("SpawnableBody").map { it.name }.containsAll(listOf("expired", "died", "damaged", "collided", "tapped")))
    run {
        val sp = Project.create(File(tmp, "spawn"), "Spawn", preset = Preset.BLANK)
        File(sp.dir, "scenes/main.nscn").writeText("scene \"S\"\nnode World Node2D\n\tnode Ground Body\n\t\tx = 400\n\t\ty = 500\n\t\twidth = 800\n\t\theight = 40\n\t\tsolid = true\n\t\tstatic = true\n" +
            "\tnode Short SpawnableBody\n\t\tlifetime = 0.5\n\t\tx = 100\n\t\ty = 100\n" +
            "\tnode Forever SpawnableBody\n\t\tx = 200\n\t\ty = 100\n\t\tgravity = 1000\n\t\tsolid = true\n")
        val logs2 = ArrayList<String>(); val h = FakeHost()
        val e = Engine({ sp.readText(it) }, emptyMap(), h, { logs2.add(it) })
        check("spawn scene loads", e.loadScene("scenes/main.nscn"), logs2.toString())
        val short = e.tree.find(e.tree.root!!, "Short")!!; val forever = e.tree.find(e.tree.root!!, "Forever")!!
        h.calls.clear()
        repeat(20) { e.update(1.0 / 60) }
        check("lifetime not over yet", e.tree.alive(short.id) && h.calls.none { it.contains("expired") })
        repeat(20) { e.update(1.0 / 60) }   // 0.66 s in
        check("expired fires once", h.calls.count { it == "fire ${short.id} expired 0" } == 1, h.calls.toString())
        check("expired body is freed", !e.tree.alive(short.id) && h.calls.contains("freed ${short.id}"))
        repeat(600) { e.update(1.0 / 60) }   // 10 more seconds
        check("lifetime 0 lives on", e.tree.alive(forever.id))
        check("SpawnableBody falls and lands like a Body", forever.b("onGround") && forever.f("y") < 500.0 && forever.f("y") > 400.0, forever.props.toString())
        check("age counts up", forever.f("_age") > 10.0)
        // many bodies at once stay fast (the bullet-heaven case)
        val parent = e.tree.root!!
        repeat(600) { i -> e.apiAdd(parent.id, "SpawnableBody", "B$i").let { id -> e.tree.get(id)!!.props.apply { put("x", (i * 37 % 1200).toDouble()); put("y", (i * 53 % 400).toDouble()); put("width", 8.0); put("height", 8.0) } } }
        val t0 = System.nanoTime(); repeat(60) { e.update(1.0 / 60) }; val ms = (System.nanoTime() - t0) / 1e6
        check("600 bodies simulate quickly", ms < 2500, "%.0f ms for 60 frames".format(ms))
    }
    run {   // the whole script API must compile through snc
        val sp = Project.create(File(tmp, "spawnapi"), "Spawn Api", preset = Preset.BLANK)
        File(sp.dir, "scripts/bullet.nbs").writeText("script Bullet of SpawnableBody:\n\tfn onReady():\n\t\ttag(\"bullet\")\n\t\tsetLifetime(2.0)\n\tfn onHear(collided, other):\n\t\tif dealDamage(other):\n\t\t\tdie()\n\tfn onHear(expired):\n\t\tlog(\"gone\")\n")
        File(sp.dir, "scripts/enemy.nbs").writeText("script Enemy of SpawnableBody:\n\tfn onReady():\n\t\tsetMaxHealth(5.0)\n\t\tsetHealth(4.0)\n\t\tsetDamage(2.0)\n\t\ttag(\"enemy\")\n\tfn onFrame():\n\t\tvar p = @/World/Hero as Node2D\n\t\tchase(p, 80.0)\n\tfn onHear(damaged, amount):\n\t\tlog(f\"ouch {amount} {health()} of {maxHealth()}\")\n\tfn onHear(died):\n\t\tlog(f\"dead {isDead()} {age()} {lifetime()} {damage()}\")\n")
        File(sp.dir, "scripts/hero.nbs").writeText("script Hero of Node2D:\n\tfn onReady():\n\t\tvar b = spawnBody(\"scenes/bullet.nscn\", parent(), x(), y())\n\t\tb.launch(45.0, 300.0)\n\t\tb.launchAt(@/World/Hero as Node2D, 50.0)\n\t\tb.hurt(1.0)\n\t\tb.heal(0.5)\n\t\tlog(b.angleTo(10.0, 10.0))\n\t\tvar ring = spawnRing(\"scenes/bullet.nscn\", parent(), 0.0, 0.0, 6, 100.0)\n\t\tvar burst = spawnBurst(\"scenes/bullet.nscn\", parent(), 0.0, 0.0, 8, 200.0, 15.0)\n\t\tlog(ring.length() + burst.length())\n\t\tif countTagged(\"enemy\") > 0:\n\t\t\tvar near = nearest(\"enemy\", x(), y())\n\t\t\tlog(near.x())\n\tfn onHear(@/World/Enemy.damaged, amount):\n\t\tlog(amount)\n\tfn onHear(@/World/Enemy.died):\n\t\tlog(\"enemy died\")\n")
        val r = Builder(sp, ProcessRunner(), apiDir.absoluteFile, snc).build()
        check("SpawnableBody API compiles with snc", r.ok, r.messages.joinToString("\n     "))
    }


    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    System.exit(if (failures == 0) 0 else 1)
}
