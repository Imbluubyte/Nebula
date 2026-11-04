package com.nebula.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.studio.*
import com.nebula.studio.runtime.GameView

@Composable
fun StudioScreen(studio: Studio) {
    val project = studio.project
    Box(Modifier.fillMaxSize().background(C.bg).windowInsetsPadding(WindowInsets.systemBars)) {
        if (project == null) WelcomeScreen(studio) else {
            val wide = LocalConfiguration.current.screenWidthDp >= 900
            Column(Modifier.fillMaxSize()) {
                TopBar(studio)
                if (wide) WideLayout(studio, Modifier.weight(1f)) else CompactLayout(studio, Modifier.weight(1f))
                StatusBar(studio)
            }
        }
        ModalHost(studio)
    }
}

@Composable
private fun TopBar(studio: Studio) {
    val running = studio.game != null
    Row(Modifier.fillMaxWidth().height(52.dp).background(C.panel).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(studio.project?.title ?: "", color = C.text, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp).widthIn(max = 160.dp), maxLines = 1)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            IconBtn(Icons.Default.Undo, "Undo (Ctrl+Z)", { Commands.find("undo")!!.run(studio) })
            IconBtn(Icons.Default.Redo, "Redo (Ctrl+Shift+Z)", { Commands.find("redo")!!.run(studio) })
            IconBtn(Icons.Default.Save, "Save (Ctrl+S)", { studio.saveAll(); studio.info("Saved") })
            IconBtn(Icons.Default.Add, "Add node (Ctrl+N)", { studio.modal = Modal.NewNode })
            IconBtn(Icons.Default.Build, "Build scripts (Ctrl+B)", { studio.build() }, enabled = !studio.building)
            IconBtn(Icons.Default.Unarchive, "Export APK (Ctrl+E)", { studio.modal = Modal.Export }, enabled = !studio.building)
            IconBtn(Icons.Default.Settings, "Project settings", { studio.modal = Modal.Settings })
            IconBtn(Icons.Default.Search, "Command palette (Ctrl+Shift+P)", { studio.modal = Modal.Palette })
            IconBtn(Icons.Default.Info, "Help (F1)", { studio.modal = Modal.Help })
            IconBtn(Icons.Default.Close, "Close project", { studio.closeProject() })
        }
        if (studio.building) CircularProgressIndicator(Modifier.size(24.dp), color = C.accent2, strokeWidth = 2.dp)
        if (running) {
            Box(Modifier.height(40.dp).clickable { studio.stop() }.background(C.err, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Stop, "Stop", tint = androidx.compose.ui.graphics.Color.White); Text(" Stop", color = androidx.compose.ui.graphics.Color.White) }
            }
        } else {
            Box(Modifier.height(40.dp).clickable(enabled = !studio.building) { studio.play() }.background(C.accent, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PlayArrow, "Run", tint = androidx.compose.ui.graphics.Color.White); Text(" Run", color = androidx.compose.ui.graphics.Color.White) }
            }
        }
    }
}

@Composable
private fun StatusBar(studio: Studio) {
    val d = studio.lowered?.diags?.size ?: 0
    Row(Modifier.fillMaxWidth().height(26.dp).background(C.panel).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(studio.status.ifEmpty { "Ready" }, color = C.dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
        if (d > 0) Text("$d script error(s)  ", color = C.err, fontSize = 11.sp)
        Text(if (studio.editor?.dirty == true) "unsaved changes" else "saved", color = C.dim, fontSize = 11.sp)
    }
}

@Composable
private fun WideLayout(studio: Studio, modifier: Modifier) {
    Row(modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().background(C.panel)) {
                TabLabel("Scene", studio.leftTab == 0, Modifier.weight(1f)) { studio.leftTab = 0 }
                TabLabel("Files", studio.leftTab == 1, Modifier.weight(1f)) { studio.leftTab = 1 }
            }
            if (studio.leftTab == 0) SceneTreePanel(studio, Modifier.fillMaxSize()) else FilesPanel(studio, Modifier.fillMaxSize())
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            CenterTabs(studio)
            Box(Modifier.weight(1f).fillMaxWidth()) { CenterContent(studio) }
            if (studio.consoleOpen) { Box(Modifier.fillMaxWidth().height(1.dp).background(C.line)); ConsolePanel(studio, Modifier.fillMaxWidth().height(170.dp)) }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
        InspectorPanel(studio, Modifier.width(310.dp).fillMaxHeight())
    }
}

@Composable
private fun CompactLayout(studio: Studio, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (studio.panel) {
                Panel.Scene -> Column { Row(Modifier.fillMaxWidth().background(C.panel)) {
                        TabLabel("Scene", studio.leftTab == 0, Modifier.weight(1f)) { studio.leftTab = 0 }
                        TabLabel("Files", studio.leftTab == 1, Modifier.weight(1f)) { studio.leftTab = 1 } }
                    if (studio.leftTab == 0) SceneTreePanel(studio, Modifier.fillMaxSize()) else FilesPanel(studio, Modifier.fillMaxSize()) }
                Panel.Files -> FilesPanel(studio, Modifier.fillMaxSize())
                Panel.Viewport -> Column { CenterTabs(studio); Box(Modifier.weight(1f).fillMaxWidth()) { CenterContent(studio) } }
                Panel.Inspector -> InspectorPanel(studio, Modifier.fillMaxSize())
                Panel.Script -> ScriptEditorPanel(studio, Modifier.fillMaxSize())
                Panel.Console -> ConsolePanel(studio, Modifier.fillMaxSize())
            }
        }
        Row(Modifier.fillMaxWidth().height(56.dp).background(C.panel)) {
            for ((p, icon, label) in listOf(Triple(Panel.Scene, Icons.Default.AccountTree, "Tree"), Triple(Panel.Viewport, Icons.Default.Dashboard, "View"),
                Triple(Panel.Inspector, Icons.Default.Tune, "Inspect"), Triple(Panel.Script, Icons.Default.Code, "Script"), Triple(Panel.Console, Icons.Default.Terminal, "Log"))) {
                Column(Modifier.weight(1f).fillMaxHeight().clickable { studio.panel = p; if (p == Panel.Viewport) studio.centerTab = if (studio.game != null) CenterTab.Game else CenterTab.Scene; if (p == Panel.Script) studio.centerTab = CenterTab.Script },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(icon, label, tint = if (studio.panel == p) C.accent2 else C.dim, modifier = Modifier.size(22.dp))
                    Text(label, color = if (studio.panel == p) C.accent2 else C.dim, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(40.dp).clickable(onClick = onClick).background(if (active) C.panel2 else C.panel), contentAlignment = Alignment.Center) {
        Text(text, color = if (active) C.text else C.dim, fontSize = 13.sp)
    }
}

@Composable
private fun CenterTabs(studio: Studio) {
    Row(Modifier.fillMaxWidth().background(C.panel).horizontalScroll(rememberScrollState())) {
        TabLabel("Scene: ${studio.editor?.title ?: ""}", studio.centerTab == CenterTab.Scene, Modifier.width(180.dp)) { studio.centerTab = CenterTab.Scene }
        if (studio.scripts.isNotEmpty()) TabLabel("Scripts (${studio.scripts.size})", studio.centerTab == CenterTab.Script, Modifier.width(130.dp)) { studio.centerTab = CenterTab.Script }
        if (studio.game != null) TabLabel("Game", studio.centerTab == CenterTab.Game, Modifier.width(100.dp)) { studio.centerTab = CenterTab.Game }
    }
}

@Composable
private fun CenterContent(studio: Studio) {
    when (studio.centerTab) {
        CenterTab.Scene -> ViewportPanel(studio, Modifier.fillMaxSize())
        CenterTab.Script -> ScriptEditorPanel(studio, Modifier.fillMaxSize())
        CenterTab.Game -> studio.game?.let { g -> GameView(g.engine, g.images, g.w, g.h, Modifier.fillMaxSize()) }
    }
}
