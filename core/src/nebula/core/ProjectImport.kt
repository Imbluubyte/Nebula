package nebula.core

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Brings a project from somewhere else (a folder the user picked, or a zip) into the projects folder, so it can be opened and
 * edited like any other project: from then on every save goes to that copy ("the project folder").
 * Android-free; the app only hands over the files (see Studio.openFolderUri / openZipUri).
 */
object ProjectImport {
    class ImportException(message: String) : Exception(message)

    private const val MAX_ENTRIES = 50_000
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    /** Never copied: version control data and what zip tools of other systems add. */
    private val skipNames = setOf(".git", "__MACOSX", ".DS_Store", "Thumbs.db")
    fun isJunk(name: String) = name in skipNames

    /** Unzips [input] into [into]. Entries that would land outside it are refused; huge archives are refused too. */
    fun unzip(input: InputStream, into: File) {
        into.mkdirs()
        val base = into.canonicalFile
        var entries = 0; var bytes = 0L
        ZipInputStream(input.buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val name = e.name.replace('\\', '/')
                if (name.split('/').any { it in skipNames }) continue
                if (++entries > MAX_ENTRIES) throw ImportException("the zip has too many files")
                val out = File(base, name).canonicalFile
                if (out != base && !out.path.startsWith(base.path + File.separator)) throw ImportException("the zip has a file outside its folder: ${e.name}")
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zin.read(buf); if (n < 0) break
                        bytes += n; if (bytes > MAX_BYTES) throw ImportException("the zip is too big to open")
                        o.write(buf, 0, n)
                    }
                }
            }
        }
        if (entries == 0) throw ImportException("the zip is empty")
    }

    private fun subfolders(d: File): List<File> = d.listFiles { f -> f.isDirectory && f.name !in skipNames }?.sortedBy { it.name.lowercase() } ?: emptyList()

    /**
     * The project folder inside [dir]: the nearest folder (up to 3 levels down, so a zip that wraps everything in one folder works)
     * that has a project.nebula, else the nearest one that has scenes/ or scripts/, else [dir] itself (or the one folder it only wraps).
     */
    fun findRoot(dir: File): File {
        fun search(match: (File) -> Boolean): File? {
            var level = listOf(dir)
            repeat(4) {
                level.firstOrNull(match)?.let { return it }
                level = level.flatMap { subfolders(it) }
            }
            return null
        }
        return search { File(it, "project.nebula").isFile } ?: search { File(it, "scenes").isDirectory || File(it, "scripts").isDirectory } ?: unwrap(dir)
    }

    /** A folder that holds nothing but one folder (what GitHub's zip of a repository looks like) is that inner folder. */
    private fun unwrap(dir: File): File {
        var d = dir
        repeat(4) {
            val all = d.listFiles { f -> f.name !in skipNames }.orEmpty()
            if (all.size == 1 && all[0].isDirectory) d = all[0] else return d
        }
        return d
    }

    private fun cleanName(raw: String): String = raw.trim().replace(Regex("[^A-Za-z0-9_ -]"), "").trim().ifEmpty { "Imported" }

    private fun uniqueDir(root: File, name: String): File {
        var d = File(root, name); var i = 2
        while (d.exists()) { d = File(root, "$name $i"); i++ }
        return d
    }

    private fun copyTree(from: File, to: File, top: Boolean) {
        to.mkdirs()
        for (f in from.listFiles() ?: return) {
            if (f.name in skipNames) continue
            if (top && f.name == "build") continue                  // a build made somewhere else is not valid here: build again
            if (f.isDirectory) copyTree(f, File(to, f.name), false) else f.copyTo(File(to, f.name), overwrite = true)
        }
    }

    /**
     * Makes [src] a project inside [root] (a new folder, named after the project's title or [nameHint], numbered if taken) and returns it.
     * Missing parts are filled in: project.nebula, the scenes / scripts / assets / animations folders and a main scene that exists.
     * With [move] the files are moved instead of copied when possible (use it for a folder you made yourself, like an unzipped archive).
     */
    fun adopt(src: File, root: File, nameHint: String, move: Boolean = false): Project {
        val found = findRoot(src)
        val title = File(found, "project.nebula").takeIf { it.isFile }?.let { Project(found).config.getProperty("title") }
        val dest = uniqueDir(root.also { it.mkdirs() }, cleanName(title ?: nameHint))
        var moved = false
        if (move) moved = found.renameTo(dest)
        if (!moved) { copyTree(found, dest, true) } else File(dest, "build").deleteRecursively()
        val p = Project(dest)
        if (!File(dest, "project.nebula").isFile) { p.title = dest.name; p.packageId = "com.nebula." + dest.name.lowercase().replace(Regex("[^a-z0-9]"), "").ifEmpty { "game" } }
        for (r in Project.ROOTS) File(dest, r).mkdirs()
        if (!p.file(p.mainScene).isFile) {
            val scenes = p.scenes()
            (scenes.firstOrNull { it.endsWith("/main.nscn") } ?: scenes.firstOrNull())?.let { p.mainScene = it }
        }
        p.save()
        return p
    }

    /** Opens a folder as a project: copies it into [root]. A folder that is already a project inside [root] is returned as it is. */
    fun importFolder(src: File, root: File, nameHint: String = src.name): Project {
        if (!src.isDirectory) throw ImportException("'${src.name}' is not a folder")
        if (src.canonicalFile.parentFile == root.canonicalFile && File(src, "project.nebula").isFile) return Project(src)
        return adopt(src, root, nameHint)
    }

    /** Opens a zip as a project: unpacks it (through a scratch folder under [scratch]) and moves the result into [root]. */
    fun importZip(input: InputStream, root: File, nameHint: String, scratch: File): Project {
        val stage = File(scratch, "import-${System.nanoTime()}")
        try {
            unzip(input, stage)
            return adopt(stage, root, nameHint.removeSuffix(".zip").removeSuffix(".ZIP"), move = true)
        } finally { stage.deleteRecursively() }
    }
}
