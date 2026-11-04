package com.nebula.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private sealed class Entry
private class DirEntry(val path: String, val depth: Int) : Entry()
private class FileEntry(val path: String, val depth: Int) : Entry()

private fun parentOf(path: String) = path.substringBeforeLast('/', "")

/** Flattens a folder tree into rows: folders first (expanded ones list their content), then the files of [dir]. */
private fun entries(dir: String, folders: List<String>, files: List<String>, collapsed: List<String>, depth: Int, out: MutableList<Entry>) {
    for (d in folders) if (parentOf(d) == dir) {
        out.add(DirEntry(d, depth))
        if (d !in collapsed) entries(d, folders, files, collapsed, depth + 1, out)
    }
    for (f in files) if (parentOf(f) == dir) out.add(FileEntry(f, depth))
}

@Composable
fun FilesPanel(studio: Studio, modifier: Modifier = Modifier) {
    val p = studio.project ?: return
    studio.rev
    val collapsed = studio.collapsedFolders.toList()
    val selected = studio.filesFolder
    Column(modifier.background(C.panel)) {
        Section("Files") {
            IconBtn(Icons.Default.CreateNewFolder, "New folder", { studio.modal = Modal.NewFolder(selected ?: "assets") }, size = 36.dp)
            IconBtn(Icons.Default.NoteAdd, "New scene", { studio.modal = Modal.NewScene }, size = 36.dp)
            IconBtn(Icons.Default.Movie, "New animation set (sprite sheet)", { studio.modal = Modal.NewAnimation }, size = 36.dp)
            IconBtn(Icons.Default.FileUpload, "Import asset (image or sound)", { studio.importAsset() }, size = 36.dp)
        }
        if (selected != null) Text("Selected: $selected/  (imports, new scenes and new scripts go here)", color = C.dim, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            fun group(title: String, root: String, files: List<String>, open: (String) -> Unit) {
                val rows = ArrayList<Entry>().also { entries(root, p.folders(root), files, collapsed, 0, it) }
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).background(if (selected == root) C.sel else Color.Transparent)
                        .clickable { studio.filesFolder = if (selected == root) null else root }.padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = C.dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        IconBtn(Icons.Default.CreateNewFolder, "New folder in $root", { studio.modal = Modal.NewFolder(root) }, size = 36.dp, tint = C.dim)
                    }
                }
                items(rows) { e ->
                    when (e) {
                        is DirEntry -> Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(if (selected == e.path) C.sel else Color.Transparent)
                            .clickable {
                                studio.filesFolder = e.path
                                if (e.path in collapsed) studio.collapsedFolders.remove(e.path) else studio.collapsedFolders.add(e.path)
                            }.padding(start = (12 + 16 * e.depth).dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (e.path in collapsed) Icons.Default.Folder else Icons.Default.FolderOpen, null, tint = C.accent2, modifier = Modifier.size(20.dp))
                            Text("  " + e.path.substringAfterLast('/'), color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            IconBtn(Icons.Default.CreateNewFolder, "New subfolder", { studio.modal = Modal.NewFolder(e.path) }, size = 36.dp, tint = C.dim)
                            IconBtn(Icons.Default.Delete, "Delete folder", {
                                studio.modal = Modal.Confirm("Delete the folder ${e.path} and everything in it? This cannot be undone.") { studio.deleteFolder(e.path) }
                            }, size = 36.dp, tint = C.dim)
                        }
                        is FileEntry -> Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open(e.path) }
                            .padding(start = (12 + 16 * e.depth).dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(File(e.path).name + if (e.path == p.mainScene) "  (main)" else "", color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            IconBtn(Icons.Default.Delete, "Delete", { studio.modal = Modal.Confirm("Delete ${e.path}? This cannot be undone.") { studio.deleteFile(e.path) } }, size = 36.dp, tint = C.dim)
                        }
                    }
                }
            }
            group("SCENES", "scenes", p.scenes()) { studio.openScene(it) }
            group("SCRIPTS", "scripts", p.scripts()) { studio.openScript(it) }
            group("ANIMATIONS", "animations", p.animations()) { studio.openAnimation(it) }
            group("ASSETS", "assets", p.assets()) { studio.openAsset(it) }
        }
    }
}
