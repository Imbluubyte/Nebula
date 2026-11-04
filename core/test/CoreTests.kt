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
    check("hear needs type", lowerOne("script A of Node2D:\n\tfn f():\n\t\thear tapped from @X -> f\n").diags.any { "kind of node" in it.message })
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

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    System.exit(if (failures == 0) 0 else 1)
}
