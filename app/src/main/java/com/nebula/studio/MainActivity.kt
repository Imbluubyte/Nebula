package com.nebula.studio

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nebula.studio.ui.NebulaTheme
import com.nebula.studio.ui.StudioScreen

class MainActivity : ComponentActivity() {
    private var studio: Studio? = null

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) studio?.importUri(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // An exported game carries its project in assets/project: then this app is just the player.
        val isGame = try { assets.list("project")?.contains("project.nebula") == true } catch (e: Exception) { false }
        if (isGame) { startPlayer(); return }

        val s = Studio(this); studio = s
        s.importHook = { picker.launch("*/*") }
        setContent {
            NebulaTheme {
                BackHandler(enabled = s.modal != null || s.game != null) { if (s.modal != null) s.modal = null else s.stop() }
                androidx.compose.foundation.layout.Box(Modifier.onPreviewKeyEvent { Commands.handleKey(s, it) }) { StudioScreen(s) }
            }
        }
    }

    override fun onPause() { super.onPause(); studio?.saveAll() }

    private fun startPlayer() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val player = com.nebula.studio.runtime.Player(this)
        requestedOrientation = if (player.landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        setContent { NebulaTheme { player.Content() } }
    }
}
