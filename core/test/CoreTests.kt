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
    val proj = Project.create(File(tmp, "game"), "Test Game")
    val lowered = Builder(proj, ProcessRunner(), apiDir.absoluteFile, snc).lower()
    check("starter lowers", lowered.ok, lowered.diags.toString())
    val res = Builder(proj, ProcessRunner(), apiDir.absoluteFile, snc).build()
    check("starter compiles with snc", res.ok, res.messages.joinToString("\n     "))
    check("meta file", MetaFile.read(File(proj.buildDir, "scripts.meta").readText()).let { it["Hero"]?.tweaks?.size == 2 && it["Score"]?.everyMs == listOf(5000) && it["Coin"]?.broadcasts?.size == 1 })

    // error remapping: a SuperNova type error must point at the .nbs line
    File(proj.dir, "scripts/hero.nbs").appendText("\tfn broken():\n\t\tvar n: Int = \"text\"\n")
    val bad = Builder(proj, ProcessRunner(), apiDir.absoluteFile, snc).build()
    check("errors remap to .nbs", !bad.ok && bad.messages.any { "scripts/hero.nbs:" in it }, bad.messages.joinToString("\n     "))
    File(proj.dir, "scripts/hero.nbs").writeText(Templates.starter("x")["scripts/hero.nbs"]!!)

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

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    System.exit(if (failures == 0) 0 else 1)
}
