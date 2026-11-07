package com.nebula.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.nebula.studio.build.ApkExporter
import com.nebula.studio.build.Downloads
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

/** A `.nanim` file open in the animation manager. Every edit replaces [set] (so the UI updates) and is saved straight away. */
class AnimDoc(val path: String, initial: AnimSet, private val onChange: (AnimDoc) -> Unit) {
    var set by mutableStateOf(initial)
    var selected by mutableIntStateOf(0)

    fun edit(sheet: String = set.sheet, cols: Int = set.cols, rows: Int = set.rows, clips: List<Clip> = set.clips) {
        set = AnimSet(sheet, cols.coerceIn(1, 512), rows.coerceIn(1, 512), ArrayList(clips)); onChange(this)
    }

    fun updateClip(i: Int, name: String? = null, from: Int? = null, to: Int? = null, fps: Double? = null, loop: Boolean? = null) {
        val c = set.clips.getOrNull(i) ?: return
        var nn = name?.let { AnimSet.cleanName(it) }?.takeIf { it.isNotEmpty() } ?: c.name
        if (set.clips.withIndex().any { (k, o) -> k != i && o.name == nn }) nn = c.name       // names must be unique
        val top = set.cols * set.rows
        val list = ArrayList(set.clips)
        list[i] = Clip(nn, (from ?: c.from).coerceIn(1, top), (to ?: c.to).coerceIn(1, top), (fps ?: c.fps).coerceIn(0.0, 240.0), loop ?: c.loop)
        edit(clips = list)
    }

    fun addClip() {
        var n = "clip"; var k = 2
        while (set.clips.any { it.name == n }) n = "clip" + k++
        val list = ArrayList(set.clips); list.add(Clip(n, 1, 1, 8.0, true))
        selected = list.lastIndex; edit(clips = list)
    }

    fun removeClip(i: Int) {
        val list = ArrayList(set.clips); if (i !in list.indices) return
        list.removeAt(i); selected = selected.coerceAtMost(list.lastIndex).coerceAtLeast(0); edit(clips = list)
    }
}

class GameSession(val engine: Engine, val images: ImageCache, val sounds: Sounds, val w: Int, val h: Int)

sealed class Modal {
    object NewProject : Modal()
    object ImportGitHub : Modal()
    object NewNode : Modal()
    object NewScene : Modal()
    object Palette : Modal()
    object Export : Modal()
    object Settings : Modal()
    object Help : Modal()
    class Rename(val path: String) : Modal()
    class NewFolder(val parent: String) : Modal()
    object NewAnimation : Modal()
    object NewSprite : Modal()
    object Sprite : Modal()
    object Animation : Modal()
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
    var nodeClipboard by mutableStateOf<String?>(null)  // copied / cut node (with its children) as scene text; paste works in any scene

    /** Asks the scene view to zoom ("in" / "out") or "fit" (keyboard shortcuts); the view clears it once handled. A new object each time, so repeats count. */
    class ViewRequest(val kind: String)
    var viewRequest by mutableStateOf<ViewRequest?>(null)

    // ---- tile painting (a TileMap is painted straight in the scene view)
    var paintPath by mutableStateOf<String?>(null)     // path of the TileMap being painted, null = not painting
    var tileBrush by mutableIntStateOf(1)              // tile number the paint and fill tools use
    var tileTool by mutableStateOf("paint")            // paint | erase | fill
    fun paintNode(): SceneNode? { rev; val t = editor?.tree ?: return null; val p = paintPath ?: return null; val n = t.find(t.root ?: return null, p) ?: return null; return if (n.type == "TileMap") n else null }
    fun beginTileStroke() { editor?.beginDrag() }       // one undo step per finger stroke
    fun paintTile(n: SceneNode, wx: Double, wy: Double): Boolean {
        val (c, r) = TileGrid.cellAt(n, wx, wy) ?: return false
        val changed = TileGrid.apply(n, tileTool, c, r, tileBrush)
        if (changed) rev++
        return changed
    }
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
    var showFps by mutableStateOf(false)               // FPS counter over the game while testing in the editor
    var snap by mutableIntStateOf(8)
    val console = mutableStateListOf<LogLine>()
    var building by mutableStateOf(false)
    var status by mutableStateOf("")
    var game by mutableStateOf<GameSession?>(null)
    var images by mutableStateOf<ImageCache?>(null)

    // ---- files panel
    var filesFolder by mutableStateOf<String?>(null)    // folder chosen in the Files panel: where imports and new files go
    val collapsedFolders = mutableStateListOf<String>()

    // ---- assets and the animation manager
    var animDoc by mutableStateOf<AnimDoc?>(null)
    var sprite by mutableStateOf<SpriteSession?>(null)   // the picture open in the sprite editor
    private var preview: Sounds? = null

    fun previewSound(path: String) {
        val p = project ?: return
        val s = preview ?: Sounds(ctx, DiskIO(p)).also { preview = it }
        s.play(path); info("Playing $path")
    }

    /** Tapping an asset in the Files panel: sounds play, animation files open in the manager. */
    fun openAsset(path: String) {
        val l = path.lowercase()
        if (nebula.core.Markdown.isMarkdown(l)) openScript(path)        // Markdown opens in the code editor
        else if (l.endsWith(".wav") || l.endsWith(".ogg") || l.endsWith(".mp3")) previewSound(path)
        else if (listOf(".png", ".jpg", ".jpeg", ".webp").any { l.endsWith(it) }) openSprite(path)
    }

    // ------------------------------------------------------------------ sprite editor
    private fun isImage(path: String) = listOf(".png", ".jpg", ".jpeg", ".webp").any { path.lowercase().endsWith(it) }

    /** Opens an image of the project in the sprite editor. A sheet that an animation set describes opens as its frames. */
    fun openSprite(path: String) {
        val p = project ?: return
        closeSprite()
        try {
            val bytes = p.file(path).takeIf { it.isFile }?.readBytes() ?: throw java.io.IOException("file not found")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw java.io.IOException("not a picture Android can read")
            if (bmp.width > 2048 || bmp.height > 2048) throw java.io.IOException("the picture is bigger than 2048 x 2048")
            val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val grid = p.animations().mapNotNull { a -> p.readText(a)?.let { AnimSet.parse(it) } }.firstOrNull { it.sheet == path }
            val cols = grid?.cols ?: 1; val rows = grid?.rows ?: 1
            val doc = SpriteDoc.fromSheet(px, bmp.width, bmp.height, cols, rows)
            val usable = doc.frameCount == cols * rows
            // jpg and webp are saved next to the original as a png, so the original is never overwritten with a different format
            val target = if (path.lowercase().endsWith(".png")) path else path.substringBeforeLast('.') + ".png"
            val s = SpriteSession(target, doc, if (usable) cols else 1, if (usable) rows else 1)
            s.savedVersion = if (target == path) doc.version else -1
            sprite = s; modal = Modal.Sprite
        } catch (e: Exception) { error("Cannot open $path in the sprite editor: ${e.message}") }
    }

    /** Creates a blank png (one frame) in the assets folder and opens it in the sprite editor. */
    fun newSprite(name: String, w: Int, h: Int) {
        val p = project ?: return
        val clean = name.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { return }
        val path = "${under("assets")}/$clean.png"
        if (p.file(path).exists()) { error("'$path' already exists"); return }
        closeSprite()
        try { sprite = SpriteSession(path, SpriteDoc.blank(w.coerceIn(1, 1024), h.coerceIn(1, 1024)), 1, 1); modal = Modal.Sprite; saveSprite() }
        catch (e: Exception) { error("Cannot create the sprite: ${e.message}") }
    }

    /** Writes the picture as a png sheet (frames side by side) and keeps animation sets that use the sheet in step. */
    fun saveSprite() {
        val p = project ?: return; val s = sprite ?: return
        if (!s.dirty) return
        try {
            val (cols, rows) = s.layout()
            val (px, w, h) = s.doc.toSheet(cols)
            val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            val f = p.file(s.path); f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            s.cols = cols; s.rows = rows; s.savedVersion = s.doc.version
            syncAnimations(s.path, cols, rows, s.doc.frameCount)
            images?.clear(); rev++
        } catch (e: Exception) { error("Cannot save ${s.path}: ${e.message}") }
    }

    private fun syncAnimations(sheet: String, cols: Int, rows: Int, frames: Int) {
        val p = project ?: return
        var used = false
        for (a in p.animations()) {
            val set = p.readText(a)?.let { AnimSet.parse(it) } ?: continue
            if (set.sheet != sheet) continue
            used = true
            if (set.cols != cols || set.rows != rows) { set.cols = cols; set.rows = rows; p.file(a).writeText(set.toText()) }
        }
        if (!used && frames > 1) {                      // a new animated sprite gets an animation set with one clip that plays every frame
            val base = AnimSet.cleanName(File(sheet).nameWithoutExtension).ifEmpty { "anim" }
            var name = base; var i = 2
            while (p.file("animations/$name.nanim").exists()) name = base + "_" + i++
            val set = AnimSet(sheet, cols, rows, arrayListOf(Clip("all", 1, frames, 8.0, true)))
            p.file("animations/$name.nanim").also { it.parentFile?.mkdirs() }.writeText(set.toText())
            info("Made animations/$name.nanim for the new frames (clip 'all').")
        }
    }

    fun closeSprite() {
        saveSprite(); sprite = null
        if (modal is Modal.Sprite) modal = null
    }

    fun openAnimation(path: String) {
        val p = project ?: return
        animDoc = AnimDoc(path, AnimSet.parse(p.readText(path) ?: "")) { saveAnim(it) }
        modal = Modal.Animation
    }

    private fun saveAnim(d: AnimDoc) {
        val p = project ?: return
        try { p.file(d.path).writeText(d.set.toText()); images?.anims?.clear(); rev++ }
        catch (e: Exception) { error("Cannot save ${d.path}: ${e.message}") }
    }

    fun closeAnimation() { modal = null; animDoc = null }

    fun newAnimation(name: String) {
        val p = project ?: return
        val clean = AnimSet.cleanName(name).ifEmpty { return }
        val path = "${under("animations")}/$clean.nanim"
        if (p.file(path).exists()) { error("animation set '$clean' already exists"); return }
        p.file(path).parentFile?.mkdirs(); p.file(path).writeText(AnimSet().toText())
        rev++; openAnimation(path)
    }

    /** Lets the user pick the sprite sheet image for the open animation set, then returns to the manager. */
    fun pickSheet() {
        val p = project ?: return; val d = animDoc ?: return
        val imgs = p.assets().filter { f -> listOf(".png", ".jpg", ".jpeg", ".webp").any { f.lowercase().endsWith(it) } }
        modal = Modal.Pick("Sprite sheet image", imgs) { d.edit(sheet = it); modal = Modal.Animation }
    }

    private fun under(root: String): String = filesFolder?.takeIf { it == root || it.startsWith("$root/") } ?: root

    var importHook: (() -> Unit)? = null
    fun importAsset() { importHook?.invoke() }

    /** Copies a file the user picked into the project's assets folder. */
    fun importUri(uri: Uri) {
        val p = project ?: return
        try {
            var name = "asset"
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) } }
            val dest = File(p.dir, under("assets") + "/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_")); dest.parentFile?.mkdirs()
            ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            images?.clear(); rev++; info("Imported ${dest.relativeTo(p.dir).path}")
        } catch (e: Exception) { error("Import failed: ${e.message}") }
    }

    // ---- opening a folder or a zip as a project: the files are copied into the projects folder, which is where the project is edited and saved from then on
    var openFolderHook: (() -> Unit)? = null
    var openZipHook: (() -> Unit)? = null
    fun openFolder() { openFolderHook?.invoke() }
    fun openZip() { openZipHook?.invoke() }
    private var importing = false

    private fun runImport(what: String, work: () -> Project) {
        if (importing) { info("Still opening the last one..."); return }
        importing = true; status = "Opening $what..."; info("Opening $what...")
        Thread {
            try {
                val p = work()
                post { importing = false; status = ""; openProject(p.dir); info("Saved to ${p.dir.absolutePath}; your changes are saved there automatically.") }
            } catch (e: Exception) { post { importing = false; status = ""; error("Cannot open the $what: ${e.message ?: e.javaClass.simpleName}") } }
        }.start()
    }

    /** A public GitHub repository (address typed by the user) is downloaded as a copy into the projects folder and opened. No sign-in is used. */
    fun importGitHub(url: String) {
        runImport("repository") { nebula.core.GitHubImport.import(url, root, ctx.cacheDir) }
    }

    private fun displayName(uri: Uri): String? {
        var name: String? = null
        try { ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) } } catch (e: Exception) { }
        return name
    }

    /** A zip the user picked becomes a project in the projects folder. */
    fun openZipUri(uri: Uri) {
        runImport("zip") {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("the file cannot be read")
            input.use { ProjectImport.importZip(it, root, displayName(uri) ?: "Imported", ctx.cacheDir) }
        }
    }

    /** A folder the user picked (system folder picker) becomes a project in the projects folder. */
    fun openFolderUri(tree: Uri) {
        runImport("folder") {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val name = displayName(DocumentsContract.buildDocumentUriUsingTree(tree, rootId)) ?: "Imported"
            val stage = File(ctx.cacheDir, "import-${System.nanoTime()}")
            try {
                copyDocumentTree(tree, rootId, stage, 0)
                ProjectImport.adopt(stage, root, name, move = true)
            } finally { stage.deleteRecursively() }
        }
    }

    private fun copyDocumentTree(tree: Uri, docId: String, to: File, depth: Int) {
        if (depth > 40) throw java.io.IOException("the folder is nested too deeply")
        to.mkdirs()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val entries = ArrayList<Triple<String, String, String>>()
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c -> while (c.moveToNext()) entries.add(Triple(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "")) }
        for ((id, rawName, mime) in entries) {
            val name = rawName.replace('/', '_')                         // a name from another app never gets to climb out of the folder
            if (name.isEmpty() || name == "." || name == ".." || ProjectImport.isJunk(name)) continue
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) copyDocumentTree(tree, id, File(to, name), depth + 1)
            else ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { i -> File(to, name).outputStream().use { i.copyTo(it) } }
        }
    }

    private var libCounter = 0
    private val settingsFile = File(ctx.filesDir, "settings.properties")
    private val settings = Properties()

    /** Autosave: every few seconds, scene and script edits are written to the project folder (no need to press save). */
    private val autosave = object : Runnable {
        override fun run() {
            try {
                if (sprite != null) { if (modal is Modal.Sprite) saveSprite() else closeSprite() }
                val p = project
                if (p != null && game == null) {
                    saveScene()
                    for (d in scripts) if (d.dirty) try { p.file(d.path).writeText(d.value.text); d.saved = d.value.text } catch (e: Exception) { error("Cannot save ${d.path}: ${e.message}") }
                }
            } finally { main.postDelayed(this, 4000) }
        }
    }

    init {
        if (settingsFile.exists()) settingsFile.inputStream().use { settings.load(it) }
        snap = settings.getProperty("snap", "8").toIntOrNull() ?: 8
        showGrid = settings.getProperty("grid", "true") == "true"
        showFps = settings.getProperty("fps", "false") == "true"
        installApi()
        main.postDelayed(autosave, 4000)
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

    fun toggleFps() { showFps = !showFps; setSetting("fps", showFps.toString()) }

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
    fun createProject(name: String, pkg: String, preset: Preset = Preset.PLATFORMER) {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9_ -]"), "").ifEmpty { "MyGame" }
        val dir = File(root, clean)
        if (dir.exists()) { error("A project called '$clean' already exists."); return }
        val p = Project.create(dir, clean, pkg.ifBlank { "com.nebula." + clean.lowercase().replace(Regex("[^a-z0-9]"), "") }, preset)
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
        stop(); saveAll(); closeSprite()
        preview?.release(); preview = null; animDoc = null
        project = null; editor = null; scenePath = null; scripts.clear(); activeScript = null; selectedPath = null; lowered = null; paintPath = null
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
        val path = "${under("scenes")}/$clean.nscn"
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
    fun copySelected() { val n = selected() ?: return; nodeClipboard = editor?.exportNode(n); info("Copied '${n.name}'") }
    fun cutSelected() {
        val n = selected() ?: return
        if (n === editor?.tree?.root) { info("The root node cannot be cut."); return }
        nodeClipboard = editor?.exportNode(n); deleteSelected(); info("Cut '${n.name}'")
    }
    /** Pastes the copied node as the last child of the selected node (or of the root when nothing is selected). */
    fun pasteNode() {
        val text = nodeClipboard ?: return
        val parent = selected() ?: editor?.tree?.root ?: return
        val made = change { it.paste(parent, text) }
        if (made != null) select(made) else info("Nothing to paste.")
    }
    /** Deletes the selected node straight away (the Delete key); undo brings it back. */
    fun deleteSelectedNow() { if (selected() != null) deleteSelected() }
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
            val text = p.readText(path) ?: run { error("File $path not found"); return }
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

    fun newFolder(parent: String, name: String) {
        val p = project ?: return
        try {
            val rel = p.createFolder(parent, name)
            collapsedFolders.remove(parent); filesFolder = rel; rev++; info("Created $rel/")
        } catch (e: IllegalArgumentException) { error("Folder not created: ${e.message}") }
    }

    /** Deletes a folder and everything in it (scripts are detached from their nodes). The roots themselves stay. */
    fun deleteFolder(rel: String) {
        val p = project ?: return
        if (!p.isManaged(rel) || rel in Project.ROOTS) return
        val prefix = "$rel/"
        if (p.mainScene.startsWith(prefix)) { error("The main scene is inside $rel. Pick another main scene in Settings first."); return }
        val inside = p.file(rel).walkTopDown().filter { it.isFile }.map { it.relativeTo(p.dir).path.replace('\\', '/') }.toList()
        val sceneInside = scenePath?.startsWith(prefix) == true
        for (f in inside) deleteFile(f)
        p.file(rel).deleteRecursively()
        if (sceneInside) editor?.markSaved()      // after the loop: detaching scripts dirties the open scene, which must not be written back
        collapsedFolders.removeAll { it == rel || it.startsWith(prefix) }
        if (filesFolder == rel || filesFolder?.startsWith(prefix) == true) filesFolder = null
        if (sceneInside) openScene(p.mainScene)
        rev++; info("Deleted $rel/")
    }

    /** New script file for the selected node: `script Name of <node type>:` with the usual hooks. */
    fun newScriptForSelected() {
        val p = project ?: return; val n = selected() ?: run { info("Select a node first."); return }
        val cls = n.name.replace(Regex("[^A-Za-z0-9_]"), "").replaceFirstChar { it.uppercase() }.ifEmpty { "Script" }
        var name = cls; var i = 2
        while (p.scripts().any { it.endsWith("/" + fileName(name)) }) name = cls + i++
        val path = "${under("scripts")}/${fileName(name)}"
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
        lowered?.let { nebula.core.Bars.register(it.metas) }      // the viewport shows DynamicBars from their script's `empty` / `full` lines
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
        val engine = Engine({ io.readText(it) }, metas, host, { info(it) }, { remap?.remap(it) ?: it }, { sounds.play(it) }, { sounds.control(it) })
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
    fun export(pkg: String, label: String, install: Boolean, toDownloads: Boolean = false) {
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
                    var saved: String? = null; var saveError: String? = null
                    if (toDownloads) try { saved = Downloads.saveApk(ctx, f) } catch (e: Exception) { saveError = e.message }
                    post {
                        building = false; status = "Exported"; info("Exported ${f.absolutePath} (${f.length() / 1024} KB)")
                        if (saved != null) info("Copied to $saved")
                        if (saveError != null) error("Could not copy to Downloads: $saveError")
                        if (install) Installer.install(ctx, f) { info(it) }
                    }
                } catch (e: Throwable) { post { building = false; status = "Export failed"; error("Export failed: ${e.message}") } }
            }.start()
        }
    }
}
