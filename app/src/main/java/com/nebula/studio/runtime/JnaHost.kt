package com.nebula.studio.runtime

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import nebula.core.Engine
import nebula.core.ScriptHost

/** The functions snc generates for libscripts.so (see api/nebula.sn and the generated entry points). */
interface ScriptsLib : Library {
    fun sn_init(): Int
    fun sn_last_error(): String?
    fun sn_pump(): Int
    fun sn_nbAttach(id: Long, script: String)
    fun sn_nbReady(id: Long)
    fun sn_nbFrame(id: Long, delta: Double)
    fun sn_nbTick(id: Long, rate: Long, delta: Double)
    fun sn_nbEvery(id: Long, ms: Long, delta: Double)
    fun sn_nbFire(id: Long, name: String, a: Double, b: Double, other: Long)
    fun sn_nbFreed(id: Long)

    fun sn_set_extern_nb_log(cb: Callback)
    fun sn_set_extern_nb_find(cb: Callback)
    fun sn_set_extern_nb_parent(cb: Callback)
    fun sn_set_extern_nb_type(cb: Callback)
    fun sn_set_extern_nb_child_count(cb: Callback)
    fun sn_set_extern_nb_child_at(cb: Callback)
    fun sn_set_extern_nb_add(cb: Callback)
    fun sn_set_extern_nb_spawn(cb: Callback)
    fun sn_set_extern_nb_free(cb: Callback)
    fun sn_set_extern_nb_has(cb: Callback)
    fun sn_set_extern_nb_alive(cb: Callback)
    fun sn_set_extern_nb_get_f(cb: Callback)
    fun sn_set_extern_nb_get_s(cb: Callback)
    fun sn_set_extern_nb_get_b(cb: Callback)
    fun sn_set_extern_nb_set_f(cb: Callback)
    fun sn_set_extern_nb_set_s(cb: Callback)
    fun sn_set_extern_nb_set_b(cb: Callback)
    fun sn_set_extern_nb_tag(cb: Callback)
    fun sn_set_extern_nb_has_tag(cb: Callback)
    fun sn_set_extern_nb_tagged_count(cb: Callback)
    fun sn_set_extern_nb_tagged_at(cb: Callback)
    fun sn_set_extern_nb_down(cb: Callback)
    fun sn_set_extern_nb_axis(cb: Callback)
    fun sn_set_extern_nb_touch_count(cb: Callback)
    fun sn_set_extern_nb_touch_x(cb: Callback)
    fun sn_set_extern_nb_touch_y(cb: Callback)
    fun sn_set_extern_nb_screen_w(cb: Callback)
    fun sn_set_extern_nb_screen_h(cb: Callback)
    fun sn_set_extern_nb_sound(cb: Callback)
    fun sn_set_extern_nb_goto(cb: Callback)
    fun sn_set_extern_nb_store(cb: Callback)
    fun sn_set_extern_nb_fetch(cb: Callback)
}

// One callback interface per C signature (JNA needs exactly one `invoke`).
interface CbS : Callback { fun invoke(a: String) }
interface CbLS_L : Callback { fun invoke(a: Long, b: String): Long }
interface CbL_L : Callback { fun invoke(a: Long): Long }
interface CbL_P : Callback { fun invoke(a: Long): Pointer }
interface CbLL_L : Callback { fun invoke(a: Long, b: Long): Long }
interface CbLSS_L : Callback { fun invoke(a: Long, b: String, c: String): Long }
interface CbSL_L : Callback { fun invoke(a: String, b: Long): Long }
interface CbL : Callback { fun invoke(a: Long) }
interface CbL_I : Callback { fun invoke(a: Long): Int }
interface CbLS_I : Callback { fun invoke(a: Long, b: String): Int }
interface CbLS_D : Callback { fun invoke(a: Long, b: String): Double }
interface CbLS_P : Callback { fun invoke(a: Long, b: String): Pointer }
interface CbLSD : Callback { fun invoke(a: Long, b: String, c: Double) }
interface CbLSS : Callback { fun invoke(a: Long, b: String, c: String) }
interface CbLSI : Callback { fun invoke(a: Long, b: String, c: Int) }
interface CbS_L : Callback { fun invoke(a: String): Long }
interface CbS_I : Callback { fun invoke(a: String): Int }
interface CbSS_D : Callback { fun invoke(a: String, b: String): Double }
interface CbV_L : Callback { fun invoke(): Long }
interface CbL_D : Callback { fun invoke(a: Long): Double }
interface CbV_D : Callback { fun invoke(): Double }
interface CbSS : Callback { fun invoke(a: String, b: String) }
interface CbS_P : Callback { fun invoke(a: String): Pointer }

/** Runs libscripts.so for an [Engine]: implements every `extern fn` of nebula.sn and forwards engine events in. */
class JnaScriptHost(path: String, private val saver: SaveStore) : ScriptHost {
    lateinit var engine: Engine
    private val lib: ScriptsLib
    private val keep = ArrayList<Any>()          // callbacks must stay referenced or JNA frees them
    private val ring = arrayOfNulls<Memory>(256) // strings handed to native code live a while
    private var ringPos = 0
    private var initError: String? = null

    init {
        System.setProperty("jna.encoding", "UTF-8")
        lib = if (path.contains('/')) Native.load(path, ScriptsLib::class.java) else Native.load(path, ScriptsLib::class.java)
    }

    private fun cstr(s: String): Pointer {
        val b = s.toByteArray(Charsets.UTF_8)
        val m = Memory((b.size + 1).toLong()); m.write(0, b, 0, b.size); m.setByte(b.size.toLong(), 0)
        ring[ringPos] = m; ringPos = (ringPos + 1) % ring.size
        return m
    }
    private fun bi(b: Boolean) = if (b) 1 else 0

    /** Registers the externs and runs the script's top-level code. Returns an error text or null. */
    fun start(): String? {
        val e = engine
        fun <T : Callback> reg(cb: T, set: (Callback) -> Unit) { keep.add(cb); set(cb) }
        reg(object : CbS { override fun invoke(a: String) { e.log(a) } }, lib::sn_set_extern_nb_log)
        reg(object : CbLS_L { override fun invoke(a: Long, b: String) = e.apiFind(a, b) }, lib::sn_set_extern_nb_find)
        reg(object : CbL_L { override fun invoke(a: Long) = e.apiParent(a) }, lib::sn_set_extern_nb_parent)
        reg(object : CbL_P { override fun invoke(a: Long) = cstr(e.apiType(a)) }, lib::sn_set_extern_nb_type)
        reg(object : CbL_L { override fun invoke(a: Long) = e.apiChildCount(a) }, lib::sn_set_extern_nb_child_count)
        reg(object : CbLL_L { override fun invoke(a: Long, b: Long) = e.apiChildAt(a, b) }, lib::sn_set_extern_nb_child_at)
        reg(object : CbLSS_L { override fun invoke(a: Long, b: String, c: String) = e.apiAdd(a, b, c) }, lib::sn_set_extern_nb_add)
        reg(object : CbSL_L { override fun invoke(a: String, b: Long) = e.apiSpawn(a, b) }, lib::sn_set_extern_nb_spawn)
        reg(object : CbL { override fun invoke(a: Long) { e.apiFree(a) } }, lib::sn_set_extern_nb_free)
        reg(object : CbLS_I { override fun invoke(a: Long, b: String) = bi(e.apiHas(a, b)) }, lib::sn_set_extern_nb_has)
        reg(object : CbL_I { override fun invoke(a: Long) = bi(e.apiAlive(a)) }, lib::sn_set_extern_nb_alive)
        reg(object : CbLS_D { override fun invoke(a: Long, b: String) = e.apiGetF(a, b) }, lib::sn_set_extern_nb_get_f)
        reg(object : CbLS_P { override fun invoke(a: Long, b: String) = cstr(e.apiGetS(a, b)) }, lib::sn_set_extern_nb_get_s)
        reg(object : CbLS_I { override fun invoke(a: Long, b: String) = bi(e.apiGetB(a, b)) }, lib::sn_set_extern_nb_get_b)
        reg(object : CbLSD { override fun invoke(a: Long, b: String, c: Double) { e.apiSet(a, b, c) } }, lib::sn_set_extern_nb_set_f)
        reg(object : CbLSS { override fun invoke(a: Long, b: String, c: String) { e.apiSet(a, b, c) } }, lib::sn_set_extern_nb_set_s)
        reg(object : CbLSI { override fun invoke(a: Long, b: String, c: Int) { e.apiSet(a, b, c != 0) } }, lib::sn_set_extern_nb_set_b)
        reg(object : CbLSI { override fun invoke(a: Long, b: String, c: Int) { e.apiTag(a, b, c != 0) } }, lib::sn_set_extern_nb_tag)
        reg(object : CbLS_I { override fun invoke(a: Long, b: String) = bi(e.apiHasTag(a, b)) }, lib::sn_set_extern_nb_has_tag)
        reg(object : CbS_L { override fun invoke(a: String) = e.apiTaggedCount(a) }, lib::sn_set_extern_nb_tagged_count)
        reg(object : CbSL_L { override fun invoke(a: String, b: Long) = e.apiTaggedAt(a, b) }, lib::sn_set_extern_nb_tagged_at)
        reg(object : CbS_I { override fun invoke(a: String) = bi(e.apiDown(a)) }, lib::sn_set_extern_nb_down)
        reg(object : CbSS_D { override fun invoke(a: String, b: String) = e.apiAxis(a, b) }, lib::sn_set_extern_nb_axis)
        reg(object : CbV_L { override fun invoke() = e.apiTouchCount() }, lib::sn_set_extern_nb_touch_count)
        reg(object : CbL_D { override fun invoke(a: Long) = e.apiTouchX(a) }, lib::sn_set_extern_nb_touch_x)
        reg(object : CbL_D { override fun invoke(a: Long) = e.apiTouchY(a) }, lib::sn_set_extern_nb_touch_y)
        reg(object : CbV_D { override fun invoke() = e.screenW }, lib::sn_set_extern_nb_screen_w)
        reg(object : CbV_D { override fun invoke() = e.screenH }, lib::sn_set_extern_nb_screen_h)
        reg(object : CbS { override fun invoke(a: String) { e.playSound(a) } }, lib::sn_set_extern_nb_sound)
        reg(object : CbS { override fun invoke(a: String) { e.apiGoto(a) } }, lib::sn_set_extern_nb_goto)
        reg(object : CbSS { override fun invoke(a: String, b: String) { saver.put(a, b) } }, lib::sn_set_extern_nb_store)
        reg(object : CbS_P { override fun invoke(a: String) = cstr(saver.get(a)) }, lib::sn_set_extern_nb_fetch)
        return if (lib.sn_init() == 1) null else (lib.sn_last_error() ?: "script initialisation failed")
    }

    private fun err() { initError = lib.sn_last_error()?.takeIf { it.isNotEmpty() } }
    override fun attach(id: Long, script: String) { lib.sn_nbAttach(id, script); err() }
    override fun ready(id: Long) { lib.sn_nbReady(id); err() }
    override fun frame(id: Long, delta: Double) { lib.sn_nbFrame(id, delta); err() }
    override fun tick(id: Long, rate: Int, delta: Double) { lib.sn_nbTick(id, rate.toLong(), delta); err() }
    override fun every(id: Long, ms: Int, delta: Double) { lib.sn_nbEvery(id, ms.toLong(), delta); err() }
    override fun fire(id: Long, name: String, a: Double, b: Double, other: Long) { lib.sn_nbFire(id, name, a, b, other); err() }
    override fun freed(id: Long) { lib.sn_nbFreed(id); err() }
    override fun pump() { if (lib.sn_pump() < 0) err() }
    override fun takeError(): String? { val e = initError; initError = null; return e }
}

/** Key/value storage behind `save(...)` / `load(...)` in scripts. */
interface SaveStore { fun put(key: String, value: String); fun get(key: String): String }
