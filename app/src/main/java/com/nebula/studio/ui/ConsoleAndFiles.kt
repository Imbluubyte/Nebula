package com.nebula.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.studio.Modal
import com.nebula.studio.Studio
import java.io.File

private val locRe = Regex("""(scripts/[\w./-]+\.nbs):(\d+)""")

@Composable
fun ConsolePanel(studio: Studio, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(studio.console.size) { if (studio.console.isNotEmpty()) state.scrollToItem(studio.console.size - 1) }
    Column(modifier.background(Color0)) {
        Section("Console") {
            IconBtn(Icons.Default.DeleteSweep, "Clear", { studio.console.clear() }, size = 36.dp)
        }
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(studio.console.toList()) { l ->
                val m = locRe.find(l.text)
                Text(l.text, color = when (l.level) { 2 -> C.err; 1 -> C.warn; else -> C.text }, fontSize = 12.sp, fontFamily = Mono,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = m != null) { m?.let { studio.openScript(it.groupValues[1], it.groupValues[2].toInt()) } }
                        .padding(horizontal = 12.dp, vertical = 3.dp))
            }
        }
    }
}
private val Color0 = androidx.compose.ui.graphics.Color(0xFF0B0C18)

@Composable
fun FilesPanel(studio: Studio, modifier: Modifier = Modifier) {
    val p = studio.project ?: return
    studio.rev
    Column(modifier.background(C.panel)) {
        Section("Files") {
            IconBtn(Icons.Default.NoteAdd, "New scene", { studio.modal = Modal.NewScene }, size = 36.dp)
            IconBtn(Icons.Default.FileUpload, "Import asset (image or sound)", { studio.importAsset() }, size = 36.dp)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            fun group(title: String, files: List<String>, open: (String) -> Unit) {
                item { Text(title, color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)) }
                items(files) { f ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open(f) }.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(File(f).name + if (f == p.mainScene) "  (main)" else "", color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        IconBtn(Icons.Default.Delete, "Delete", { studio.modal = Modal.Confirm("Delete $f? This cannot be undone.") { studio.deleteFile(f) } }, size = 36.dp, tint = C.dim)
                    }
                }
            }
            group("SCENES", p.scenes()) { studio.openScene(it) }
            group("SCRIPTS", p.scripts()) { studio.openScript(it) }
            group("ASSETS", p.assets(), { })
        }
    }
}
