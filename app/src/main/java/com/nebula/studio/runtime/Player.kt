package com.nebula.studio.runtime

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import nebula.core.Engine
import nebula.lang.MetaFile
import java.util.Properties

/** Runs an exported game: the project is in assets/project, scripts are libscripts.so inside the APK. */
class Player(ctx: Context) {
    private val io = ApkIO(ctx.assets)
    private val config = Properties().also { p -> io.readBytes("project.nebula")?.inputStream()?.use { p.load(it) } }
    val landscape = config.getProperty("orientation", "landscape") == "landscape"
    private val gameW = config.getProperty("width", "1280").toInt()
    private val gameH = config.getProperty("height", "720").toInt()
    private val images = ImageCache(io)
    private val sounds = Sounds(ctx, io)
    private var error: String? = null
    private var engine: Engine? = null

    init {
        try {
            val metas = io.readText("scripts.meta")?.let { MetaFile.read(it) } ?: emptyMap()
            var host: JnaScriptHost? = null
            if (metas.isNotEmpty()) host = JnaScriptHost("scripts", PrefsSaveStore(ctx, config.getProperty("title", "game")))
            val e = Engine({ io.readText(it) }, metas, host, { android.util.Log.i("Nebula", it) }, { it }, { sounds.play(it) }, { sounds.control(it) })
            e.screenW = gameW.toDouble(); e.screenH = gameH.toDouble()
            if (host != null) { host.engine = e; host.start()?.let { error = "script init: $it" } }
            if (error == null && !e.loadScene(config.getProperty("scene", "scenes/main.nscn"))) error = "cannot load the main scene"
            engine = e
        } catch (t: Throwable) { error = t.toString() }
    }

    @Composable
    fun Content() {
        val e = engine
        if (e != null && error == null) GameView(e, images, gameW, gameH, Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize()) { BasicText(error ?: "Game failed to start", style = TextStyle(color = Color.White)) }
    }
}
