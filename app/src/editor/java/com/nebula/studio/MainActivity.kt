package com.nebula.studio

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.nebula.studio.ui.NebulaTheme
import com.nebula.studio.ui.StudioScreen

/** The editor. Exported games use the player build instead (src/player), which has none of this. */
class MainActivity : ComponentActivity() {
    private var studio: Studio? = null

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) studio?.importUri(uri) }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) studio?.openFolderUri(uri) }
    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) studio?.openZipUri(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val s = Studio(this); studio = s
        s.importHook = { picker.launch("*/*") }
        s.openFolderHook = { folderPicker.launch(null) }
        s.openZipHook = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        setContent {
            NebulaTheme {
                BackHandler(enabled = s.modal != null || s.game != null) { if (s.modal is Modal.Sprite) s.closeSprite() else if (s.modal != null) s.modal = null else s.stop() }
                androidx.compose.foundation.layout.Box(Modifier.onPreviewKeyEvent { Commands.handleKey(s, it) }) { StudioScreen(s) }
            }
        }
    }

    override fun onPause() { super.onPause(); studio?.saveAll() }
}
