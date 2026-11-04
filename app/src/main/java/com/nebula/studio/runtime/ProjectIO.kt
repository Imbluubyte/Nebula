package com.nebula.studio.runtime

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import nebula.core.Project
import java.io.File

/** Where a running project's files come from: a folder (studio) or the APK's assets (exported game). */
interface ProjectIO {
    fun readText(path: String): String?
    fun readBytes(path: String): ByteArray?
}

class DiskIO(private val project: Project) : ProjectIO {
    override fun readText(path: String) = project.readText(path)
    override fun readBytes(path: String): ByteArray? = project.file(path).takeIf { it.isFile }?.readBytes()
}

class ApkIO(private val assets: AssetManager) : ProjectIO {
    override fun readText(path: String) = readBytes(path)?.toString(Charsets.UTF_8)
    override fun readBytes(path: String): ByteArray? = try { assets.open("project/$path").use { it.readBytes() } } catch (e: Exception) { null }
}

/** Decoded images by project path (misses are remembered too, call [clear] after assets change). */
class ImageCache(private val io: ProjectIO) {
    private val map = HashMap<String, ImageBitmap?>()
    fun get(path: String): ImageBitmap? {
        if (path.isEmpty()) return null
        return map.getOrPut(path) {
            val bytes = io.readBytes(path) ?: return@getOrPut null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    fun clear() = map.clear()
}

/** Plays short sounds from project files. */
class Sounds(private val ctx: Context, private val io: ProjectIO) {
    private val pool = SoundPool.Builder().setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()).build()
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()
    private val queued = HashSet<Int>()

    init {
        pool.setOnLoadCompleteListener { p, id, status -> if (status == 0) { ready.add(id); if (queued.remove(id)) p.play(id, 1f, 1f, 1, 0, 1f) } }
    }

    fun play(path: String) {
        if (path.isEmpty()) return
        val id = ids.getOrPut(path) {
            val bytes = io.readBytes(path) ?: return
            val f = File(ctx.cacheDir, "snd_" + path.replace('/', '_'))
            f.writeBytes(bytes)
            pool.load(f.absolutePath, 1)
        }
        if (id in ready) pool.play(id, 1f, 1f, 1, 0, 1f) else queued.add(id)
    }

    fun release() = pool.release()
}

class PrefsSaveStore(ctx: Context, name: String) : SaveStore {
    private val prefs = ctx.getSharedPreferences("nebula_save_$name", Context.MODE_PRIVATE)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun get(key: String): String = prefs.getString(key, "") ?: ""
}
