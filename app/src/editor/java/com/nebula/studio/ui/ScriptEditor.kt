package com.nebula.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.studio.ScriptDoc
import com.nebula.studio.Studio

private val kwSn = setOf("fn", "var", "const", "if", "orif", "else", "while", "for", "in", "return", "break", "continue", "match", "case", "default",
    "and", "or", "not", "has", "repeat", "try", "catch", "finally", "throw", "async", "await", "class", "extends", "enum", "use", "as", "private", "static",
    "true", "false", "null", "self", "super", "pass")
private val kwNebula = setOf("script", "of", "tweak", "broadcast", "shout", "wait", "spawn", "under")
private val hooks = setOf("onReady", "onFrame", "onTick", "onEvery", "onFree")
private val cKw = Color(0xFFC792EA); private val cNeb = Color(0xFFFF9E64); private val cStr = Color(0xFFC3E88D)
private val cCom = Color(0xFF6B7098); private val cNum = Color(0xFFF78C6C); private val cType = Color(0xFF82AAFF); private val cPath = Color(0xFF89DDFF)

fun highlight(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    var i = 0; val n = text.length
    fun style(from: Int, to: Int, c: Color) = addStyle(SpanStyle(color = c), from, to)
    while (i < n) {
        val c = text[i]
        when {
            c == '#' -> { var j = i; while (j < n && text[j] != '\n') j++; style(i, j, cCom); i = j }
            c == '"' -> { var j = i + 1; while (j < n && text[j] != '"' && text[j] != '\n') { if (text[j] == '\\') j++; j++ }; j = minOf(j + 1, n); style(i, j, cStr); i = j }
            c == '@' -> { var j = i + 1; while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '/' || text[j] == '^')) j++; style(i, j, cPath); i = j }
            c.isDigit() -> { var j = i; while (j < n && (text[j].isDigit() || text[j] == '.')) j++; style(i, j, cNum); i = j }
            c.isLetter() || c == '_' -> {
                var j = i; while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val w = text.substring(i, j)
                when {
                    w in kwNebula -> style(i, j, cNeb)
                    w in kwSn -> style(i, j, cKw)
                    w in hooks -> style(i, j, cNeb)
                    w[0].isUpperCase() -> style(i, j, cType)
                }
                i = j
            }
            else -> i++
        }
    }
}

private val highlighter = VisualTransformation { t -> TransformedText(highlight(t.text), OffsetMapping.Identity) }

private fun autoIndent(old: TextFieldValue, nv: TextFieldValue): TextFieldValue {
    if (nv.text.length != old.text.length + 1) return nv
    val pos = nv.selection.start
    if (pos < 1 || nv.text[pos - 1] != '\n' || nv.selection.length != 0) return nv
    val prevStart = nv.text.lastIndexOf('\n', pos - 2) + 1
    val prev = nv.text.substring(prevStart, pos - 1)
    var indent = prev.takeWhile { it == '\t' || it == ' ' }
    val code = prev.substringBefore('#').trimEnd()
    if (code.endsWith(":")) indent += "\t"
    if (indent.isEmpty()) return nv
    return TextFieldValue(nv.text.substring(0, pos) + indent + nv.text.substring(pos), TextRange(pos + indent.length))
}

@Composable
fun ScriptEditorPanel(studio: Studio, modifier: Modifier = Modifier) {
    val doc = studio.scripts.firstOrNull { it.path == studio.activeScript }
    Column(modifier.background(C.bg)) {
        // tabs
        Row(Modifier.fillMaxWidth().background(C.panel).horizontalScroll(rememberScrollState())) {
            for (d in studio.scripts) {
                val active = d.path == studio.activeScript
                Row(Modifier.height(40.dp).background(if (active) C.bg else C.panel).clickable { studio.activeScript = d.path }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.path.substringAfterLast('/') + if (d.dirty) " •" else "", color = if (active) C.text else C.dim, fontSize = 13.sp)
                    IconBtn(Icons.Default.Close, "Close", { studio.closeScript(d.path) }, size = 36.dp, tint = C.dim)
                }
            }
        }
        if (doc == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Open a script from the scene tree or the Files tab.", color = C.dim) }
            return@Column
        }
        // diagnostics for this file
        val diags = studio.lowered?.diags?.filter { it.file == doc.path } ?: emptyList()
        Editor(studio, doc, Modifier.weight(1f))
        if (diags.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 96.dp).background(Color(0xFF2A1520)).verticalScroll(rememberScrollState())) {
                for (d in diags) Text("line ${d.line}: ${d.message}", color = C.err, fontSize = 12.sp, fontFamily = Mono,
                    modifier = Modifier.fillMaxWidth().clickable { studio.openScript(doc.path, d.line) }.padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        KeyRow(studio, doc)
    }
}

@Composable
private fun Editor(studio: Studio, doc: ScriptDoc, modifier: Modifier) {
    var layout by remember(doc.path) { mutableStateOf<TextLayoutResult?>(null) }
    val scroll = rememberScrollState()
    val text = doc.value.text
    LaunchedEffect(text) { studio.lint() }
    Box(modifier.fillMaxWidth().verticalScroll(scroll)) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 400.dp)) {
            // gutter: one number per logical line, placed with the real text layout so wrapped lines stay aligned
            Box(Modifier.width(44.dp).background(C.panel)) {
                val l = layout
                var offset = 0
                val lines = text.split('\n')
                for ((idx, line) in lines.withIndex()) {
                    val y = if (l != null && offset <= l.layoutInput.text.length) l.getLineTop(l.getLineForOffset(minOf(offset, maxOf(0, l.layoutInput.text.length - 1)))) else idx * 20f
                    Text("${idx + 1}", color = C.dim, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.offset { IntOffset(4, y.toInt() + 8) }.width(36.dp))
                    offset += line.length + 1
                }
            }
            BasicTextField(
                value = doc.value, onValueChange = { nv -> doc.change(autoIndent(doc.value, nv)) },
                textStyle = TextStyle(color = C.text, fontSize = 14.sp, fontFamily = Mono, lineHeight = 20.sp),
                cursorBrush = SolidColor(C.accent2), visualTransformation = highlighter,
                onTextLayout = { layout = it },
                modifier = Modifier.weight(1f).padding(8.dp).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Tab) { doc.insert("\t"); true } else false
                })
        }
    }
}

@Composable
private fun KeyRow(studio: Studio, doc: ScriptDoc) {
    Row(Modifier.fillMaxWidth().background(C.panel).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
        IconBtn(Icons.Default.Undo, "Undo", { doc.undo() }); IconBtn(Icons.Default.Redo, "Redo", { doc.redo() })
        IconBtn(Icons.Default.Save, "Save", { studio.saveAll() }, tint = if (doc.dirty) C.accent2 else C.dim)
        for ((label, ins) in listOf("Tab" to "\t", "@" to "@", ":" to ":", "(" to "(", ")" to ")", "[" to "[", "]" to "]", "\"" to "\"", "=" to " = ", "->" to " -> ",
            "." to ".", "," to ", ", "#" to "# ", "{" to "{", "}" to "}", "<" to "<", ">" to ">", "_" to "_", "/" to "/", "^" to "^")) {
            Box(Modifier.height(44.dp).defaultMinSize(minWidth = 40.dp).clickable { doc.insert(ins) }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, color = C.text, fontSize = 16.sp, fontFamily = Mono)
            }
        }
    }
}
