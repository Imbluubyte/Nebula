package com.nebula.studio

import androidx.compose.ui.input.key.*
import com.nebula.studio.ui.TextFocus

/** Everything you can do, in one list: used by the command palette, the toolbar and keyboard shortcuts. */
class Command(val id: String, val title: String, val shortcut: String?, val enabled: (Studio) -> Boolean = { true }, val run: (Studio) -> Unit)

object Commands {
    private fun hasProject(s: Studio) = s.project != null
    private fun hasNode(s: Studio) = s.selected() != null
    /** The scene view is what you are working in: no dialog open and no text field holding the keyboard (so copy / paste / delete keep their text meaning there). */
    private fun inScene(s: Studio) = s.project != null && s.centerTab == CenterTab.Scene && s.modal == null && TextFocus.count == 0

    val all: List<Command> = listOf(
        Command("save", "Save all", "Ctrl+S", ::hasProject) { it.saveAll(); it.info("Saved") },
        Command("undo", "Undo", "Ctrl+Z", ::hasProject) { s ->
            if (s.centerTab == CenterTab.Script) s.scripts.firstOrNull { it.path == s.activeScript }?.undo() else s.undo() },
        Command("redo", "Redo", "Ctrl+Shift+Z", ::hasProject) { s ->
            if (s.centerTab == CenterTab.Script) s.scripts.firstOrNull { it.path == s.activeScript }?.redo() else s.redo() },
        Command("run", "Run game", "F5", ::hasProject) { it.play() },
        Command("runScene", "Run current scene", "F6", ::hasProject) { it.play(it.scenePath) },
        Command("stop", "Stop game", "Shift+F5", { it.game != null }) { it.stop() },
        Command("build", "Build scripts", "Ctrl+B", ::hasProject) { it.build() },
        Command("export", "Export APK...", "Ctrl+E", ::hasProject) { it.modal = Modal.Export },
        Command("addNode", "Add node...", "Ctrl+N", ::hasProject) { it.modal = Modal.NewNode },
        Command("newScript", "New script for selected node", "Ctrl+Shift+N", ::hasNode) { it.newScriptForSelected() },
        Command("dup", "Duplicate node", "Ctrl+D", ::hasNode) { it.duplicateSelected() },
        Command("copy", "Copy node", "Ctrl+C", { inScene(it) && hasNode(it) }) { it.copySelected() },
        Command("cut", "Cut node", "Ctrl+X", { inScene(it) && hasNode(it) }) { it.cutSelected() },
        Command("paste", "Paste node", "Ctrl+V", { inScene(it) && it.nodeClipboard != null }) { it.pasteNode() },
        Command("deleteNow", "Delete node (no question)", "Delete", { inScene(it) && hasNode(it) }) { it.deleteSelectedNow() },
        Command("deselect", "Deselect", "Escape", { inScene(it) && hasNode(it) }) { it.select(null) },
        Command("rename", "Rename node", "F2", ::hasNode) { s -> s.selected()?.let { s.modal = Modal.Rename(it.path()) } },
        Command("delete", "Delete node", "Ctrl+Backspace", ::hasNode) { s -> s.selected()?.let { n -> s.modal = Modal.Confirm("Delete '${n.name}'?") { s.deleteSelected() } } },
        Command("up", "Move node up", "Ctrl+Up", ::hasNode) { it.moveSelected(-1) },
        Command("down", "Move node down", "Ctrl+Down", ::hasNode) { it.moveSelected(1) },
        Command("newScene", "New scene...", null, ::hasProject) { it.modal = Modal.NewScene },
        Command("viewScene", "Show scene", "Ctrl+1", ::hasProject) { it.centerTab = CenterTab.Scene; it.panel = Panel.Viewport },
        Command("viewScript", "Show script editor", "Ctrl+2", { it.scripts.isNotEmpty() }) { it.centerTab = CenterTab.Script; it.panel = Panel.Script },
        Command("viewGame", "Show game", "Ctrl+3", { it.game != null }) { it.centerTab = CenterTab.Game },
        Command("console", "Toggle console", "Ctrl+`", ::hasProject) { it.consoleOpen = !it.consoleOpen },
        Command("nextTab", "Next script tab", "Ctrl+Tab", { it.scripts.size > 1 }) { s ->
            val i = s.scripts.indexOfFirst { it.path == s.activeScript }; s.activeScript = s.scripts[(i + 1) % s.scripts.size].path; s.centerTab = CenterTab.Script },
        Command("closeScript", "Close script tab", "Ctrl+W", { it.activeScript != null }) { s -> s.activeScript?.let { s.closeScript(it) } },
        Command("zoomIn", "Zoom in", "Ctrl+=", ::inScene) { it.viewRequest = Studio.ViewRequest("in") },
        Command("zoomOut", "Zoom out", "Ctrl+-", ::inScene) { it.viewRequest = Studio.ViewRequest("out") },
        Command("zoomFit", "Fit scene to screen", "Ctrl+0", ::inScene) { it.viewRequest = Studio.ViewRequest("fit") },
        Command("grid", "Toggle grid", "Ctrl+G", ::hasProject) { it.showGrid = !it.showGrid },
        Command("fps", "Toggle FPS counter", "F3", ::hasProject) { it.toggleFps() },
        Command("settings", "Project settings", "Ctrl+,", ::hasProject) { it.modal = Modal.Settings },
        Command("palette", "Command palette", "Ctrl+Shift+P", { true }) { it.modal = Modal.Palette },
        Command("help", "Help and shortcuts", "F1", { true }) { it.modal = Modal.Help },
        Command("close", "Close project", null, ::hasProject) { it.closeProject() },
    )

    private class Parsed(val ctrl: Boolean, val shift: Boolean, val keyCode: Int)

    private val keyNames = mapOf("Up" to android.view.KeyEvent.KEYCODE_DPAD_UP, "Down" to android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        "Backspace" to android.view.KeyEvent.KEYCODE_DEL, "Tab" to android.view.KeyEvent.KEYCODE_TAB, "," to android.view.KeyEvent.KEYCODE_COMMA,
        "`" to android.view.KeyEvent.KEYCODE_GRAVE, "Delete" to android.view.KeyEvent.KEYCODE_FORWARD_DEL, "Escape" to android.view.KeyEvent.KEYCODE_ESCAPE,
        "=" to android.view.KeyEvent.KEYCODE_EQUALS, "-" to android.view.KeyEvent.KEYCODE_MINUS)

    private val parsed: List<Pair<Command, Parsed>> = all.mapNotNull { c ->
        val sc = c.shortcut ?: return@mapNotNull null
        val parts = sc.split('+'); val k = parts.last()
        val code = when {
            k.length == 1 && k[0].isLetter() -> android.view.KeyEvent.KEYCODE_A + (k[0].uppercaseChar() - 'A')
            k.startsWith("F") && k.length <= 3 && k.drop(1).toIntOrNull() != null -> android.view.KeyEvent.KEYCODE_F1 + (k.drop(1).toInt() - 1)
            k.length == 1 && k[0].isDigit() -> android.view.KeyEvent.KEYCODE_0 + (k[0] - '0')
            else -> keyNames[k] ?: return@mapNotNull null
        }
        c to Parsed("Ctrl" in parts, "Shift" in parts, code)
    }

    /** Returns true when the key event was a shortcut and has been handled. */
    fun handleKey(studio: Studio, e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val code = e.nativeKeyEvent.keyCode
        val ctrl = e.isCtrlPressed; val shift = e.isShiftPressed
        // Ctrl+Y is the usual alternative for redo
        if (ctrl && code == android.view.KeyEvent.KEYCODE_Y) { find("redo")?.let { if (it.enabled(studio)) it.run(studio) }; return true }
        if (ctrl && code == android.view.KeyEvent.KEYCODE_K) { studio.modal = Modal.Palette; return true }
        // Ctrl and + (Ctrl+Shift+= on most keyboards) and the keypad plus / minus also zoom
        if (ctrl && (code == android.view.KeyEvent.KEYCODE_PLUS || code == android.view.KeyEvent.KEYCODE_NUMPAD_ADD || (code == android.view.KeyEvent.KEYCODE_EQUALS && shift))) return runIfEnabled(studio, "zoomIn")
        if (ctrl && code == android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT) return runIfEnabled(studio, "zoomOut")
        for ((c, p) in parsed) if (p.keyCode == code && p.ctrl == ctrl && p.shift == shift) {
            // a disabled shortcut is not swallowed, so the focused text field still gets its Ctrl+C / Ctrl+V / Delete
            if (!c.enabled(studio)) return false
            c.run(studio)
            return true
        }
        return false
    }

    private fun runIfEnabled(studio: Studio, id: String): Boolean {
        val c = find(id) ?: return false
        if (!c.enabled(studio)) return false
        c.run(studio); return true
    }

    fun find(id: String) = all.firstOrNull { it.id == id }
}
