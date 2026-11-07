package com.nebula.studio.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.studio.Modal
import com.nebula.studio.Studio
import com.nebula.studio.build.TermuxRunner

@Composable
fun WelcomeScreen(studio: Studio) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val projects = remember(tick, studio.modal) { studio.listProjects() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Nebula", color = C.accent2, fontSize = 36.sp)
                Text("2D game engine for tablets. Scenes, nodes, SuperNova scripts.", color = C.dim, fontSize = 14.sp)
            }
            item { TextBtn("New project", { studio.modal = Modal.NewProject }, Modifier.fillMaxWidth(), primary = true) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextBtn("Open folder", { studio.openFolder() }, Modifier.weight(1f))
                    TextBtn("Open zip", { studio.openZip() }, Modifier.weight(1f))
                }
                TextBtn("Import from GitHub", { studio.modal = Modal.ImportGitHub }, Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("A folder, zip or public GitHub repository is copied into the projects folder, and your changes are saved there automatically.", color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            item { Text("PROJECTS", color = C.dim, fontSize = 11.sp, letterSpacing = 1.sp) }
            if (projects.isEmpty()) item { Text("No projects yet. They are stored in ${studio.root.absolutePath}", color = C.dim, fontSize = 13.sp) }
            items(projects) { dir ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel).clickable { studio.openProject(dir) }.heightIn(min = 56.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dir.name, color = C.text, fontSize = 16.sp)
                }
            }
            item { Text("SETUP", color = C.dim, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp)) }
            item {
                val all = studio.hasAllFiles(); val termux = TermuxRunner.installed(ctx)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text((if (all) "✓ " else "✗ ") + "All files access (projects live in a shared folder so Termux can build them)", color = if (all) C.ok else C.warn, fontSize = 13.sp)
                    if (!all) TextBtn("Grant access", { studio.requestAllFiles() })
                    Text((if (termux) "✓ " else "✗ ") + "Termux installed (builds the scripts)", color = if (termux) C.ok else C.warn, fontSize = 13.sp)
                    Text("In Termux: install SuperNova (scripts/termux-install.sh in its repo), run termux-setup-storage, and put allow-external-apps=true in ~/.termux/termux.properties. Then allow Nebula the 'Run commands in Termux' permission in Android settings. Details: docs/GUIDE.md.", color = C.dim, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextBtn("Refresh", { tick++ })
                        TextBtn("App permissions", { ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                    }
                }
            }
        }
    }
}
