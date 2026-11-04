package com.nebula.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.studio.Modal
import com.nebula.studio.Studio
import com.nebula.studio.runtime.Renderer
import nebula.core.Kind
import nebula.core.NodeTypes
import nebula.core.PropDef
import nebula.core.SceneNode

private val swatches = listOf("#FFFFFF", "#FFB300", "#FF7043", "#EF5350", "#AB47BC", "#5C6BC0", "#4FC3F7", "#66BB6A", "#37474F", "#000000")
private val imageExt = listOf(".png", ".jpg", ".jpeg", ".webp")
private val soundExt = listOf(".wav", ".ogg", ".mp3")

@Composable
fun InspectorPanel(studio: Studio, modifier: Modifier = Modifier) {
    val n = studio.selected()
    Column(modifier.background(C.panel)) {
        Section("Inspector")
        if (n == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Select a node in the scene to edit it.", color = C.dim, fontSize = 14.sp)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(NodeTypes.get(n.type)?.icon ?: "?")
                    Text("  ${if (n.instanceOf != null) "Scene instance: ${n.instanceOf}" else n.type}", color = C.dim, fontSize = 12.sp)
                }
            }
            item { Field(n.name, { v -> studio.renameNode(n.path(), v) }, label = "Name") }
            item { Field(n.tags.joinToString(", "), { v -> studio.change { it.setTags(n, v.split(',').map { t -> t.trim() }.filter { t -> t.isNotEmpty() }) } }, label = "Tags (comma separated)", placeholder = "enemy, hazard") }
            val defs = NodeTypes.propsOf(n.type)
            items2(defs) { d -> PropEditor(studio, n, d) }
            item { ScriptSection(studio, n) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.items2(defs: List<PropDef>, content: @Composable (PropDef) -> Unit) {
    items(defs.size) { i -> content(defs[i]) }
}

@Composable
private fun PropEditor(studio: Studio, n: SceneNode, d: PropDef) {
    if (d.name == "onGround") return          // runtime only
    when (d.kind) {
        Kind.B -> Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(d.name, color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(n.b(d.name), { v -> studio.change { it.setProp(n, d.name, v) } },
                colors = SwitchDefaults.colors(checkedTrackColor = C.accent))
        }
        Kind.F -> Row(verticalAlignment = Alignment.CenterVertically) {
            Field(fmtNum(n.f(d.name)), { v -> v.toDoubleOrNull()?.let { x -> studio.change { it.setProp(n, d.name, x) } } }, Modifier.weight(1f), label = d.name, number = true)
            val step = if (d.name == "rotation") 15.0 else if (d.name in listOf("scale", "zoom", "volume", "pitch", "speed")) 0.1 else if (d.name in listOf("frame", "hframes", "vframes")) 1.0 else 8.0
            IconBtn(Icons.Default.Remove, "less", { studio.change { it.setProp(n, d.name, n.f(d.name) - step) } })
            IconBtn(Icons.Default.Add, "more", { studio.change { it.setProp(n, d.name, n.f(d.name) + step) } })
        }
        Kind.S -> Column {
            when (d.hint) {
                "shape" -> {
                    Text(d.name, color = C.dim, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (o in listOf("rect", "circle")) TextBtn(o, { studio.change { it.setProp(n, d.name, o) } }, primary = n.s(d.name) == o)
                    }
                }
                "image", "sound" -> {
                    Text(d.name, color = C.dim, fontSize = 11.sp)
                    val exts = if (d.hint == "image") imageExt else soundExt
                    TextBtn(n.s(d.name).ifEmpty { "choose file..." }, {
                        val files = (studio.project?.assets() ?: emptyList()).filter { f -> exts.any { f.lowercase().endsWith(it) } }
                        studio.modal = Modal.Pick("Choose ${d.hint}", listOf("(none)") + files) { picked -> studio.change { it.setProp(n, d.name, if (picked == "(none)") "" else picked) } }
                    }, Modifier.fillMaxWidth())
                }
                "animations" -> {
                    Text("${d.name} (sprite sheet animations)", color = C.dim, fontSize = 11.sp)
                    TextBtn(n.s(d.name).ifEmpty { "choose animation set..." }, {
                        val files = studio.project?.animations() ?: emptyList()
                        studio.modal = Modal.Pick("Choose animation set", listOf("(none)") + files) { picked -> studio.change { it.setProp(n, d.name, if (picked == "(none)") "" else picked) } }
                    }, Modifier.fillMaxWidth())
                    if (n.s(d.name).isNotEmpty()) TextBtn("Open animation manager", { studio.openAnimation(n.s(d.name)) }, Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                "animation" -> {
                    Text("${d.name} (clip played at start)", color = C.dim, fontSize = 11.sp)
                    val clips = studio.images?.anims?.get(n.s("animations"))?.clips?.map { it.name } ?: emptyList()
                    TextBtn(n.s(d.name).ifEmpty { "choose clip..." }, {
                        studio.modal = Modal.Pick("Choose clip", listOf("(none)") + clips) { picked -> studio.change { it.setProp(n, d.name, if (picked == "(none)") "" else picked) } }
                    }, Modifier.fillMaxWidth())
                }
                "color" -> {
                    Field(n.s(d.name), { v -> studio.change { it.setProp(n, d.name, v.trim()) } }, label = d.name, mono = true, placeholder = "#RRGGBB")
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (hex in swatches) Box(Modifier.size(28.dp).clip(CircleShape).background(Renderer.parseColor(hex, Color.White))
                            .clickable { studio.change { it.setProp(n, d.name, hex) } })
                    }
                }
                "action" -> {
                    Field(n.s(d.name), { v -> studio.change { it.setProp(n, d.name, v.trim()) } }, label = "${d.name} (held while pressed, read with isDown / axis)", placeholder = "left, right, jump...")
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (a in listOf("left", "right", "up", "down", "jump", "action")) TextBtn(a, { studio.change { it.setProp(n, d.name, a) } }, primary = n.s(d.name) == a)
                    }
                }
                else -> Field(n.s(d.name), { v -> studio.change { it.setProp(n, d.name, v) } }, label = d.name)
            }
        }
    }
}

@Composable
private fun ScriptSection(studio: Studio, n: SceneNode) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.panel2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SCRIPT", color = C.dim, fontSize = 11.sp, letterSpacing = 1.sp)
        val path = n.script
        if (path == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextBtn("New script", { studio.newScriptForSelected() }, primary = true)
                TextBtn("Attach...", {
                    val all = studio.project?.scripts() ?: emptyList()
                    studio.modal = Modal.Pick("Attach script", all) { studio.attachScript(it) }
                })
            }
        } else {
            Text(path, color = C.accent2, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextBtn("Open", { studio.openScript(path) }, primary = true)
                TextBtn("Detach", { studio.attachScript(null) })
            }
            val meta = studio.metaOf(path)
            if (meta != null && meta.tweaks.isNotEmpty()) {
                Text("TWEAKS", color = C.dim, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 6.dp))
                for (t in meta.tweaks) {
                    val cur = n.tweaks[t.name]
                    when (t.kind) {
                        "Bool" -> Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch((cur as? Boolean) ?: (t.default == "true"), { v -> studio.change { it.setTweak(n, t.name, v) } }, colors = SwitchDefaults.colors(checkedTrackColor = C.accent))
                        }
                        "String" -> Field((cur as? String) ?: t.default.trim('"'), { v -> studio.change { it.setTweak(n, t.name, v) } }, label = t.name)
                        else -> Field(fmtNum((cur as? Number)?.toDouble() ?: t.default.toDoubleOrNull() ?: 0.0), { v ->
                            v.toDoubleOrNull()?.let { x -> studio.change { it.setTweak(n, t.name, if (t.kind == "Int") Math.round(x).toDouble() else x) } }
                        }, label = "${t.name} (${t.kind}, default ${t.default})", number = true)
                    }
                }
            }
        }
    }
}
