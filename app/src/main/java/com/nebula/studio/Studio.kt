package com.nebula.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.nebula.studio.build.ApkExporter
import com.nebula.studio.build.ExportOptions
import com.nebula.studio.build.Installer
import com.nebula.studio.build.TermuxRunner
import com.nebula.studio.runtime.*
import nebula.core.*
import nebula.lang.*
import java.io.File
import java.util.Properties

enum class CenterTab { Scene, Script, Game }
enum class Panel { Scene, Files, Viewport, Inspector, Script, Console }   // used by the compact (single pane) layout
class LogLine(val level: Int, val text: String)   // 0 info, 1 warning, 2 error

/** An open script file with its own undo history. */
class ScriptDoc(val path: String, initial: String) {
    var value by mutableStateOf(TextFieldValue(initial))
    var saved by mutableStateOf(initial)
    private val undoStack = ArrayList<TextFieldValue>()
    private val redoStack = ArrayList<TextFieldValue>()
    private var lastEdit = 0L
    val dirty get() = value.text != saved

    fun change(nv: TextFieldValue) {
        if (nv.text != value.text) {
            val now = System.currentTimeMillis()
            if (undoStack.isEmpty() || now - lastEdit > 700 || Math.abs(nv.text.length - value.text.length) > 8) { undoStack.add(value); if (undoStack.size > 300) undoStack.removeAt(0) }
            redoStack.clear(); lastEdit = now
        }
        value = nv
    }
    fun undo() { val p = undoStack.removeLastOrNull() ?: return; redoStack.add(value); value = p }
    fun redo() { val n = redoStack.removeLastOrNull() ?: return; undoStack.add(value); value = n }
    fun insert(text: String) {
        val v = value; val s = v.selection.min; val e = v.selection.max
        change(TextFieldValue(v.text.substring(0, s) + text + v.text.substring(e), TextRange(s + text.length)))
    }
}

class GameSession(val engine: Engine, val images: ImageCache, val sounds: Sounds, val w: Int, val h: Int)

sealed class Modal {
    object NewProject : Modal()
    object NewNode : Modal()
    object NewScene : Modal()
    object Palette : Modal()
    object Export : Modal()
    object Settings : Modal()
    object Help : Modal()
    class Rename(val path: String) : Modal()
    class Confirm(val message: String, val action: () -> Unit) : Modal()
    class Pick(val title: String, val options: List<String>, val onPick: (String) -> Unit) : Modal()
}

class Studio(val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())

    // ---- project and scene
    var project by mutableStateOf<Project?>(null)
    var editor by mutableStateOf<SceneEditor?>(null)
    var rev by mutableIntStateOf(0)                    // bumped on every scene change so panels recompose
    var selectedPath by mutableStateOf<String?>(null)
    val collapsed = mutableStateListOf<String>()
    var scenePath by mutableStateOf<String?>(null)

    // ---- scripts
    val scripts = mutableStateListOf<ScriptDoc>()
    var activeScript by mutableStateOf<String?>(null)
    var lowered by mutableStateOf<Lowered?>(null)

    // ---- ui state
    var centerTab by mutableStateOf(CenterTab.Scene)
    var panel by mutableStateOf(Panel.Viewport)
    var leftTab by mutableIntStateOf(0)
    var consoleOpen by mutableStateOf(true)
    var modal by mutableStateOf<Modal?>(null)
    var showGrid by mutableStateOf(true)
    var snap by mutableIntStateOf(8)
    val console = mutableStateListOf<LogLine>()
    var building by mutableStateOf(false)
    var status by mutableStateOf("")
    var game by mutableStateOf<GameSession?>(null)
    var images by mutableStateOf<ImageCache?>(null)

    var importHook: (() -> Unit)? = null
    fun importAsset() { importHook?.invoke() }

    /** Copies a file the user picked into the project's assets folder. */
    fun importUri(uri: Uri) {
        val p = project ?: return
        try {
            var name = "asset"
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) } }
            val dest = File(p.dir, "assets/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_")); dest.parentFile?.mkdirs()
            ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            images?.clear(); rev++; info("Imported assets/${dest.name}")
        } catch (e: Exception) { error("Import failed: ${e.message}") }
    }

    private var libCounter = 0
    private val settingsFile = File(ctx.filesDir, "settings.properties")
    private val settings = Properties()

    init {
        if (settingsFile.exists()) settingsFile.inputStream().use { settings.load(it) }
        snap = settings.getProperty("snap", "8").toIntOrNull() ?: 8
        showGrid = settings.getProperty("grid", "true") == "true"
        installApi()
    }

    // ------------------------------------------------------------------ storage
    fun hasAllFiles() = Environment.isExternalStorageManager()
    fun requestAllFiles() {
        val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
    val root: File get() = if (hasAllFiles()) File(Environment.getExternalStorageDirectory(), "NebulaProjects")
                           else File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "NebulaProjects")
    val apiDir: File get() = File(root, ".nebula/api")

    /** nebula.sn ships inside the APK; the builder (Termux) reads it from here. */
    fun installApi() {
        try { apiDir.mkdirs(); File(apiDir, "nebula.sn").writeText(ctx.assets.open("nebula.sn").bufferedReader().readText()) } catch (e: Exception) { }
    }

    fun listProjects(): List<File> = root.listFiles { f -> f.isDirectory && File(f, "project.nebula").exists() }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun setSetting(key: String, value: String) { settings.setProperty(key, value); settingsFile.outputStream().use { settings.store(it, "Nebula settings") } }
    fun setting(key: String, def: String = "") = settings.getProperty(key, def) ?: def

    // ------------------------------------------------------------------ log
    fun log(level: Int, text: String) {
        val add = { for (l in text.lines()) if (l.isNotBlank()) { console.add(LogLine(level, l)); if (console.size > 600) console.removeAt(0) } }
        if (Looper.myLooper() == Looper.getMainLooper()) add() else main.post { add() }
    }
    fun info(t: String) = log(0, t)
    fun error(t: String) = log(2, t)
    fun post(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    // ------------------------------------------------------------------ projects
    fun createProject(name: String, pkg: String) {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9_ -]"), "").ifEmpty { "MyGame" }
        val dir = File(root, clean)
        if (dir.exists()) { error("A project called '$clean' already exists."); return }
        val p = Project.create(dir, clean, pkg.ifBlank { "com.nebula." + clean.lowercase().replace(Regex("[^a-z0-9]"), "") })
        openProject(p.dir)
    }

    fun openProject(dir: File) {
        closeProject()
        val p = Project(dir); project = p; images = ImageCache(DiskIO(p))
        setSetting("last", dir.name)
        info("Opened ${p.title}")
        openScene(p.mainScene)
        lint()
    }

    fun closeProject() {
        stop(); saveAll()
        project = null; editor = null; scenePath = null; scripts.clear(); activeScript = null; selectedPath = null; lowered = null
        centerTab = CenterTab.Scene
    }

    fun openScene(path: String) {
        val p = project ?: return
        saveScene()
        val ed = SceneEditor { p.readText(it) }
        val text = p.readText(path)
        if (text == null) { ed.newScene(path, File(path).nameWithoutExtension.replaceFirstChar { it.uppercase() }) }
        else try { ed.load(path, text) } catch (e: SceneParseException) { error("$path: ${e.message}"); return }
        editor = ed; scenePath = path; selectedPath = null; collapsed.clear(); rev++
        centerTab = CenterTab.Scene
    }

    fun newScene(name: String) {
        val p = project ?: return
        val clean = name.trim().replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { return }
        val path = "scenes/$clean.nscn"
        if (p.file(path).exists()) { error("scene '$clean' already exists"); return }
        saveScene()
        val ed = SceneEditor { p.readText(it) }; ed.newScene(path, clean)
        editor = ed; scenePath = path; selectedPath = null; saveScene(force = true); rev++
    }

    // ------------------------------------------------------------------ scene editing
    val tree: SceneTree? get() { rev; return editor?.tree }
    fun selected(): SceneNode? { rev; val t = editor?.tree ?: return null; val p = selectedPath ?: return null; return t.find(t.root ?: return null, p) }
    fun select(n: SceneNode?) { selectedPath = n?.path() }

    /** Runs an editor operation and refreshes the panels. */
    fun <T> change(op: (SceneEditor) -> T): T? {
        val ed = editor ?: return null
        val sel = selected()
        val r = op(ed)
        // keep the selection if the node still exists (names may have changed)
        if (sel != null && selectedPath != null && selected() == null) selectedPath = null
        rev++
        return r
    }

    fun addNode(type: String) {
        val parent = selected() ?: editor?.tree?.root ?: return
        val n = change { it.addNode(parent, type) } ?: return
        select(n)
    }
    fun deleteSelected() { val n = selected() ?: return; if (change { it.delete(n) } == true) selectedPath = null else info("The root node cannot be deleted.") }
    fun duplicateSelected() { val n = selected() ?: return; change { it.duplicate(n) }?.let { select(it) } }
    fun renameNode(path: String, name: String) {
        val t = editor?.tree ?: return; val n = t.find(t.root ?: return, path) ?: return
        change { it.rename(n, name) }; select(n)
    }
    fun moveSelected(delta: Int) { val n = selected() ?: return; change { it.move(n, delta) } }
    fun indentSelected() { val n = selected() ?: return; change { it.indent(n) } }
    fun outdentSelected() { val n = selected() ?: return; change { it.outdent(n) } }
    fun undo() { change { it.undo() } ?: return; if (selected() == null) selectedPath = null }
    fun redo() { change { it.redo() } }

    fun saveScene(force: Boolean = false) {
        val p = project ?: return; val ed = editor ?: return; val path = ed.path ?: return
        if (!ed.dirty && !force) return
        try { p.file(path).parentFile?.mkdirs(); p.file(path).writeText(ed.toText()); ed.markSaved() } catch (e: Exception) { error("Cannot save $path: ${e.message}") }
    }

    fun saveAll() {
        saveScene()
        val p = project ?: return
        for (d in scripts) if (d.dirty) try { p.file(d.path).writeText(d.value.text); d.saved = d.value.text } catch (e: Exception) { error("Cannot save ${d.path}: ${e.message}") }
        p.save()
    }

    // ------------------------------------------------------------------ scripts
    fun openScript(path: String, line: Int = 0) {
        val p = project ?: return
        var doc = scripts.firstOrNull { it.path == path }
        if (doc == null) {
            val text = p.readText(path) ?: run { error("Script $path not found"); return }
            doc = ScriptDoc(path, text); scripts.add(doc)
        }
        if (line > 0) {
            val lines = doc.value.text.split('\n'); val off = lines.take(line - 1).sumOf { it.length + 1 }.coerceAtMost(doc.value.text.length)
            doc.value = doc.value.copy(selection = TextRange(off))
        }
        activeScript = path; centerTab = CenterTab.Script; panel = Panel.Script
    }

    fun closeScript(path: String) {
        val d = scripts.firstOrNull { it.path == path } ?: return
        if (d.dirty) project?.file(path)?.writeText(d.value.text)
        scripts.remove(d)
        if (activeScript == path) { activeScript = scripts.lastOrNull()?.path; if (activeScript == null) centerTab = CenterTab.Scene }
        lint()
    }

    fun deleteFile(rel: String) {
        val p = project ?: return
        scripts.removeAll { it.path == rel }; if (activeScript == rel) activeScript = scripts.lastOrNull()?.path
        p.file(rel).delete()
        change { ed -> ed.tree.all().filter { it.script == rel }.forEach { ed.setScript(it, null) } }
        images?.clear(); lint()
    }

    /** New script file for the selected node: `script Name of <node type>:` with the usual hooks. */
    fun newScriptForSelected() {
        val p = project ?: return; val n = selected() ?: run { info("Select a node first."); return }
        val cls = n.name.replace(Regex("[^A-Za-z0-9_]"), "").replaceFirstChar { it.uppercase() }.ifEmpty { "Script" }
        var name = cls; var i = 2
        while (p.file("scripts/${fileName(name)}").exists() || p.scripts().any { it.endsWith("/" + fileName(name)) }) name = cls + i++
        val path = "scripts/${fileName(name)}"
        val base = if (n.type == "Node") "Node" else n.type
        p.file(path).parentFile?.mkdirs()
        p.file(path).writeText("script $name of $base:\n\tfn onReady():\n\t\tlog(\"$name is ready\")\n\n\tfn onFrame():\n\t\tpass\n")
        change { it.setScript(n, path) }
        openScript(path); lint()
    }
    private fun fileName(cls: String) = cls.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase() + ".nbs"

    fun attachScript(path: String?) { val n = selected() ?: return; change { it.setScript(n, path) }; lint() }

    private var lintToken = 0
    /** Re-runs the transpiler on every script (using unsaved text) for live diagnostics and tweak info. */
    fun lint() {
        val p = project ?: return
        val sources = p.scripts().map { path -> ScriptSource(path, scripts.firstOrNull { it.path == path }?.value?.text ?: p.readText(path) ?: "") }
        lowered = Transpiler.lower(sources); lintToken++
    }

    fun metaOf(path: String): ScriptMeta? = lowered?.metas?.firstOrNull { it.file == path }

    // ------------------------------------------------------------------ build / run
    private fun builder(p: Project): Builder {
        val termux = TermuxRunner(ctx)
        return Builder(p, termux, apiDir, command = { termux.buildCommand(it) })
    }

    fun build(then: ((Boolean) -> Unit)? = null) {
        val p = project ?: return
        if (building) return
        saveAll(); lint()
        val l = lowered
        if (l != null && !l.ok) { l.diags.forEach { error(it.toString()) }; status = "${l.diags.size} script error(s)"; centerTab = CenterTab.Script.takeIf { scripts.isNotEmpty() } ?: centerTab; then?.invoke(false); return }
        if (p.scripts().isEmpty()) { info("No scripts to build."); then?.invoke(true); return }
        building = true; status = "Building scripts..."; info("Building scripts with Termux...")
        Thread {
            val res = try { builder(p).build() } catch (e: Exception) { BuildResult(false, listOf("build failed: ${e.message}"), null) }
            post {
                building = false
                if (res.ok) { status = "Build OK"; info("Build OK") } else { status = "Build failed"; res.messages.forEach { error(it) } }
                then?.invoke(res.ok)
            }
        }.start()
    }

    fun play(sceneOverride: String? = null) {
        val p = project ?: return
        stop(); saveAll()
        val scene = sceneOverride ?: p.mainScene
        if (!p.file(scene).exists()) { error("Main scene '$scene' not found. Set one in Settings."); return }
        if (p.scripts().isEmpty()) { startGame(p, scene, null); return }
        build { ok -> if (ok) startGame(p, scene, File(p.buildDir, "libscripts.so")) }
    }

    private fun startGame(p: Project, scene: String, so: File?) {
        val io = DiskIO(p)
        val sounds = Sounds(ctx, io); val imgs = ImageCache(io)
        val metas = if (so != null) p.file("build/scripts.meta").takeIf { it.isFile }?.let { MetaFile.read(it.readText()) } ?: emptyMap() else emptyMap()
        var host: JnaScriptHost? = null
        if (so != null) {
            try {
                val dir = File(ctx.filesDir, "nebula_lib").also { it.mkdirs() }
                dir.listFiles()?.forEach { if (it.name.startsWith("libscripts_")) it.delete() }
                val copy = File(dir, "libscripts_${libCounter++}.so"); so.copyTo(copy, overwrite = true)
                host = JnaScriptHost(copy.absolutePath, PrefsSaveStore(ctx, p.dir.name))
            } catch (t: Throwable) { error("Cannot load the built scripts: ${t.message}"); return }
        }
        val remap = lowered
        val engine = Engine({ io.readText(it) }, metas, host, { info(it) }, { remap?.remap(it) ?: it }, { sounds.play(it) })
        engine.screenW = p.gameWidth.toDouble(); engine.screenH = p.gameHeight.toDouble()
        if (host != null) { host.engine = engine; host.start()?.let { error("script init: ${remap?.remap(it) ?: it}"); return } }
        if (!engine.loadScene(scene)) return
        game = GameSession(engine, imgs, sounds, p.gameWidth, p.gameHeight)
        centerTab = CenterTab.Game; status = "Running ${File(scene).nameWithoutExtension}"; info("Running $scene")
    }

    fun stop() {
        val g = game ?: return
        try { g.engine.shutdown() } catch (e: Throwable) { }
        g.sounds.release(); game = null
        if (centerTab == CenterTab.Game) centerTab = if (activeScript != null && scripts.isNotEmpty()) CenterTab.Scene else CenterTab.Scene
        status = "Stopped"
    }

    // ------------------------------------------------------------------ export
    fun export(pkg: String, label: String, install: Boolean) {
        val p = project ?: return
        p.packageId = pkg; p.title = label; p.save()
        build { ok ->
            if (!ok) return@build
            building = true; status = "Exporting APK..."
            Thread {
                try {
                    val so = File(p.buildDir, "libscripts.so").takeIf { p.scripts().isNotEmpty() && it.isFile }
                    val meta = p.file("build/scripts.meta").takeIf { it.isFile }?.readText() ?: "# none\n"
                    val keystore = setting("keystore").takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
                    val out = File(p.dir, "export/${label.replace(Regex("[^A-Za-z0-9_-]"), "_")}.apk")
                    val opt = if (keystore != null) ExportOptions(pkg, label, out, keystore, setting("keystore_pass", "nebula"), setting("keystore_alias", "nebula"), setting("keystore_key_pass", "nebula"))
                              else ExportOptions(pkg, label, out)
                    val f = ApkExporter.export(ctx, p, opt, so, meta)
                    post { building = false; status = "Exported"; info("Exported ${f.absolutePath} (${f.length() / 1024} KB)"); if (install) Installer.install(ctx, f) { info(it) } }
                } catch (e: Throwable) { post { building = false; status = "Export failed"; error("Export failed: ${e.message}") } }
            }.start()
        }
    }
}
