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
        check("top down player does not fall", Math.abs(player.f("y") - 720.0) < 0.5 && Math.abs(player.f("x") - 1280.0) < 0.5)
        for (dir in listOf(1.0 to 0.0, -1.0 to 0.0, 0.0 to 1.0, 0.0 to -1.0)) {
            player.props["x"] = 1280.0; player.props["y"] = 1000.0
            repeat(300) { player.props["vx"] = dir.first * 900.0; player.props["vy"] = dir.second * 900.0; e.update(1.0 / 60) }
            check("top down walls stop the player (${dir.first},${dir.second})", player.f("x") in 59.0..2501.0 && player.f("y") in 59.0..1381.0 && (player.f("x") !in 1275.0..1285.0 || player.f("y") !in 995.0..1005.0), player.props.toString())
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


    // --- tilemaps
    run {
        val g = TileGrid(5, 3); g[0, 0] = 1; g[4, 0] = 2; g[2, 2] = 7
        check("tile text roundtrip", g.toText() == "1,0,0,0,2;;0,0,7" && TileGrid.parse(g.toText(), 5, 3).toText() == g.toText(), g.toText())
        check("tiles survive a bigger map", TileGrid.parse(g.toText(), 8, 6)[2, 2] == 7 && TileGrid.parse(g.toText(), 8, 6)[4, 0] == 2)
        check("tiles outside read empty", g[-1, 0] == 0 && g[5, 0] == 0 && g[0, 3] == 0)
        check("empty map is empty text", TileGrid(4, 4).toText() == "")
        val f = TileGrid(4, 3); f[1, 0] = 5; f[1, 1] = 5; check("flood fill", f.floodFill(0, 0, 9) && f[0, 0] == 9 && f[1, 0] == 5 && f[3, 2] == 9 && !f.floodFill(0, 0, 9))
        check("bad tile text does not crash", TileGrid.parse("a,b;;,,;-3,99999999999", 3, 3).toText() == ";;0,0,0".replace(";;0,0,0", "").let { TileGrid.parse("a,b;;,,;-3,99999999999", 3, 3).toText() })
    }
    run {
        val tp = Project.create(File(tmp, "tiles"), "Tiles", preset = Preset.BLANK)
        // 10x6 map of 32px tiles centred on (320, 200): spans x 160..480, y 104..296. Floor on the bottom row, a wall at column 10, a ceiling bit at column 3 row 3
        val rows = listOf("", "", "0,0,3", "", "", "1,1,1,1,1,1,1,1,1,1")
        val wall = (1..6).joinToString(";") { r -> if (r == 6) "1,1,1,1,1,1,1,1,1,1" else ",,,,,,,,,1" }
        File(tp.dir, "scenes/main.nscn").writeText("scene \"T\"\nnode World Node2D\n\tnode Map TileMap\n\t\tx = 320\n\t\ty = 200\n\t\tcolumns = 10\n\t\trows = 6\n\t\ttileWidth = 32\n\t\ttileHeight = 32\n\t\ttiles = \"$wall\"\n" +
            "\tnode Hero SpawnableBody\n\t\tx = 300\n\t\ty = 150\n\t\twidth = 20\n\t\theight = 20\n\t\tgravity = 1000\n\t\tbounce = 0\n")
        val h = FakeHost(); val e = Engine({ tp.readText(it) }, emptyMap(), h, { })
        check("tilemap scene loads", e.loadScene("scenes/main.nscn"))
        val hero = e.tree.find(e.tree.root!!, "Hero")!!; val map = e.tree.find(e.tree.root!!, "Map")!!
        repeat(120) { e.update(1.0 / 60) }
        check("body lands on the tile floor", hero.b("onGround") && Math.abs(hero.f("y") - (296 - 32 - 10)) < 0.01, hero.props.toString())
        check("collided fired once for the map", h.calls.count { it == "fire ${hero.id} collided ${map.id}" } == 1, h.calls.filter { "collided" in it }.toString())
        hero.props["vx"] = 400.0; repeat(120) { e.update(1.0 / 60) }
        check("body stops at the tile wall and keeps standing", Math.abs(hero.f("x") - (480 - 32 - 10)) < 0.01 && hero.b("onGround") && Math.abs(hero.f("y") - 254) < 0.01, hero.props.toString())
        hero.props["vx"] = -400.0; repeat(120) { e.update(1.0 / 60) }
        check("sliding along flat tiles never snags", hero.f("x") < 200 || hero.f("x") > 160 + 10 - 0.01, hero.props.toString())
        check("left edge of the map is open (no wall there)", hero.f("x") < 170, hero.props.toString())
        // script-style access: 1-based cells through get/set
        check("tile read through the api", e.apiGetF(map.id, "tile:10,6") == 1.0 && e.apiGetF(map.id, "tile:1,1") == 0.0 && e.apiGetF(map.id, "tile:99,99") == 0.0)
        e.apiSet(map.id, "tile:5,3", 4.0); check("tile write through the api", e.apiGetF(map.id, "tile:5,3") == 4.0)
        e.update(1.0 / 60); check("tile edits reach the saved string", TileGrid.parse(map.s("tiles"), 10, 6)[4, 2] == 4 && SceneFile.write(SceneFile.fromTree("t", e.tree.root!!)).contains("4"), map.s("tiles"))
        e.apiSet(map.id, "tile:0,0", 4.0); e.apiSet(map.id, "tile:x", 4.0); check("bad cells are ignored", e.apiGetF(map.id, "tile:0,0") == 0.0)
        // a solid tile placed above stops a jump
        hero.props["vx"] = 0.0; hero.props["x"] = 288.0; hero.props["y"] = 254.0; hero.props["vy"] = -1500.0
        e.apiSet(map.id, "tile:5,3", 0.0); e.apiSet(map.id, "tile:9,4", 3.0)   // column 9 spans x 416..448
        hero.props["x"] = 432.0; hero.props["y"] = 254.0; hero.props["vy"] = -1500.0; hero.props["gravity"] = 0.0
        repeat(30) { e.update(1.0 / 60) }
        check("ceiling tile stops upward movement", hero.f("y") >= 104 + 32 * 4 - 0.01 + 10 - 0.5 && hero.f("vy") == 0.0 || hero.f("y") > 104 + 96 + 10 - 0.5, hero.props.toString())
        // solidTiles restricts which numbers stop bodies; non-solid maps are scenery
        map.props["solidTiles"] = "7"; hero.props["y"] = 200.0; hero.props["vy"] = 0.0; hero.props["gravity"] = 1000.0; hero.props["x"] = 300.0
        repeat(120) { e.update(1.0 / 60) }
        check("only listed tile numbers are solid", hero.f("y") > 296, hero.props.toString())
        map.props["solidTiles"] = ""; map.props["solid"] = false; hero.props["y"] = 200.0; hero.props["vy"] = 0.0
        repeat(120) { e.update(1.0 / 60) }
        check("a map with solid off is scenery", hero.f("y") > 296, hero.props.toString())
    }
    run {   // the editor's tile tools
        val tree = SceneTree(); val m = tree.create("TileMap", "M"); tree.setRoot(m)
        m.props["x"] = 100.0; m.props["y"] = 100.0; m.props["columns"] = 4.0; m.props["rows"] = 2.0; m.props["tileWidth"] = 10.0; m.props["tileHeight"] = 10.0   // spans x 80..120, y 90..110
        check("cellAt inside and outside", TileGrid.cellAt(m, 81.0, 91.0) == (0 to 0) && TileGrid.cellAt(m, 119.9, 109.9) == (3 to 1) && TileGrid.cellAt(m, 79.0, 100.0) == null && TileGrid.cellAt(m, 100.0, 111.0) == null)
        check("paint tool", TileGrid.apply(m, "paint", 1, 0, 5) && m.s("tiles") == "0,5" && !TileGrid.apply(m, "paint", 1, 0, 5))
        check("erase tool", TileGrid.apply(m, "erase", 1, 0, 0) && m.s("tiles") == "" && !TileGrid.apply(m, "erase", 1, 0, 0))
        TileGrid.apply(m, "paint", 1, 0, 2); TileGrid.apply(m, "paint", 1, 1, 2)
        check("fill tool stays inside its area", TileGrid.apply(m, "fill", 0, 0, 9) && m.s("tiles") == "9,2;9,2", m.s("tiles"))
        check("fill tool floods an open area", TileGrid.apply(m, "fill", 3, 1, 7) && m.s("tiles") == "9,2,7,7;9,2,7,7", m.s("tiles"))
    }
    run {   // many tiles stay cheap: a big solid map and a few hundred bodies
        val tp = Project.create(File(tmp, "bigtiles"), "Big", preset = Preset.BLANK)
        val cols = 200; val rws = 100
        val text = (1..rws).joinToString(";") { r -> if (r > 90) List(cols) { 1 }.joinToString(",") else "" }
        File(tp.dir, "scenes/main.nscn").writeText("scene \"B\"\nnode World Node2D\n\tnode Map TileMap\n\t\tx = 3200\n\t\ty = 1600\n\t\tcolumns = $cols\n\t\trows = $rws\n\t\ttiles = \"$text\"\n")
        val e = Engine({ tp.readText(it) }, emptyMap(), FakeHost(), { })
        e.loadScene("scenes/main.nscn")
        repeat(300) { i -> e.apiAdd(e.tree.root!!.id, "SpawnableBody", "B$i").let { id -> e.tree.get(id)!!.props.apply { put("x", 100.0 + i * 20); put("y", 2700.0); put("width", 8.0); put("height", 8.0); put("gravity", 900.0) } } }
        val t0 = System.nanoTime(); repeat(120) { e.update(1.0 / 60) }; val ms = (System.nanoTime() - t0) / 1e6
        check("20000 tiles and 300 bodies stay fast", ms < 3000, "%.0f ms for 120 frames".format(ms))
        check("bodies came to rest on the big map", e.tree.all().filter { it.name.startsWith("B") && it.type == "SpawnableBody" }.all { it.b("onGround") })
    }
    run {   // the TileMap script API compiles through snc
        val tp = Project.create(File(tmp, "tileapi"), "Tile Api", preset = Preset.BLANK)
        File(tp.dir, "scripts/level.nbs").writeText("script Level of TileMap:\n\tfn onReady():\n\t\tsetTile(1, 1, 3)\n\t\tclearTile(2, 2)\n\t\tfillTiles(0)\n\t\tsetSolid(true)\n\t\tlog(columns() + rows())\n\t\tlog(tileWidth() + tileHeight())\n\t\tlog(tileAt(1, 1))\n\t\tlog(colAt(10.0))\n\t\tlog(rowAt(10.0))\n\t\tlog(tileAtPoint(5.0, 5.0))\n\t\tvar c = cellCenter(3, 4)\n\t\tlog(c.x + c.y)\n")
        File(tp.dir, "scripts/hero.nbs").writeText("script Hero of Body:\n\tfn onFrame():\n\t\tvar m = @/World/Map as TileMap\n\t\tif m.tileAtPoint(x(), y() + 20.0) == 0:\n\t\t\tlog(\"falling\")\n")
        val r = Builder(tp, ProcessRunner(), apiDir.absoluteFile, snc).build()
        check("TileMap API compiles with snc", r.ok, r.messages.joinToString("\n     "))
    }


    // --- camera
    run {
        fun cam(scene: String): Engine {
            val cp = Project.create(File(tmp, "cam-${scene.hashCode()}"), "Cam", preset = Preset.BLANK)
            File(cp.dir, "scenes/main.nscn").writeText(scene)
            return Engine({ cp.readText(it) }, emptyMap(), FakeHost(), { }).also { check("camera scene loads", it.loadScene("scenes/main.nscn")) }
        }
        fun near(a: Double, b: Double, eps: Double = 0.01) = Math.abs(a - b) <= eps
        val bare = cam("scene \"C\"\nnode World Node2D\n")
        check("no camera: screen centre is the view centre and points map one to one", near(bare.camX, 640.0) && near(bare.camY, 360.0) && bare.toWorld(100.0, 50.0).let { near(it.first, 100.0) && near(it.second, 50.0) })

        val still = cam("scene \"C\"\nnode World Node2D\n\tnode Cam Camera\n\t\tx = 1000\n\t\ty = 500\n\t\tzoom = 2\n")
        still.update(1.0 / 60)
        check("static camera shows its own position and zoom", near(still.camX, 1000.0) && near(still.camY, 500.0) && near(still.camZoom, 2.0))
        check("screen to world through zoom", still.toWorld(640.0, 360.0).let { near(it.first, 1000.0) && near(it.second, 500.0) } && still.toWorld(0.0, 0.0).let { near(it.first, 680.0) && near(it.second, 320.0) })

        val base = "scene \"C\"\nnode World Node2D\n\tnode Cam Camera\n\t\tfollow = true\n\t\ttarget = \"/World/Hero\"\n\t\tsmoothing = 0\n%s\tnode Hero Body tags \"hero\"\n\t\tstatic = true\n\t\tx = 300\n\t\ty = 200\n"
        val f = cam(base.format(""))
        val hero = f.tree.find(f.tree.root!!, "Hero")!!; val camNode = f.tree.find(f.tree.root!!, "Cam")!!
        check("follow snaps to the target when the scene starts", near(f.camX, 300.0) && near(f.camY, 200.0))
        hero.props["x"] = 500.0; hero.props["y"] = 100.0; f.update(1.0 / 60)
        check("smoothing 0 locks on to the target", near(f.camX, 500.0) && near(f.camY, 100.0))
        check("the Camera node itself moves (scripts can read it)", near(camNode.f("x"), 500.0) && near(camNode.f("y"), 100.0))

        val hOnly = cam(base.format("\t\tfollowY = false\n"))
        val h2 = hOnly.tree.find(hOnly.tree.root!!, "Hero")!!
        val y0 = hOnly.camY
        h2.props["x"] = 900.0; h2.props["y"] = 50.0; hOnly.update(1.0 / 60)
        check("followY off: only moves sideways", near(hOnly.camX, 900.0) && near(hOnly.camY, y0), "${hOnly.camX} ${hOnly.camY} $y0")
        val vOnly = cam(base.format("\t\tfollowX = false\n"))
        val v2 = vOnly.tree.find(vOnly.tree.root!!, "Hero")!!; val x0 = vOnly.camX
        v2.props["x"] = 900.0; v2.props["y"] = 50.0; vOnly.update(1.0 / 60)
        check("followX off: only moves up and down", near(vOnly.camX, x0) && near(vOnly.camY, 50.0))

        val slow = cam(base.format("").replace("smoothing = 0", "smoothing = 0.5"))
        val s2 = slow.tree.find(slow.tree.root!!, "Hero")!!
        s2.props["x"] = 400.0; s2.props["y"] = 200.0; repeat(30) { slow.update(1.0 / 60) }    // half a second = one time constant
        check("smoothing is a time constant (about 63% after one)", Math.abs((slow.camX - 300.0) / 100.0 - 0.632) < 0.03, slow.camX.toString())
        val fast = cam(base.format("").replace("smoothing = 0", "smoothing = 0.5"))
        val f2 = fast.tree.find(fast.tree.root!!, "Hero")!!
        f2.props["x"] = 400.0; repeat(15) { fast.update(1.0 / 30) }       // the same half second at another frame rate
        check("smoothing does not depend on the frame rate", Math.abs(fast.camX - slow.camX) < 0.5, "${fast.camX} ${slow.camX}")
        repeat(600) { slow.update(1.0 / 60) }
        check("smoothing arrives eventually", near(slow.camX, 400.0, 0.05))
        s2.props["x"] = 1000.0; slow.tree.find(slow.tree.root!!, "Cam")!!.props["_snap"] = true; slow.update(1.0 / 60)
        check("snapToTarget jumps at once", near(slow.camX, 1000.0))

        val off = cam(base.format("\t\toffsetX = 50\n\t\toffsetY = -80\n"))
        check("offset shifts the view", near(off.camX, 350.0) && near(off.camY, 120.0))

        val byTag = cam(base.format("").replace("/World/Hero", "#hero"))
        check("a #tag can be the target", near(byTag.camX, 300.0))
        val missing = cam(base.format("").replace("/World/Hero", "/World/Nobody"))
        missing.update(1.0 / 60); check("a missing target leaves the camera alone", near(missing.camX, 0.0) && near(missing.camY, 0.0), "${missing.camX} ${missing.camY}")

        val lim = cam(base.format("\t\tlimits = true\n\t\tlimitRight = 2000\n\t\tlimitBottom = 1000\n"))
        val l2 = lim.tree.find(lim.tree.root!!, "Hero")!!
        check("limits stop the view at the left/top edge", near(lim.camX, 640.0) && near(lim.camY, 360.0), "${lim.camX} ${lim.camY}")
        l2.props["x"] = 1900.0; l2.props["y"] = 990.0; lim.update(1.0 / 60)
        check("limits stop the view at the right/bottom edge", near(lim.camX, 2000.0 - 640.0) && near(lim.camY, 1000.0 - 360.0), "${lim.camX} ${lim.camY}")
        val tiny = cam(base.format("\t\tlimits = true\n\t\tlimitRight = 1000\n\t\tlimitBottom = 500\n"))
        check("a level smaller than the screen is centred", near(tiny.camX, 500.0) && near(tiny.camY, 250.0))
        val zl = cam(base.format("\t\tlimits = true\n\t\tlimitRight = 2000\n\t\tlimitBottom = 1000\n\t\tzoom = 2\n"))
        check("limits account for zoom (the view is smaller)", near(zl.camX, 320.0) && near(zl.camY, 200.0), "${zl.camX} ${zl.camY}")   // hero x 300 is clamped to 320, y 200 is already inside 180..820

        // fixed nodes ignore the camera: an on-screen button keeps working when the view has moved away
        val hud = cam("scene \"C\"\nnode World Node2D\n\tnode Cam Camera\n\t\tx = 1000\n\t\ty = 500\n" +
            "\tnode Pad Button\n\t\taction = \"left\"\n\t\tfixed = true\n\t\tx = 100\n\t\ty = 100\n\t\twidth = 80\n\t\theight = 80\n" +
            "\tnode WorldPad Button\n\t\taction = \"right\"\n\t\tx = 100\n\t\ty = 100\n\t\twidth = 80\n\t\theight = 80\n" +
            "\tnode Tapper Node2D\n\t\ttouchable = true\n\t\tfixed = true\n\t\tx = 200\n\t\ty = 200\n\t\twidth = 40\n\t\theight = 40\n")
        hud.update(1.0 / 60)
        hud.setPointers(listOf(100.0 to 100.0))
        check("fixed button works at its screen spot, a world one does not", hud.input.isDown("left") && !hud.input.isDown("right"))
        check("touches() are world positions", hud.input.touches.single().let { near(it.first, 1000.0 + (100.0 - 640.0)) && near(it.second, 500.0 + (100.0 - 360.0)) })
        hud.setPointers(listOf(640.0 + (100.0 - 1000.0) to 360.0 + (100.0 - 500.0)))   // the screen spot where the world button now appears
        check("a world button is hit where the camera shows it", hud.input.isDown("right") && !hud.input.isDown("left"))
        check("tap on a fixed node uses screen coordinates", hud.tap(200.0, 200.0) && !hud.tap(640.0, 360.0))
    }
    run {   // the camera script API compiles through snc
        val cp = Project.create(File(tmp, "camapi"), "Cam Api", preset = Preset.BLANK)
        File(cp.dir, "scripts/cam.nbs").writeText("script Rig of Camera:\n\tfn onReady():\n\t\tsetZoom(1.5)\n\t\tlog(zoom())\n\t\tfollowTag(\"player\")\n\t\tfollowNode(@/World/Hero)\n\t\tsetSmoothing(0.3)\n\t\tlog(smoothing())\n\t\tsetFollowAxes(true, false)\n\t\tsetOffset(0.0, -60.0)\n\t\tsetLimits(0.0, 0.0, 3000.0, 800.0)\n\t\tclearLimits()\n\t\tsnapToTarget()\n\t\tlog(isFollowing())\n\t\tstopFollowing()\n")
        File(cp.dir, "scripts/hud.nbs").writeText("script Hud of Label:\n\tfn onReady():\n\t\tsetFixed(true)\n\t\tlog(isFixed())\n")
        val r = Builder(cp, ProcessRunner(), apiDir.absoluteFile, snc).build()
        check("Camera API compiles with snc", r.ok, r.messages.joinToString("\n     "))
    }

    // --- script variables that start from other variables (SuperNova gives a field's starting value no access to other fields)
    val fieldSrc = "script Hero of Node2D:\n\ttweak speed = 200.5\n\tvar runSpeed = speed * 1.5\n\tvar label = f\"speed {speed}\"\n\tvar plain = 5\n" +
        "\tvar fast: Float = runSpeed + 1.0\n\tfn onReady():\n\t\tlog(runSpeed)\n\t\tlog(label)\n\t\tlog(fast)\n"
    run {
        val o = lowerOne(fieldSrc)
        val src = o.source
        check("a variable that starts from a tweak is set in the constructor", o.ok && "\tvar runSpeed: Float = 0.0" in src && "\t\trunSpeed = speed * 1.5" in src &&
            "\tvar label: String = \"\"" in src && "\t\tlabel = f\"speed {speed}\"" in src && "\tvar plain = 5" in src, o.diags.joinToString() + "\n" + src)
        check("tweak override comes before the variables that use it", src.indexOf("\t\t\tspeed = ") in 0 until src.indexOf("\t\trunSpeed = speed") &&
            src.indexOf("\t\trunSpeed = speed") < src.indexOf("\t\tfast = runSpeed + 1.0"), src)
        check("a variable can start from one that was itself set late", "\tvar fast: Float = 0.0" in src)
        check("a node as a starting value explains itself", lowerOne("script A of Node2D:\n\tvar target = @Player\n").diags.any { "cannot start as a node" in it.message })
        check("an unclear type asks for one", lowerOne("script A of Node2D:\n\tvar a = 1\n\tvar b = foo(a)\n").diags.any { "write its type" in it.message })
        check("plain starting values are left alone", lowerOne("script A of Node2D:\n\tvar a = 1\n\tvar b = foo()\n\tfn onReady():\n\t\tvar c = a\n").let { it.ok && "\tvar a = 1" in it.source && "\tvar b = foo()" in it.source })
        check("undefined variable errors get a hint", o.remap("x.sn:1: undefined variable 'abc'").contains("declare it at the top of the script"))
        val vp = Project.create(File(tmp, "fieldinit"), "Field Init", preset = Preset.BLANK)
        File(vp.dir, "scripts/hero.nbs").writeText(fieldSrc)
        val r = Builder(vp, ProcessRunner(), apiDir.absoluteFile, snc).build()
        check("variables starting from other variables compile with snc", r.ok, r.messages.joinToString("\n     "))
    }

    // --- CollisionBox
    check("CollisionBox is a Node2D but not a body", NodeTypes.exists("CollisionBox") && NodeTypes.isA("CollisionBox", "Node2D") && "CollisionBox" !in NodeTypes.bodyTypes)
    run {
        val cp = Project.create(File(tmp, "hitbox"), "Hitbox", preset = Preset.BLANK)
        File(cp.dir, "scenes/main.nscn").writeText("scene \"S\"\nnode World Node2D\n\tnode Ground Body\n\t\tx = 400\n\t\ty = 500\n\t\twidth = 800\n\t\theight = 40\n\t\tsolid = true\n\t\tstatic = true\n" +
            "\tnode Plain Body\n\t\tx = 100\n\t\ty = 100\n\t\tgravity = 1000\n" +
            "\tnode Boxed Body\n\t\tx = 200\n\t\ty = 100\n\t\tgravity = 1000\n\t\tnode Hit CollisionBox\n\t\t\twidth = 10\n\t\t\theight = 10\n" +
            "\tnode Shifted Body\n\t\tx = 300\n\t\ty = 100\n\t\tgravity = 1000\n\t\tnode Hit CollisionBox\n\t\t\ty = -10\n\t\t\twidth = 10\n\t\t\theight = 10\n")
        val e = Engine({ cp.readText(it) }, emptyMap(), FakeHost(), { })
        check("hitbox scene loads", e.loadScene("scenes/main.nscn"))
        repeat(300) { e.update(1.0 / 60) }
        fun rest(name: String) = e.tree.find(e.tree.root!!, name)!!.f("y")
        check("a body without a CollisionBox uses its own size", Math.abs(rest("Plain") - 464.0) < 1.5, rest("Plain").toString())               // 32 high: bottom on the ground's top (480)
        check("a CollisionBox child replaces the body's hitbox", Math.abs(rest("Boxed") - 475.0) < 1.5, rest("Boxed").toString())             // 10 high: rests 5 above its centre
        check("a CollisionBox offset moves the hitbox", Math.abs(rest("Shifted") - 485.0) < 1.5, rest("Shifted").toString())                  // box centre 10 above the body's
        File(cp.dir, "scripts/box.nbs").writeText("script Box of CollisionBox:\n\tfn onReady():\n\t\tsetSize(20.0, 10.0)\n\t\tlog(width())\n\t\tlog(height())\n")
        val r = Builder(cp, ProcessRunner(), apiDir.absoluteFile, snc).build()
        check("CollisionBox API compiles with snc", r.ok, r.messages.joinToString("\n     "))
    }

    // --- node clipboard (copy / cut / paste in the editor)
    run {
        val ed = SceneEditor { null }
        ed.newScene("scenes/t.nscn", "T")
        val root = ed.tree.root!!
        val a = ed.addNode(root, "Body", "A"); ed.setProp(a, "x", 12.0)
        ed.addNode(a, "CollisionBox", "Hit")
        val text = ed.exportNode(a)
        val pasted = ed.paste(root, text)
        check("paste copies a node with its children", pasted != null && pasted.name == "A2" && pasted.f("x") == 12.0 && pasted.children.singleOrNull()?.type == "CollisionBox")
        check("paste is one undo step", ed.undo() && ed.tree.root!!.children.size == 1)
        check("text that is not a node does not paste", ed.paste(ed.tree.root!!, "not a scene") == null)
    }

    // --- DynamicBar: sprite roles declared in the script, fill maths
    run {
        check("DynamicBar is a Node2D", NodeTypes.exists("DynamicBar") && NodeTypes.isA("DynamicBar", "Node2D") && "DynamicBar" !in NodeTypes.bodyTypes)
        val barSrc = "script Life of DynamicBar:\n\tempty @Back\n\tfull @Frame/Fill\n\tfn onReady():\n\t\tsetValue(40.0)\n"
        val kidSrc = "script BigLife of Life:\n\tfull @^/Other\n"
        val low = Transpiler.lower(listOf(ScriptSource("scripts/life.nbs", barSrc), ScriptSource("scripts/big.nbs", kidSrc)))
        check("bar script lowers", low.ok, low.diags.joinToString())
        val lm = low.metas.associateBy { it.name }
        check("bar roles in metadata", lm["Life"]?.barEmpty == "Back" && lm["Life"]?.barFull == "Frame/Fill" && lm["BigLife"]?.barFull == "../Other" && lm["BigLife"]?.barEmpty == "")
        check("bar lines are not sent to the compiler", !low.source.lines().any { it.trim().startsWith("empty @") || it.trim().startsWith("full @") })
        val back = MetaFile.read(MetaFile.write(low.metas))
        check("bar roles survive the meta file", back["Life"]?.barEmpty == "Back" && back["Life"]?.barFull == "Frame/Fill" && back["BigLife"]?.barFull == "../Other")
        check("empty/full only for DynamicBar scripts", lowerOne("script A of Node2D:\n\tempty @Back\n").diags.any { "DynamicBar" in it.message })
        check("empty/full not inside a function", lowerOne("script A of DynamicBar:\n\tfn onReady():\n\t\tfull @Fill\n").diags.any { "one tab" in it.message })

        Bars.register(back.values)
        val t = SceneTree()
        val root = t.create("Node2D", "Root"); t.setRoot(root)
        val bar = t.create("DynamicBar", "Bar"); bar.script = "scripts/life.nbs"; t.add(root, bar)
        val bg = t.create("Sprite", "Back"); t.add(bar, bg)
        val frame = t.create("Node2D", "Frame"); t.add(bar, frame)
        val fill = t.create("Sprite", "Fill"); t.add(frame, fill)
        bar.props["value"] = 25.0; bar.props["minValue"] = 0.0; bar.props["maxValue"] = 100.0
        check("bar finds its two sprites", Bars.emptySprite(bar) === bg && Bars.fullSprite(bar) === fill)
        check("only the full sprite is cropped", Bars.fillOf(bg) == null && Bars.fillOf(fill)?.first == 0.25 && Bars.fillOf(fill)?.second == "right")
        bar.props["value"] = 150.0; check("fraction clamps above max", Bars.fraction(bar) == 1.0)
        bar.props["value"] = -5.0; check("fraction clamps below min", Bars.fraction(bar) == 0.0)
        bar.props["minValue"] = 50.0; bar.props["maxValue"] = 150.0; bar.props["value"] = 100.0
        check("fraction uses min and max", Bars.fraction(bar) == 0.5)
        bar.props["maxValue"] = 50.0; check("backwards range counts as empty", Bars.fraction(bar) == 0.0)
        bar.props["direction"] = "up"; bar.props["maxValue"] = 150.0
        check("direction is passed on", Bars.fillOf(fill)?.second == "up")
        val loose = t.create("Sprite", "Loose"); t.add(root, loose)
        check("sprites outside a bar are untouched", Bars.fillOf(loose) == null)
        bar.script = "scripts/missing.nbs"; check("a bar without a known script has no roles", Bars.emptySprite(bar) == null && Bars.fillOf(fill) == null)
        // a script that inherits the roles of another one
        val bar2 = t.create("DynamicBar", "Bar2"); bar2.script = "scripts/big.nbs"; t.add(root, bar2)
        val back2 = t.create("Sprite", "Back"); t.add(bar2, back2)
        check("roles are inherited from the base script", Bars.emptySprite(bar2) === back2 && Bars.rolesOf(bar2)?.full == "../Other")
        Bars.register(emptyList())
    }

    // --- Buttons that look like their Sprite children keep working as buttons
    run {
        val files = HashMap<String, String>()
        files["b.nscn"] = "scene \"b\"\nnode R Node2D\n\tnode Pad Button\n\t\taction = \"left\"\n\t\tfixed = true\n\t\tx = 100\n\t\ty = 100\n\t\twidth = 80\n\t\theight = 80\n\t\tnode Look Sprite\n\t\t\timage = \"assets/pad.png\"\n"
        val e = Engine({ files[it] }, emptyMap(), FakeHost(), { })
        check("button with a Sprite child loads", e.loadScene("b.nscn"))
        val pad = e.tree.find(e.tree.root!!, "/R/Pad")!!
        check("the Sprite is a child of the button", pad.children.singleOrNull()?.type == "Sprite")
        e.setPointers(listOf(100.0 to 100.0)); check("button with a Sprite child still counts as pressed", e.input.isDown("left"))
        e.setPointers(emptyList()); check("button released", !e.input.isDown("left"))
        check("fixed carries over to the Sprite", pad.children[0].isFixed())
        // pressed look: a Sprite called Pressed replaces the others while the button is held
        val down = SceneNode(0, "Pressed", "Sprite"); pad.children.add(down); down.parent = pad
        val look = pad.children[0]
        check("not held: normal look shown, Pressed hidden", !ButtonSkin.hidden(look, false) && ButtonSkin.hidden(down, false))
        e.setPointers(listOf(100.0 to 100.0))
        check("held: only Pressed shown", pad.b("_pressed") && ButtonSkin.hidden(look, false) && !ButtonSkin.hidden(down, false))
        check("the editor shows both looks", !ButtonSkin.hidden(look, true) && !ButtonSkin.hidden(down, true))
        e.setPointers(listOf(500.0 to 500.0)); check("finger elsewhere: not pressed", !pad.b("_pressed") && !ButtonSkin.hidden(look, false))
        e.setPointers(emptyList()); check("released: not pressed", !pad.b("_pressed"))
        pad.children.remove(down); down.parent = null
        e.setPointers(listOf(100.0 to 100.0)); check("without a Pressed sprite nothing is hidden", !ButtonSkin.hidden(look, false))
        e.setPointers(emptyList())
    }

    // --- opening a folder or a zip as a project
    run {
        val base = File(tmp, "import").also { it.deleteRecursively(); it.mkdirs() }
        val root = File(base, "NebulaProjects")
        fun zipOf(entries: Map<String, String>): ByteArray {
            val bo = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bo).use { z -> for ((n, t) in entries) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
            return bo.toByteArray()
        }
        // a zip with everything inside one wrapper folder, a stale build and junk from another system
        val wrapped = zipOf(mapOf(
            "My Game/project.nebula" to "title=Space Shooter\nscene=scenes/level1.nscn\n", "My Game/scenes/level1.nscn" to "scene \"L1\"\nnode R Node2D\n",
            "My Game/scripts/a.nbs" to "script A of Node2D:\n\tpass\n", "My Game/assets/x.txt" to "hi", "My Game/build/libscripts.so" to "old",
            "__MACOSX/My Game/._a" to "junk", "My Game/.git/config" to "x"))
        val p1 = ProjectImport.importZip(wrapped.inputStream(), root, "download.zip", File(base, "scratch"))
        check("zip: opened inside the projects folder under its title", p1.dir.parentFile == root && p1.dir.name == "Space Shooter" && p1.title == "Space Shooter", p1.dir.path)
        check("zip: files arrive", p1.readText("scripts/a.nbs") != null && p1.readText("assets/x.txt") == "hi" && p1.mainScene == "scenes/level1.nscn" && p1.readText(p1.mainScene) != null)
        check("zip: stale build and junk are dropped", !p1.file("build").exists() && !p1.file(".git").exists() && !File(root, "__MACOSX").exists())
        check("zip: scratch folder is cleaned up", File(base, "scratch").listFiles().orEmpty().isEmpty())
        val p2 = ProjectImport.importZip(wrapped.inputStream(), root, "download.zip", File(base, "scratch"))
        check("zip: opening it again gets its own folder", p2.dir.name == "Space Shooter 2" && p1.dir.exists())
        check("zip: it is listed like any project", root.listFiles { f -> File(f, "project.nebula").exists() }!!.size == 2)
        // a zip of just the folders, no project.nebula, no wrapper
        val bare = zipOf(mapOf("scenes/intro.nscn" to "scene \"I\"\nnode R Node2D\n", "scripts/b.nbs" to "script B of Node2D:\n\tpass\n"))
        val p3 = ProjectImport.importZip(bare.inputStream(), root, "cool-game.zip", File(base, "scratch"))
        check("zip without project.nebula gets one", p3.dir.name == "cool-game" && File(p3.dir, "project.nebula").isFile && p3.title == "cool-game")
        check("zip without a main.nscn uses the scene it has", p3.mainScene == "scenes/intro.nscn" && p3.file("assets").isDirectory && p3.file("animations").isDirectory)
        check("an unsafe zip is refused", runCatching { ProjectImport.importZip(zipOf(mapOf("../evil.txt" to "x")).inputStream(), root, "bad.zip", File(base, "scratch")) }.exceptionOrNull() is ProjectImport.ImportException && !File(base, "evil.txt").exists())
        check("an empty zip is refused", runCatching { ProjectImport.importZip(zipOf(emptyMap()).inputStream(), root, "e.zip", File(base, "scratch")) }.exceptionOrNull() is ProjectImport.ImportException)
        check("something that is not a zip is refused", runCatching { ProjectImport.importZip("hello".byteInputStream(), root, "n.zip", File(base, "scratch")) }.isFailure)
        // a folder
        val src = File(base, "elsewhere/Platformer").also { it.mkdirs() }
        File(src, "scenes").mkdirs(); File(src, "scenes/main.nscn").writeText("scene \"M\"\nnode R Node2D\n"); File(src, "build").mkdirs(); File(src, "build/x.so").writeText("old")
        File(src, "assets/deep").mkdirs(); File(src, "assets/deep/build").mkdirs(); File(src, "assets/deep/build/keep.txt").writeText("keep")
        val p4 = ProjectImport.importFolder(src, root)
        check("folder: copied under its own name", p4.dir.name == "Platformer" && p4.dir != src && p4.readText("scenes/main.nscn") != null && src.exists())
        check("folder: only the top-level build is skipped", !p4.file("build").exists() && p4.readText("assets/deep/build/keep.txt") == "keep")
        val again = ProjectImport.importFolder(p4.dir, root)
        check("a project already in the projects folder is opened where it is", again.dir == p4.dir && root.listFiles()!!.count { it.name.startsWith("Platformer") } == 1)
        check("a file is not a folder", runCatching { ProjectImport.importFolder(File(src, "scenes/main.nscn"), root) }.exceptionOrNull() is ProjectImport.ImportException)
    }

    // --- DynamicBar script API compiles with snc and the bar scene loads
    run {
        val bp = Project.create(File(tmp, "bars"), "Bars", preset = Preset.BLANK)
        File(bp.dir, "scenes/main.nscn").writeText("scene \"S\"\nnode World Node2D\n\tnode Life DynamicBar\n\t\tx = 100\n\t\ty = 40\n\t\tmaxValue = 50\n\t\tvalue = 50\n\t\tnode Back Sprite\n\t\tnode Fill Sprite\n\t\tscript = \"scripts/life.nbs\"\n")
        File(bp.dir, "scripts/life.nbs").writeText("script Life of DynamicBar:\n\tempty @Back\n\tfull @Fill\n\tfn onReady():\n\t\tsetRange(0.0, 200.0)\n\t\tsetValue(150.0)\n\t\tdrain(10.0)\n\t\tfill(5.0)\n\t\tsetMax(300.0)\n\t\tsetMin(10.0)\n\t\tlog(percent())\n\t\tif isFull():\n\t\t\temptyOut()\n\t\tif isEmpty():\n\t\t\tfillUp()\n\t\tlog(value())\n\t\tlog(minValue())\n\t\tlog(maxValue())\n\t\tsetDirection(\"up\")\n")
        File(bp.dir, "scripts/hud.nbs").writeText("script Hud of Node2D:\n\tfn onReady():\n\t\t(@Life as DynamicBar).drain(1.0)\n\t\t(@/World/Life as DynamicBar).fill(2.0)\n\t\t(@Life as Button).isPressed()\n")
        val r = Builder(bp, ProcessRunner(), apiDir.absoluteFile, snc).build()
        check("DynamicBar API compiles with snc", r.ok, r.messages.joinToString("\n     "))
        val e = Engine({ bp.readText(it) }, emptyMap(), FakeHost(), { })
        check("bar scene loads", e.loadScene("scenes/main.nscn"))
        val life = e.tree.find(e.tree.root!!, "/World/Life")!!
        check("bar has value, min and max", life.f("value") == 50.0 && life.f("minValue") == 0.0 && life.f("maxValue") == 50.0 && life.children.size == 2)
        Bars.register(emptyList())
    }

    // --- sprite editor: tools, undo/redo, frames, onion skin, sheets
    run {
        val red = 0xFFFF0000.toInt(); val blue = 0xFF0000FF.toInt()
        fun px(d: SpriteDoc, x: Int, y: Int) = d.frame[y * d.width + x]
        fun count(d: SpriteDoc) = d.frame.count { it != 0 }
        val d = SpriteDoc.blank(16, 16)
        // pixel brush
        var st = d.stroke(SpriteDoc.Brush(1, 1f, red, pixel = true)); st.dab(3, 4); st.dab(4, 4)
        check("nothing changes before commit", count(d) == 0); check("commit paints", st.commit() && px(d, 3, 4) == red && px(d, 4, 4) == red && count(d) == 2)
        st = d.stroke(SpriteDoc.Brush(2, 1f, blue, pixel = true)); st.dab(8, 8); st.commit()
        check("pixel brush size 2 is a hard 2x2 block", count(d) == 6 && px(d, 8, 8) == blue && px(d, 9, 9) == blue && px(d, 7, 7) == 0 && px(d, 10, 10) == 0)
        st = d.stroke(SpriteDoc.Brush(3, 1f, blue, pixel = true)); st.dab(12, 12); st.commit()
        check("pixel brush size 3 is centred", px(d, 11, 12) == blue && px(d, 13, 12) == blue && px(d, 12, 11) == blue && px(d, 10, 12) == 0)
        check("an empty stroke records nothing", d.stroke(SpriteDoc.Brush(1, 1f, red, true)).commit() == false)
        // undo / redo
        val n = count(d)
        d.undo(); check("undo", count(d) < n && d.canRedo); d.redo(); check("redo", count(d) == n)
        st = d.stroke(SpriteDoc.Brush(1, 1f, red, true)); st.dab(0, 0); st.commit(); check("a new stroke clears redo", !d.canRedo)
        // opacity does not build up inside one stroke, but does across strokes
        val o = SpriteDoc.blank(8, 8)
        st = o.stroke(SpriteDoc.Brush(1, 0.5f, red, true)); st.dab(2, 2); st.dab(2, 2); st.dab(2, 2); st.commit()
        val once = px(o, 2, 2); check("opacity 50% gives half alpha", Argb.a(once) in 127..128 && Argb.r(once) == 255)
        st = o.stroke(SpriteDoc.Brush(1, 0.5f, red, true)); st.dab(2, 2); st.commit()
        check("a second stroke adds up", Argb.a(px(o, 2, 2)) > Argb.a(once))
        // regular brush is soft
        val soft = SpriteDoc.blank(16, 16)
        st = soft.stroke(SpriteDoc.Brush(7, 1f, red, pixel = false)); st.dab(8, 8); st.commit()
        check("regular brush has soft edges", Argb.a(px(soft, 8, 8)) == 255 && soft.frame.any { Argb.a(it) in 1..254 })
        // eraser
        st = soft.stroke(SpriteDoc.Brush(3, 1f, 0, pixel = true, erase = true)); st.dab(8, 8); st.commit()
        check("eraser clears pixels", px(soft, 8, 8) == 0 && px(soft, 8, 9) == 0)
        // line, rect, ellipse
        val sh = SpriteDoc.blank(20, 20)
        st = sh.stroke(SpriteDoc.Brush(1, 1f, red, true)); st.line(1, 1, 10, 4); st.commit()
        check("line reaches both ends", px(sh, 1, 1) == red && px(sh, 10, 4) == red && count(sh) == 10)
        val r = SpriteDoc.blank(20, 20)
        st = r.stroke(SpriteDoc.Brush(1, 1f, red, true)); st.rect(2, 2, 6, 5, filled = false); st.commit()
        check("rectangle outline", px(r, 2, 2) == red && px(r, 6, 5) == red && px(r, 4, 3) == 0 && count(r) == 14)
        st = r.stroke(SpriteDoc.Brush(1, 1f, blue, true)); st.rect(10, 10, 12, 12, filled = true); st.commit()
        check("filled rectangle", px(r, 11, 11) == blue && px(r, 10, 12) == blue)
        st = r.stroke(SpriteDoc.Brush(1, 1f, red, true)); st.rect(5, 18, 2, 15, filled = true); st.commit()
        check("shape corners can be dragged any way", px(r, 3, 16) == red)
        val e = SpriteDoc.blank(20, 20)
        st = e.stroke(SpriteDoc.Brush(1, 1f, red, true)); st.ellipse(2, 2, 13, 9, filled = true); st.commit()
        check("filled ellipse is round", px(e, 8, 6) == red && px(e, 2, 2) == 0 && px(e, 13, 9) == 0 && px(e, 2, 5) == red)
        val eo = SpriteDoc.blank(20, 20)
        st = eo.stroke(SpriteDoc.Brush(1, 1f, red, true)); st.ellipse(2, 2, 13, 9, filled = false); st.commit()
        check("ellipse outline is hollow", px(eo, 7, 5) == 0 && count(eo) > 20 && eo.frame.indices.any { eo.frame[it] == red })
        // a shape can be dragged: reset redraws from the picture as it was
        val dr = SpriteDoc.blank(12, 12)
        st = dr.stroke(SpriteDoc.Brush(1, 1f, red, true)); st.rect(1, 1, 8, 8, false); st.reset(); st.rect(1, 1, 3, 3, false); st.commit()
        check("reset redraws a dragged shape", px(dr, 8, 8) == 0 && px(dr, 3, 3) == red)
        // fill
        val f = SpriteDoc.blank(10, 10)
        st = f.stroke(SpriteDoc.Brush(1, 1f, red, true)); st.rect(2, 2, 7, 7, false); st.commit()
        check("fill inside", f.fill(4, 4, blue, 1f) && px(f, 4, 4) == blue && px(f, 3, 3) == blue && px(f, 2, 2) == red && px(f, 0, 0) == 0)
        check("fill stops at the outline", px(f, 8, 8) == 0 && f.frame.count { it == blue } == 16)
        check("fill with the same colour does nothing", !f.fill(4, 4, blue, 1f))
        check("fill outside the picture does nothing", !f.fill(-1, 0, blue, 1f) && !f.fill(10, 10, blue, 1f))
        check("fill outside the box", f.fill(0, 0, red, 1f) && px(f, 9, 9) == red && px(f, 4, 4) == blue)
        val ft = SpriteDoc.blank(4, 1); ft.frame.also { }
        run { val s2 = ft.stroke(SpriteDoc.Brush(1, 1f, 0xFF808080.toInt(), true)); s2.dab(1, 0); s2.commit() }
        check("fill tolerance", ft.fill(1, 0, red, 1f, tolerance = 0) && px(ft, 1, 0) == red)
        val half = SpriteDoc.blank(3, 1); half.fill(0, 0, red, 0.5f)
        check("fill with opacity", Argb.a(px(half, 1, 0)) in 127..128)
        check("undo a fill", f.canUndo.also { f.undo() } && px(f, 9, 9) == 0)
        // eyedropper
        check("eyedropper", f.pick(2, 2) == red && f.pick(0, 0) == 0 && f.pick(-1, 0) == null && f.pick(10, 0) == null)
        // frames
        val a = SpriteDoc.blank(4, 4)
        run { val s2 = a.stroke(SpriteDoc.Brush(1, 1f, red, true)); s2.dab(0, 0); s2.commit() }
        a.addFrame(copy = true); check("copied frame is selected and equal", a.frameCount == 2 && a.current == 1 && a.frame[0] == red)
        run { val s2 = a.stroke(SpriteDoc.Brush(1, 1f, blue, true)); s2.dab(1, 1); s2.commit() }
        check("frames are independent", a.frames[0][5] == 0 && a.frames[1][5] == blue)
        a.addFrame(copy = false); check("blank frame", a.frameCount == 3 && a.frame.all { it == 0 })
        a.moveFrame(-1); check("move frame", a.current == 1 && a.frames[1].all { it == 0 } && a.frames[2][5] == blue)
        a.undo(); check("undo a frame move", a.current == 2 && a.frames[1][5] == blue)
        a.undo(); check("undo adding a frame", a.frameCount == 2)
        a.select(0); a.deleteFrame(); check("delete frame", a.frameCount == 1 && a.frame[5] == blue)
        a.deleteFrame(); check("the last frame cannot be deleted", a.frameCount == 1)
        a.clearFrame(); check("clear frame", a.frame.all { it == 0 }); a.undo(); check("undo clear", a.frame[5] == blue)
        // onion skin
        val on = SpriteDoc(2, 2, listOf(IntArray(4), IntArray(4), IntArray(4), IntArray(4)))
        on.select(0); check("no earlier frame at the start", on.onion(1, 1, wrap = false).map { it.index } == listOf(1))
        check("looping onion wraps", on.onion(1, 1, wrap = true).map { it.index }.sorted() == listOf(1, 3))
        on.select(2); val ol = on.onion(2, 1, false)
        check("onion before and after, fading", ol.map { it.offset } == listOf(-1, -2, 1) && ol[0].alpha > ol[1].alpha)
        check("one frame has no onion", SpriteDoc.blank(2, 2).onion(2, 2, true).isEmpty())
        val g = Argb.ghost(intArrayOf(red, 0), 0xFF00FF00.toInt(), 0.4f)
        check("ghost frames are tinted and faint", Argb.a(g[0]) == 102 && Argb.g(g[0]) > 0 && g[1] == 0)
        // sheets
        val sd = SpriteDoc(2, 2, listOf(intArrayOf(1, 1, 1, 1), intArrayOf(2, 2, 2, 2), intArrayOf(3, 3, 3, 3)))
        val (sp, sw, shh) = sd.toSheet()
        check("sheet layout, one row", sw == 6 && shh == 2 && sp[0] == 1 && sp[2] == 2 && sp[4] == 3)
        val (sp2, sw2, sh2) = sd.toSheet(2)
        check("sheet layout, two columns", sw2 == 4 && sh2 == 4 && sp2[2] == 2 && sp2[8] == 3 && sp2[10] == 0)
        val back = SpriteDoc.fromSheet(sp, sw, shh, 3, 1)
        check("sheet round trip", back.frameCount == 3 && back.width == 2 && back.frames[1][0] == 2 && back.frames[2][3] == 3)
        check("a bad grid keeps one frame", SpriteDoc.fromSheet(IntArray(6), 3, 2, 5, 1).frameCount == 1)
        check("colour hex", Argb.parse("#FF8000") == 0xFFFF8000.toInt() && Argb.parse("80FF8000") == 0x80FF8000.toInt() && Argb.parse("zz") == null && Argb.hex(0xFFFF8000.toInt()) == "#FF8000" && Argb.parse(Argb.hex(0x80FF8000.toInt())) == 0x80FF8000.toInt())
        check("undo history is capped", run { val c = SpriteDoc.blank(2, 2); repeat(SpriteDoc.MAX_UNDO + 20) { c.clearFrame() }; var k = 0; while (c.canUndo) { c.undo(); k++ }; k == SpriteDoc.MAX_UNDO })
    }

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    System.exit(if (failures == 0) 0 else 1)
}
