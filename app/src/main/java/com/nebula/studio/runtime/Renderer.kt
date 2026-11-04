package com.nebula.studio.runtime

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import nebula.core.NodeTypes
import nebula.core.SceneNode
import nebula.core.SceneTree

/** One node ready to draw: its world position and drawing order. */
class DrawItem(val node: SceneNode, val x: Double, val y: Double, val order: Int)

object Renderer {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }

    fun parseColor(hex: String, fallback: Color): Color =
        try { if (hex.isEmpty()) fallback else Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { fallback }

    /** Visible Node2D nodes with world positions, sorted by layer then tree order. */
    fun collect(tree: SceneTree): List<DrawItem> {
        val out = ArrayList<DrawItem>()
        fun walk(n: SceneNode, ox: Double, oy: Double) {
            val is2d = NodeTypes.isA(n.type, "Node2D")
            if (is2d && !n.b("visible")) return
            val x = if (is2d) ox + n.f("x") else ox
            val y = if (is2d) oy + n.f("y") else oy
            if (is2d) out.add(DrawItem(n, x, y, out.size))
            for (c in n.children) walk(c, x, y)
        }
        tree.root?.let { walk(it, 0.0, 0.0) }
        return out.sortedWith(compareBy<DrawItem> { it.node.f("layer") }.thenBy { it.order })
    }

    /** Size in game units used for drawing and hit-testing. */
    fun sizeOf(n: SceneNode, images: ImageCache?): Pair<Double, Double> {
        val s = n.f("scale").let { if (it == 0.0) 1.0 else it }
        return when (n.type) {
            "Sprite" -> {
                val bmp = images?.get(n.s("image"))
                val w = if (n.f("width") > 0) n.f("width") else bmp?.width?.toDouble() ?: 64.0
                val h = if (n.f("height") > 0) n.f("height") else bmp?.height?.toDouble() ?: 64.0
                w * s to h * s
            }
            "Shape", "Button", "Body" -> n.f("width") * s to n.f("height") * s
            "Label" -> (n.s("text").length * n.f("fontSize") * 0.55).coerceAtLeast(24.0) * s to n.f("fontSize") * 1.3 * s
            else -> 40.0 to 40.0
        }
    }

    /** Draws one node centred on (x, y). [editor] adds markers for nodes that have no picture. */
    fun DrawScope.drawNode(item: DrawItem, images: ImageCache?, editor: Boolean) {
        val n = item.node
        val cx = item.x.toFloat(); val cy = item.y.toFloat()
        val s = n.f("scale").let { if (it == 0.0) 1f else it.toFloat() }
        rotate(n.f("rotation").toFloat(), Offset(cx, cy)) {
            when (n.type) {
                "Shape" -> {
                    val w = (n.f("width") * s).toFloat(); val h = (n.f("height") * s).toFloat()
                    val c = parseColor(n.s("color"), Color(0xFF4FC3F7))
                    if (n.s("shape") == "circle") drawOval(c, Offset(cx - w / 2, cy - h / 2), Size(w, h))
                    else drawRect(c, Offset(cx - w / 2, cy - h / 2), Size(w, h))
                }
                "Body" -> {
                    val w = (n.f("width") * s).toFloat(); val h = (n.f("height") * s).toFloat()
                    val c = parseColor(n.s("color"), Color.Transparent)
                    if (c != Color.Transparent) drawRect(c, Offset(cx - w / 2, cy - h / 2), Size(w, h))
                    if (editor) drawRect(Color(0x8866BB6A), Offset(cx - w / 2, cy - h / 2), Size(w, h), style = Stroke(2f))
                }
                "Sprite" -> {
                    val (w, h) = sizeOf(n, images)
                    val bmp = images?.get(n.s("image"))
                    if (bmp != null) {
                        val flip = n.b("flipX")
                        scale(if (flip) -1f else 1f, 1f, Offset(cx, cy)) {
                            drawImage(bmp, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                                dstOffset = androidx.compose.ui.unit.IntOffset((cx - w / 2).toInt(), (cy - h / 2).toInt()), dstSize = IntSize(w.toInt(), h.toInt()))
                        }
                    } else if (editor) {
                        drawRect(Color(0x33FFFFFF), Offset((cx - w / 2).toFloat(), (cy - h / 2).toFloat()), Size(w.toFloat(), h.toFloat()))
                        drawRect(Color(0x99FFFFFF), Offset((cx - w / 2).toFloat(), (cy - h / 2).toFloat()), Size(w.toFloat(), h.toFloat()), style = Stroke(2f))
                    }
                }
                "Label" -> text(n.s("text"), cx, cy, (n.f("fontSize") * s).toFloat(), parseColor(n.s("color"), Color.White), true)
                "Button" -> {
                    val w = (n.f("width") * s).toFloat(); val h = (n.f("height") * s).toFloat()
                    drawRoundRect(parseColor(n.s("color"), Color(0xFF3949AB)), Offset(cx - w / 2, cy - h / 2), Size(w, h), CornerRadius(14f))
                    text(n.s("text"), cx, cy, (n.f("fontSize") * s).toFloat(), Color.White, true)
                }
                else -> if (editor) {
                    drawCircle(Color(0xAAB388FF), 10f, Offset(cx, cy), style = Stroke(3f))
                    drawLine(Color(0xAAB388FF), Offset(cx - 16, cy), Offset(cx + 16, cy), 2f)
                    drawLine(Color(0xAAB388FF), Offset(cx, cy - 16), Offset(cx, cy + 16), 2f)
                }
            }
        }
    }

    fun DrawScope.text(t: String, cx: Float, cy: Float, size: Float, color: Color, center: Boolean) {
        textPaint.textSize = size; textPaint.color = color.toArgb()
        textPaint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(t, cx, cy + size * 0.35f, textPaint)
    }
}
