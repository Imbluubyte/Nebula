package nebula.core

import nebula.lang.*
import java.io.File
import java.util.Properties

/** A Nebula project folder: project.nebula, scenes/ (.nscn), scripts/ (.nbs), assets/, build/ (generated). */
class Project(val dir: File) {
    val config = Properties()
    val configFile get() = File(dir, "project.nebula")
    val buildDir get() = File(dir, "build")

    init { if (configFile.exists()) configFile.inputStream().use { config.load(it) } }

    var title: String get() = config.getProperty("title", dir.name); set(v) { config.setProperty("title", v) }
    var mainScene: String get() = config.getProperty("scene", "scenes/main.nscn"); set(v) { config.setProperty("scene", v) }
    var packageId: String get() = config.getProperty("package", "com.nebula.game"); set(v) { config.setProperty("package", v) }
    var version: String get() = config.getProperty("version", "1"); set(v) { config.setProperty("version", v) }
    var landscape: Boolean get() = config.getProperty("orientation", "landscape") == "landscape"
        set(v) { config.setProperty("orientation", if (v) "landscape" else "portrait") }
    /** Size of the game's logical screen; the view scales to fit. */
    var gameWidth: Int get() = config.getProperty("width", "1280").toInt(); set(v) { config.setProperty("width", v.toString()) }
    var gameHeight: Int get() = config.getProperty("height", "720").toInt(); set(v) { config.setProperty("height", v.toString()) }

    fun save() { dir.mkdirs(); configFile.outputStream().use { config.store(it, "Nebula project") } }

    fun file(rel: String) = File(dir, rel)
    fun readText(rel: String): String? = file(rel).takeIf { it.isFile }?.readText()

    fun list(sub: String, ext: String): List<String> =
        File(dir, sub).walkTopDown().filter { it.isFile && it.name.endsWith(ext) }
            .map { it.relativeTo(dir).path.replace('\\', '/') }.sorted().toList()

    fun scenes() = list("scenes", ".nscn")
    fun scripts() = list("scripts", ".nbs")
    fun assets() = list("assets", "").filter { !it.endsWith("/") }

    fun scriptSources(): List<ScriptSource> = scripts().map { ScriptSource(it, file(it).readText()) }

    companion object {
        fun create(dir: File, title: String, packageId: String = "com.nebula.game"): Project {
            dir.mkdirs()
            val p = Project(dir); p.title = title; p.packageId = packageId; p.save()
            for ((path, text) in Templates.starter(title)) { val f = File(dir, path); f.parentFile.mkdirs(); f.writeText(text) }
            return p
        }
    }
}

/** Command runner so the build works on a desktop JVM (tests) and through Termux on Android. */
interface CommandRunner { fun run(cmd: List<String>, workDir: File, env: Map<String, String>): Pair<Int, String> }

class ProcessRunner : CommandRunner {
    override fun run(cmd: List<String>, workDir: File, env: Map<String, String>): Pair<Int, String> {
        val pb = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start(); val text = p.inputStream.bufferedReader().readText()
        return p.waitFor() to text
    }
}

class BuildResult(val ok: Boolean, val messages: List<String>, val lowered: Lowered?)

/** scripts (.nbs) -> game.sn -> libscripts.so. The compiler (`snc`) and a C compiler must be installed. */
class Builder(private val project: Project, private val runner: CommandRunner, private val apiDir: File,
              private val snc: String = "snc", private val env: Map<String, String> = emptyMap()) {

    /** Only the transpile step (fast, no compiler needed): used for live diagnostics in the editor. */
    fun lower(): Lowered = Transpiler.lower(project.scriptSources())

    fun build(): BuildResult {
        val lowered = lower()
        if (!lowered.ok) return BuildResult(false, lowered.diags.map { it.toString() }, lowered)
        val out = project.buildDir; out.mkdirs()
        File(out, "game.sn").writeText(lowered.source)
        File(out, "scripts.meta").writeText(MetaFile.write(lowered.metas))
        val (code, text) = try { runner.run(listOf(snc, "build", "game.sn", "--lib", "-I", apiDir.absolutePath, "-o", "libscripts.so"), out, env) }
                           catch (e: Exception) { return BuildResult(false, listOf("cannot run '$snc': ${e.message}. Install SuperNova (snc) and clang."), lowered) }
        if (code != 0) return BuildResult(false, lowered.remap(text).lines().filter { it.isNotBlank() }, lowered)
        return BuildResult(true, listOf("built ${lowered.metas.size} script(s)"), lowered)
    }
}
