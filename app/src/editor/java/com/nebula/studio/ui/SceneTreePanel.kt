package com.nebula.studio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.studio.Modal
import com.nebula.studio.Studio
import nebula.core.NodeTypes
import nebula.core.SceneNode

private class Row2(val node: SceneNode, val depth: Int, val scriptRow: Boolean)

/** The scene tree. A script shows up as a child row of the node it is attached to. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SceneTreePanel(studio: Studio, modifier: Modifier = Modifier) {
    val tree = studio.tree
    val rows = ArrayList<Row2>()
    fun walk(n: SceneNode, depth: Int) {
        rows.add(Row2(n, depth, false))
        if (n.script != null) rows.add(Row2(n, depth + 1, true))
        if (n.path() !in studio.collapsed) for (c in n.children) walk(c, depth + 1)
    }
    tree?.root?.let { walk(it, 0) }
    val sel = studio.selected()

    Column(modifier.background(C.panel)) {
        Section("Scene  ·  ${studio.editor?.title ?: ""}") {
            IconBtn(Icons.Default.Add, "Add node", { studio.modal = Modal.NewNode })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            IconBtn(Icons.Default.Code, "New script for node", { studio.newScriptForSelected() }, sel != null)
            IconBtn(Icons.Default.ContentCopy, "Duplicate", { studio.duplicateSelected() }, sel != null && sel.parent != null)
            IconBtn(Icons.Default.Edit, "Rename", { sel?.let { studio.modal = Modal.Rename(it.path()) } }, sel != null)
            IconBtn(Icons.Default.ArrowUpward, "Move up", { studio.moveSelected(-1) }, sel?.parent != null)
            IconBtn(Icons.Default.ArrowDownward, "Move down", { studio.moveSelected(1) }, sel?.parent != null)
            IconBtn(Icons.AutoMirrored.Filled.ArrowForward, "Indent (make child of node above)", { studio.indentSelected() }, sel?.parent != null)
            IconBtn(Icons.Default.ArrowBack, "Outdent", { studio.outdentSelected() }, sel?.parent?.parent != null)
            IconBtn(Icons.Default.Delete, "Delete", { sel?.let { n -> studio.modal = Modal.Confirm("Delete '${n.name}' and everything below it?") { studio.deleteSelected() } } }, sel?.parent != null, tint = C.err)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.node.id.toString() + if (it.scriptRow) "s" else "n" }) { r ->
                val n = r.node
                val isSel = sel != null && sel.id == n.id
                if (r.scriptRow) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(if (isSel) C.sel else C.panel)
                        .combinedClickable(onClick = { studio.select(n); studio.openScript(n.script!!) },
                            onLongClick = { studio.select(n); studio.modal = Modal.Confirm("Detach ${n.script} from '${n.name}'? (the file stays)") { studio.attachScript(null) } })
                        .padding(start = (12 + r.depth * 16).dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Code, null, tint = C.accent2, modifier = Modifier.size(18.dp))
                        Text("  " + n.script!!.substringAfterLast('/'), color = C.accent2, fontSize = 13.sp, maxLines = 1)
                    }
                } else {
                    val hasKids = n.children.isNotEmpty()
                    val closed = n.path() in studio.collapsed
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(if (isSel) C.sel else C.panel)
                        .combinedClickable(onClick = { studio.select(n) }, onLongClick = { studio.select(n); studio.modal = Modal.Rename(n.path()) })
                        .padding(start = (4 + r.depth * 16).dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp).clickable(enabled = hasKids) { val p = n.path(); if (closed) studio.collapsed.remove(p) else studio.collapsed.add(p) }, contentAlignment = Alignment.Center) {
                            if (hasKids) Icon(if (closed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, null, tint = C.dim, modifier = Modifier.size(20.dp))
                        }
                        Chip(NodeTypes.get(n.type)?.icon ?: "?")
                        Text("  " + n.name, color = C.text, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(if (n.instanceOf != null) "scene" else n.type, color = C.dim, fontSize = 11.sp)
                        if (NodeTypes.isA(n.type, "Node2D")) {
                            val vis = n.b("visible")
                            IconBtn(if (vis) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Toggle visible",
                                { studio.change { ed -> ed.setProp(n, "visible", !vis) } }, size = 36.dp, tint = if (vis) C.dim else C.warn)
                        }
                    }
                }
            }
        }
    }
}
