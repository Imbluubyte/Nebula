package com.nebula.studio.runtime

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import nebula.core.AnimLibrary
import nebula.core.Project
import nebula.core.SoundCmd
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
    /** Sprite sheet animation sets (`.nanim`), read through the same project files. */
    val anims = AnimLibrary { io.readText(it) }
    fun clear() { map.clear(); anims.clear() }
}

/** Plays short sounds from project files. `Sound` nodes pass their volume, pitch and loop through [control]. */
class Sounds(private val ctx: Context, private val io: ProjectIO) {
    private val pool = SoundPool.Builder().setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()).build()
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()
    private class Req(val key: Long, val volume: Float, val rate: Float, val loop: Boolean)
    private val queued = HashMap<Int, MutableList<Req>>()    // sound id -> plays waiting for the file to finish loading
    private val streams = HashMap<Long, Int>()               // node id -> its latest stream (so it can be stopped)

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status == 0) { ready.add(id); queued.remove(id)?.forEach { start(id, it) } } else queued.remove(id)
        }
    }

    private fun load(path: String): Int? {
        ids[path]?.let { return it }
        val bytes = io.readBytes(path) ?: return null
        val f = File(ctx.cacheDir, "snd_" + path.replace('/', '_'))
        f.writeBytes(bytes)
        val id = pool.load(f.absolutePath, 1)
        ids[path] = id
        return id
    }

    private fun start(id: Int, r: Req) {
        val stream = pool.play(id, r.volume, r.volume, 1, if (r.loop) -1 else 0, r.rate)
        if (stream != 0 && r.key != 0L) streams[r.key] = stream
    }

    fun play(path: String, volume: Float = 1f, rate: Float = 1f, loop: Boolean = false, key: Long = 0L) {
        if (path.isEmpty()) return
        val id = load(path) ?: return
        val r = Req(key, volume, rate, loop)
        if (id in ready) start(id, r) else queued.getOrPut(id) { ArrayList() }.add(r)
    }

    fun control(c: SoundCmd) {
        if (c.stop) { streams.remove(c.key)?.let { pool.stop(it) }; return }
        if (c.loop) streams.remove(c.key)?.let { pool.stop(it) }     // a looping node restarts instead of stacking
        play(c.file, c.volume.toFloat(), c.pitch.toFloat(), c.loop, c.key)
    }

    fun release() = pool.release()
}

class PrefsSaveStore(ctx: Context, name: String) : SaveStore {
    private val prefs = ctx.getSharedPreferences("nebula_save_$name", Context.MODE_PRIVATE)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun get(key: String): String = prefs.getString(key, "") ?: ""
}
