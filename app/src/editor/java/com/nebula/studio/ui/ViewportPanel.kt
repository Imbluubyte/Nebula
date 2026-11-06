package com.nebula.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.studio.Studio
import com.nebula.studio.runtime.DrawItem
import com.nebula.studio.runtime.Renderer
import com.nebula.studio.runtime.Renderer.drawNode
import com.nebula.studio.runtime.Renderer.text

/**
 * Scene editor view. One finger on a node selects and drags it, one finger on empty space pans,
 * two fingers pan and pinch-zoom. Works with touch only; the buttons on the right cover zoom and snapping.
 * With a mouse: the wheel zooms around the pointer, dragging with the middle button pans, and Ctrl + / Ctrl - / Ctrl 0 (see Commands) zoom and fit.
 */
@Composable
fun ViewportPanel(studio: Studio, modifier: Modifier = Modifier) {
    val project = studio.project ?: return
    val tree = studio.tree
    val images = studio.images
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(studio.scenePath) { mutableFloatStateOf(0f) }
    var offset by remember(studio.scenePath) { mutableStateOf(Offset.Zero) }
    val gw = project.gameWidth.toFloat(); val gh = project.gameHeight.toFloat()

    fun fit() {
        if (size.width == 0) return
        zoom = minOf(size.width / (gw + 160f), size.height / (gh + 160f)).coerceIn(0.05f, 4f)
        offset = Offset((size.width - gw * zoom) / 2f, (size.height - gh * zoom) / 2f)
    }
    LaunchedEffect(size, studio.scenePath) { if (zoom == 0f) fit() }
    val focusManager = LocalFocusManager.current

    /** Zooms by [factor] keeping the point [p] (view coordinates) where it is. */
    fun zoomAt(p: Offset, factor: Float) {
        if (zoom == 0f) return
        val nz = (zoom * factor).coerceIn(0.05f, 8f)
        offset = p - (p - offset) * (nz / zoom)
        zoom = nz
    }

    // keyboard zoom (Ctrl + / Ctrl - / Ctrl 0) arrives as a request from Commands
    val request = studio.viewRequest
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        when (r.kind) {
            "in" -> zoomAround(size, { zoom }, { offset }, 1.25f) { z, o -> zoom = z; offset = o }
            "out" -> zoomAround(size, { zoom }, { offset }, 0.8f) { z, o -> zoom = z; offset = o }
            "fit" -> fit()
        }
        studio.viewRequest = null
    }

    fun toWorld(p: Offset) = Offset((p.x - offset.x) / zoom, (p.y - offset.y) / zoom)
    fun hit(p: Offset): DrawItem? {
        val w = toWorld(p)
        return tree?.let { Renderer.collect(it).asReversed().firstOrNull { item ->
            val (sw, sh) = Renderer.sizeOf(item.node, images)
            val minHalf = 14f / zoom   // keep tiny nodes touchable
            Math.abs(w.x - item.x) <= maxOf(sw / 2, minHalf.toDouble()) && Math.abs(w.y - item.y) <= maxOf(sh / 2, minHalf.toDouble())
        } }
    }

    Box(modifier.background(Color(0xFF0B0C18)).onSizeChanged { size = it }) {
        Canvas(Modifier.fillMaxSize().pointerInput(studio.scenePath) {
            // mouse wheel: zoom around the pointer
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type == PointerEventType.Scroll) {
                        val c = ev.changes.first()
                        val dy = c.scrollDelta.y
                        if (dy != 0f) { zoomAt(c.position, if (dy < 0f) 1.1f else 1f / 1.1f); c.consume() }
                    }
                }
            }
        }.pointerInput(studio.scenePath, studio.snap) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                focusManager.clearFocus()                      // so a text field in the inspector lets go of the keyboard (and its shortcuts work again)
                if (currentEvent.buttons.isTertiaryPressed) {
                    // middle mouse button: dragging pans the view, whatever is under the pointer
                    do {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.pressed }
                        if (ch != null) { offset += ch.positionChange(); ch.consume() }
                    } while (ev.changes.any { it.pressed })
                    return@awaitEachGesture
                }
                val paintNode = studio.paintNode()          // while painting tiles one finger paints and two fingers pan/zoom
                val target = if (paintNode != null) null else hit(down.position)
                if (target != null) studio.select(target.node)
                var moved = 0f; var transforming = false; var dragging = false; var stroke = false
                fun paintAt(p: Offset) { val w = toWorld(p); if (paintNode != null) studio.paintTile(paintNode, w.x.toDouble(), w.y.toDouble()) }
                var rawX = target?.node?.f("x") ?: 0.0; var rawY = target?.node?.f("y") ?: 0.0
                do {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        transforming = true
                        val z = ev.calculateZoom(); val pan = ev.calculatePan(); val c = ev.calculateCentroid()
                        val nz = (zoom * z).coerceIn(0.05f, 8f)
                        // keep the point under the fingers fixed while zooming
                        offset = c - (c - offset - pan * 0f) * (nz / zoom) + pan
                        zoom = nz
                        ev.changes.forEach { it.consume() }
                    } else if (pressed.size == 1 && !transforming) {
                        val d = pressed[0].positionChange()
                        moved += d.getDistance()
                        if (paintNode != null) {
                            // painting starts once the finger really moves (so the first finger of a two-finger pan never paints)
                            if (moved > 10f && studio.tileTool != "fill") {
                                if (!stroke) { stroke = true; studio.beginTileStroke(); paintAt(down.position) }
                                paintAt(pressed[0].position)
                                pressed[0].consume()
                            }
                        } else if (moved > 10f) {
                            val node = target?.node
                            if (node != null && studio.selected()?.id == node.id) {
                                if (!dragging) { dragging = true; studio.editor?.beginDrag() }
                                rawX += d.x / zoom; rawY += d.y / zoom
                                val s = studio.snap.toDouble()
                                node.props["x"] = if (s > 0) Math.round(rawX / s) * s else rawX
                                node.props["y"] = if (s > 0) Math.round(rawY / s) * s else rawY
                                studio.rev++
                            } else offset += d
                            pressed[0].consume()
                        }
                    }
                } while (ev.changes.any { it.pressed })
                if (paintNode != null && moved <= 10f && !transforming) { studio.beginTileStroke(); paintAt(down.position) }   // a tap paints / erases / fills one spot
                if (paintNode == null && moved <= 10f && !transforming && target == null) studio.select(null)
            }
        }) {
            val z = if (zoom == 0f) 1f else zoom
            withTransform({ translate(offset.x, offset.y); scale(z, z, Offset.Zero) }) {
                // game area
                drawRect(Color(0xFF101820), Offset.Zero, Size(gw, gh))
                if (studio.showGrid) {
                    val step = 64f
                    val a = Color(0x1AFFFFFF); val stroke = 1f / z
                    var x = 0f; while (x <= gw) { drawLine(a, Offset(x, 0f), Offset(x, gh), stroke); x += step }
                    var y = 0f; while (y <= gh) { drawLine(a, Offset(0f, y), Offset(gw, y), stroke); y += step }
                }
                drawRect(Color(0x66B388FF), Offset.Zero, Size(gw, gh), style = Stroke(2f / z))
                if (tree != null) {
                    val items = Renderer.collect(tree)
                    for (item in items) drawNode(item, images, true)
                    val sel = studio.selected()
                    val sItem = items.firstOrNull { it.node.id == sel?.id }
                    if (sItem != null) {
                        val (w, h) = Renderer.sizeOf(sItem.node, images)
                        val tl = Offset((sItem.x - w / 2).toFloat(), (sItem.y - h / 2).toFloat())
                        drawRect(C.accent2, tl, Size(w.toFloat(), h.toFloat()), style = Stroke(2.5f / z, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f / z, 6f / z))))
                        val hs = 7f / z
                        for (cx in listOf(tl.x, tl.x + w.toFloat())) for (cy in listOf(tl.y, tl.y + h.toFloat()))
                            drawRect(C.accent2, Offset(cx - hs, cy - hs), Size(hs * 2, hs * 2))
                        text(sel!!.name, sItem.x.toFloat(), tl.y - 16f / z, 14f / z, C.accent2, true)
                    }
                }
            }
        }
        // zoom / fit / grid / snap controls
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp).background(C.panel.copy(alpha = 0.85f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))) {
            IconBtn(Icons.Default.ZoomIn, "Zoom in", { zoomAround(size, { zoom }, { offset }, 1.25f) { z, o -> zoom = z; offset = o } })
            IconBtn(Icons.Default.ZoomOut, "Zoom out", { zoomAround(size, { zoom }, { offset }, 0.8f) { z, o -> zoom = z; offset = o } })
            IconBtn(Icons.Default.FitScreen, "Fit to screen", { fit() })
            IconBtn(Icons.Default.GridOn, "Toggle grid", { studio.showGrid = !studio.showGrid; studio.setSetting("grid", studio.showGrid.toString()) }, tint = if (studio.showGrid) C.accent2 else C.dim)
            IconBtn(Icons.Default.GridView, "Cycle snap", { studio.snap = when (studio.snap) { 0 -> 8; 8 -> 16; 16 -> 32; 32 -> 64; else -> 0 }; studio.setSetting("snap", studio.snap.toString()) }, tint = if (studio.snap > 0) C.accent2 else C.dim)
        }
        studio.paintNode()?.let { TilePalette(studio, it, Modifier.align(Alignment.BottomCenter).padding(8.dp)) }
        Text("snap ${if (studio.snap > 0) studio.snap else "off"}  ·  ${(zoom * 100).toInt()}%", color = C.dim, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
    }
}

private fun zoomAround(size: IntSize, zoom: () -> Float, offset: () -> Offset, factor: Float, set: (Float, Offset) -> Unit) {
    if (zoom() == 0f) return                       // not fitted yet
    val c = Offset(size.width / 2f, size.height / 2f)
    val nz = (zoom() * factor).coerceIn(0.05f, 8f)
    set(nz, c - (c - offset()) * (nz / zoom()))
}
