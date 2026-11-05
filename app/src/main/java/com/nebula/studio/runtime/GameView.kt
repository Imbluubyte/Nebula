package com.nebula.studio.runtime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nebula.core.Engine
import com.nebula.studio.runtime.Renderer.drawNode

/** Letterboxed game surface: runs the engine every frame, draws the tree, feeds touch and keyboard in. */
@Composable
fun GameView(engine: Engine, images: ImageCache, gameW: Int, gameH: Int, modifier: Modifier = Modifier, showFps: Boolean = false) {
    var frame by remember { mutableLongStateOf(0L) }
    var fps by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOfSize() }
    val focus = remember { FocusRequester() }

    LaunchedEffect(engine) {
        focus.requestFocus()
        var last = 0L; var acc = 0.0; var count = 0
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                if (last != 0L) {
                    val dt = (t - last) / 1e9
                    engine.update(dt.coerceAtMost(0.05))
                    acc += dt; count++
                    if (acc >= 0.5) { fps = Math.round(count / acc).toInt(); acc = 0.0; count = 0 }     // frames per second, averaged over half a second
                }
                last = t; frame = t
            }
        }
    }

    /** A touch position in pixels to a spot on the game screen (0..gameW x 0..gameH). The engine turns it into a world position through the camera. */
    fun toGame(p: Offset): Pair<Double, Double> {
        val k = minOf(size.width / gameW.toFloat(), size.height / gameH.toFloat())
        val ox = (size.width - gameW * k) / 2f; val oy = (size.height - gameH * k) / 2f
        return ((p.x - ox) / k).toDouble() to ((p.y - oy) / k).toDouble()
    }

    Box(modifier.fillMaxSize().background(Color.Black).onSizeChanged { size = it }
        .focusRequester(focus).focusable()
        .onKeyEvent { e ->
            val action = when (e.key) {
                Key.DirectionLeft, Key.A -> "left"; Key.DirectionRight, Key.D -> "right"
                Key.DirectionUp, Key.W -> "up"; Key.DirectionDown, Key.S -> "down"
                Key.Spacebar -> "jump"; Key.Z, Key.Enter -> "action"; else -> null
            }
            if (action == null) false else {
                if (e.type == KeyEventType.KeyDown) engine.input.keys.add(action) else if (e.type == KeyEventType.KeyUp) engine.input.keys.remove(action)
                true
            }
        }
        .pointerInput(engine, size) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val down = ev.changes.filter { it.pressed }.map { toGame(it.position) }
                    engine.setPointers(down)
                    for (c in ev.changes) if (c.pressed && !c.previousPressed) { val (gx, gy) = toGame(c.position); engine.tap(gx, gy) }
                }
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            frame.hashCode()   // redraw every frame
            val k = minOf(this.size.width / gameW, this.size.height / gameH)
            val ox = (this.size.width - gameW * k) / 2f; val oy = (this.size.height - gameH * k) / 2f
            val zoom = engine.camZoom.toFloat(); val camX = engine.camX.toFloat(); val camY = engine.camY.toFloat()   // the engine moves the camera (follow, smoothing, limits)
            val items = Renderer.collect(engine.tree)
            clipRect(ox, oy, ox + gameW * k, oy + gameH * k) {
                drawRect(Color(0xFF101820), Offset(ox, oy), Size(gameW * k, gameH * k))
                withTransform({
                    translate(ox + gameW * k / 2f, oy + gameH * k / 2f)
                    scale(k * zoom, k * zoom, Offset.Zero)
                    translate(-camX, -camY)
                }) { for (item in items) if (!item.node.isFixed()) drawNode(item, images, false) }
                // fixed nodes (score, on-screen buttons) sit on the screen and ignore the camera; they are drawn last, on top
                withTransform({ translate(ox, oy); scale(k, k, Offset.Zero) }) { for (item in items) if (item.node.isFixed()) drawNode(item, images, false) }
            }
        }
        if (showFps) BasicText("$fps FPS",
            style = TextStyle(color = Color(0xFF66FF66), fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

private fun mutableStateOfSize() = androidx.compose.runtime.mutableStateOf(IntSize.Zero)
