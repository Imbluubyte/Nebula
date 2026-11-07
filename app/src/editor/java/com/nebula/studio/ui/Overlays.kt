package com.nebula.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.studio.Commands
import com.nebula.studio.Modal
import com.nebula.studio.Studio
import nebula.core.NodeTypes
import nebula.core.Preset

/** Dialogs are drawn inside the window (not as system dialogs) so keyboard shortcuts keep working. */
@Composable
fun ModalHost(studio: Studio) {
    val m = studio.modal ?: return
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { if (m is Modal.Sprite) studio.closeSprite() else studio.modal = null }, contentAlignment = Alignment.TopCenter) {
        val wide = m is Modal.Animation || m is Modal.Sprite
        Column(Modifier.padding(top = if (wide) 24.dp else 56.dp, start = 16.dp, end = 16.dp).widthIn(max = if (wide) 860.dp else 560.dp).fillMaxWidth().heightIn(max = if (wide) 720.dp else 640.dp)
            .clip(RoundedCornerShape(16.dp)).background(C.panel).clickable(enabled = false) {}.padding(16.dp)) {
            when (m) {
                Modal.NewProject -> NewProjectDialog(studio)
                Modal.ImportGitHub -> ImportGitHubDialog(studio)
                Modal.NewNode -> NewNodeDialog(studio)
                Modal.NewAnimation -> TextPrompt(studio, "New animation set", "Name", "hero", "Create") { studio.newAnimation(it) }
                Modal.Animation -> AnimationManager(studio)
                Modal.NewSprite -> NewSpriteDialog(studio)
                Modal.Sprite -> SpriteEditor(studio)
                is Modal.NewFolder -> TextPrompt(studio, "New folder in ${m.parent}/", "Folder name", "", "Create") { studio.newFolder(m.parent, it) }
                Modal.NewScene -> TextPrompt(studio, "New scene", "Scene name", "level2", "Create") { studio.newScene(it) }
                Modal.Palette -> PaletteDialog(studio)
                Modal.Export -> ExportDialog(studio)
                Modal.Settings -> SettingsDialog(studio)
                Modal.Help -> HelpDialog(studio)
                is Modal.Rename -> {
                    val t = studio.editor?.tree; val n = t?.root?.let { t.find(it, m.path) }
                    TextPrompt(studio, "Rename node", "Name", n?.name ?: "", "Rename") { studio.renameNode(m.path, it) }
                }
                is Modal.Confirm -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(m.message, color = C.text, fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextBtn("Yes", { studio.modal = null; m.action() }, primary = true); TextBtn("Cancel", { studio.modal = null })
                    }
                }
                is Modal.Pick -> Column {
                    Text(m.title, color = C.text, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
                    if (m.options.size <= 1) Text("Nothing to choose from yet. Use the Files tab to import assets or create scripts.", color = C.dim, fontSize = 13.sp)
                    LazyColumn { items(m.options) { o -> Text(o, color = C.text, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { studio.modal = null; m.onPick(o) }.padding(12.dp)) } }
                }
            }
        }
    }
}

@Composable
private fun TextPrompt(studio: Studio, title: String, label: String, initial: String, action: String, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = C.text, fontSize = 18.sp)
        Field(initial, { text = it; if (it.isNotBlank()) { studio.modal = null; onOk(it) } }, label = label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextBtn(action, { if (text.isNotBlank()) { studio.modal = null; onOk(text) } }, primary = true); TextBtn("Cancel", { studio.modal = null })
        }
    }
}

@Composable
private fun ImportGitHubDialog(studio: Studio) {
    var url by remember { mutableStateOf("") }
    fun go(u: String) { if (u.isNotBlank()) { studio.modal = null; studio.importGitHub(u) } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Import from GitHub", color = C.text, fontSize = 18.sp)
        Field("", { url = it; go(it) }, label = "Repository address", placeholder = "https://github.com/owner/repo", mono = true)
        Text("Public repositories only. A copy is saved in the projects folder, and your changes are saved there automatically. A link to a branch (.../tree/branch) imports that branch.", color = C.dim, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextBtn("Import", { go(url) }, primary = true); TextBtn("Cancel", { studio.modal = null })
        }
    }
}

@Composable
private fun NewProjectDialog(studio: Studio) {
    var name by remember { mutableStateOf("MyGame") }
    var pkg by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(Preset.PLATFORMER) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("New project", color = C.text, fontSize = 18.sp)
        Field("MyGame", { name = it }, label = "Name")
        Field("", { pkg = it }, label = "Package id (optional)", placeholder = "com.example.mygame", mono = true)
        Text("Start from", color = C.dim, fontSize = 12.sp)
        for (p in Preset.values()) {
            val on = p == preset
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) C.sel else C.panel2)
                .border(if (on) 2.dp else 1.dp, if (on) C.accent else C.line, RoundedCornerShape(12.dp))
                .clickable { preset = p }.heightIn(min = 56.dp).padding(12.dp)) {
                Text(p.label, color = C.text, fontSize = 15.sp)
                Text(p.blurb, color = C.dim, fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextBtn("Create", { studio.modal = null; studio.createProject(name, pkg, preset) }, primary = true); TextBtn("Cancel", { studio.modal = null })
        }
    }
}

@Composable
private fun NewNodeDialog(studio: Studio) {
    Column {
        Text("Add node under '${studio.selected()?.name ?: studio.editor?.tree?.root?.name ?: ""}'", color = C.text, fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
        LazyVerticalGrid(GridCells.Adaptive(150.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(NodeTypes.all) { t ->
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(C.panel2).clickable { studio.modal = null; studio.addNode(t.name) }.heightIn(min = 56.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(t.icon); Text("  ${t.name}", color = C.text, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun PaletteDialog(studio: Studio) {
    var q by remember { mutableStateOf("") }
    val list = Commands.all.filter { q.isBlank() || it.title.contains(q, ignoreCase = true) }
    Column {
        LiveSearch(q) { q = it }
        LazyColumn {
            items(list) { c ->
                val ok = c.enabled(studio)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = ok) { studio.modal = null; c.run(studio) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, color = if (ok) C.text else C.dim, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (c.shortcut != null) Text(c.shortcut, color = C.dim, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun LiveSearch(value: String, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(value, onChange, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = C.text, fontSize = 15.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(C.accent2),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp)).background(C.bg).padding(12.dp))
}

@Composable
private fun ExportDialog(studio: Studio) {
    val p = studio.project ?: return
    var pkg by remember { mutableStateOf(p.packageId) }
    var label by remember { mutableStateOf(p.title) }
    var install by remember { mutableStateOf(true) }
    var downloads by remember { mutableStateOf(studio.setting("export_downloads") == "true") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Export APK", color = C.text, fontSize = 18.sp)
        Field(p.title, { label = it }, label = "App name")
        Field(p.packageId, { pkg = it }, label = "Package id", mono = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Install after export", color = C.text, modifier = Modifier.weight(1f))
            Switch(install, { install = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.accent))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Also save a copy to the Downloads folder", color = C.text, modifier = Modifier.weight(1f))
            Switch(downloads, { downloads = it; studio.setSetting("export_downloads", it.toString()) }, colors = SwitchDefaults.colors(checkedTrackColor = C.accent))
        }
        Text("Builds your scripts, packs the project into a copy of the Nebula player and signs it" +
            (if (studio.setting("keystore").isBlank()) " with Nebula's built-in debug key (fine for sideloading; set your own keystore in Settings for releases)." else " with your keystore."),
            color = C.dim, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextBtn("Export", { studio.modal = null; studio.export(pkg.trim(), label.trim().ifEmpty { p.title }, install, downloads) }, primary = true); TextBtn("Cancel", { studio.modal = null })
        }
    }
}

@Composable
private fun SettingsDialog(studio: Studio) {
    val p = studio.project ?: return
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Project settings", color = C.text, fontSize = 18.sp)
        Field(p.title, { p.title = it; p.save() }, label = "Title")
        TextBtn("Main scene: ${p.mainScene}", { studio.modal = Modal.Pick("Main scene", p.scenes()) { p.mainScene = it; p.save() } }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(p.gameWidth.toString(), { v -> v.toIntOrNull()?.let { p.gameWidth = it.coerceIn(100, 8000); p.save(); studio.rev++ } }, Modifier.weight(1f), label = "Game width", number = true)
            Field(p.gameHeight.toString(), { v -> v.toIntOrNull()?.let { p.gameHeight = it.coerceIn(100, 8000); p.save(); studio.rev++ } }, Modifier.weight(1f), label = "Game height", number = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Landscape", color = C.text, modifier = Modifier.weight(1f))
            var land by remember { mutableStateOf(p.landscape) }
            Switch(land, { land = it; p.landscape = it; p.save() }, colors = SwitchDefaults.colors(checkedTrackColor = C.accent))
        }
        Text("EXPORT SIGNING (optional)", color = C.dim, fontSize = 11.sp)
        Field(studio.setting("keystore"), { studio.setSetting("keystore", it.trim()) }, label = "Keystore file (.p12 / PKCS12)", mono = true, placeholder = "/storage/emulated/0/my-key.p12")
        Field(studio.setting("keystore_pass", "nebula"), { studio.setSetting("keystore_pass", it) }, label = "Store password")
        Field(studio.setting("keystore_alias", "nebula"), { studio.setSetting("keystore_alias", it) }, label = "Key alias")
        Field(studio.setting("keystore_key_pass", "nebula"), { studio.setSetting("keystore_key_pass", it) }, label = "Key password")
        TextBtn("Done", { studio.modal = null }, primary = true)
    }
}

@Composable
private fun HelpDialog(studio: Studio) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Shortcuts (keyboard optional: everything has a button too)", color = C.text, fontSize = 16.sp)
        for (c in Commands.all) if (c.shortcut != null) Row {
            Text(c.title, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(c.shortcut, color = C.dim, fontSize = 13.sp)
        }
        Text("Touch: one finger on a node moves it, on empty space pans, two fingers pan and zoom. Long-press a node in the tree to rename.", color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Text("Full guide: docs/GUIDE.md in the Nebula repository.", color = C.dim, fontSize = 12.sp)
        TextBtn("Close", { studio.modal = null }, primary = true)
    }
}
