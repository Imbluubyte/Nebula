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
import nebula.core.Bars
import nebula.core.ButtonSkin
import nebula.core.NodeTypes
import nebula.core.SceneNode
import nebula.core.SceneTree
import nebula.core.TileGrid

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

    /** What a Sprite shows: the picture, how it is cut into cells, and the cell (0-based) to draw. */
    class SpriteView(val image: String, val cols: Int, val rows: Int, val cell: Int)

    /**
     * With an animation set the picture and grid come from the `.nanim` file; otherwise from the sprite's own
     * image / hframes / vframes. The `frame` property is 1-based. In the editor an animated sprite shows the first frame of its clip.
     */
    fun spriteView(n: SceneNode, images: ImageCache?, editor: Boolean): SpriteView {
        val path = n.s("animations")
        val anim = if (path.isNotEmpty()) images?.anims?.get(path) else null
        val image = if (anim != null && anim.sheet.isNotEmpty()) anim.sheet else n.s("image")
        val cols = (if (anim != null) anim.cols else n.f("hframes").toInt()).coerceAtLeast(1)
        val rows = (if (anim != null) anim.rows else n.f("vframes").toInt()).coerceAtLeast(1)
        var frame = n.f("frame").toInt()
        if (editor && anim != null) anim.clip(n.s("animation"))?.let { frame = it.from }
        return SpriteView(image, cols, rows, (frame - 1).coerceIn(0, cols * rows - 1))
    }

    /** Size in game units used for drawing and hit-testing. */
    fun sizeOf(n: SceneNode, images: ImageCache?): Pair<Double, Double> {
        val s = n.f("scale").let { if (it == 0.0) 1.0 else it }
        return when (n.type) {
            "Sprite" -> {
                val v = spriteView(n, images, true)
                val bmp = images?.get(v.image)
                val w = if (n.f("width") > 0) n.f("width") else if (bmp != null) bmp.width.toDouble() / v.cols else 64.0
                val h = if (n.f("height") > 0) n.f("height") else if (bmp != null) bmp.height.toDouble() / v.rows else 64.0
                w * s to h * s
            }
            "Shape", "Button", "Body", "SpawnableBody" -> n.f("width") * s to n.f("height") * s
            "DynamicBar" -> (Bars.emptySprite(n) ?: Bars.fullSprite(n))?.let { sizeOf(it, images) } ?: (40.0 to 40.0)    // as big as its empty bar
            "CollisionBox" -> n.f("width") to n.f("height")          // the engine ignores scale on hitboxes
            "TileMap" -> TileGrid.of(n).let { it.cols * n.f("tileWidth").coerceAtLeast(1.0) to it.rows * n.f("tileHeight").coerceAtLeast(1.0) }
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
                "Body", "SpawnableBody" -> {
                    val w = (n.f("width") * s).toFloat(); val h = (n.f("height") * s).toFloat()
                    val c = parseColor(n.s("color"), Color.Transparent)
                    if (c != Color.Transparent) drawRect(c, Offset(cx - w / 2, cy - h / 2), Size(w, h))
                    if (editor) drawRect(Color(0x8866BB6A), Offset(cx - w / 2, cy - h / 2), Size(w, h), style = Stroke(2f))
                }
                "Sprite" -> if (!ButtonSkin.hidden(n, editor)) {
                    val (w, h) = sizeOf(n, images)
                    val v = spriteView(n, images, editor)
                    val bmp = images?.get(v.image)
                    // the full-bar sprite of a DynamicBar is cut down to the bar's value, growing from one side
                    val fill = Bars.fillOf(n)
                    val r = fill?.first ?: 1.0
                    val dir = fill?.second ?: "right"
                    var fx = 0.0; var fy = 0.0; var fw = w; var fh = h       // the part of the sprite shown, in game units from its top left
                    when (dir) {
                        "left" -> { fw = w * r; fx = w - fw }
                        "up" -> { fh = h * r; fy = h - fh }
                        "down" -> fh = h * r
                        else -> fw = w * r
                    }
                    if (bmp != null) {
                        val flip = n.b("flipX")
                        val cw = (bmp.width / v.cols).coerceAtLeast(1); val ch = (bmp.height / v.rows).coerceAtLeast(1)
                        // the same part of the picture cell
                        val sx = Math.round(fx / w * cw).toInt(); val sy = Math.round(fy / h * ch).toInt()
                        val sw = Math.round(fw / w * cw).toInt().coerceAtMost(cw - sx); val sh = Math.round(fh / h * ch).toInt().coerceAtMost(ch - sy)
                        if (sw > 0 && sh > 0 && fw >= 1.0 && fh >= 1.0) scale(if (flip) -1f else 1f, 1f, Offset(cx, cy)) {
                            drawImage(bmp, srcOffset = androidx.compose.ui.unit.IntOffset((v.cell % v.cols) * cw + sx, (v.cell / v.cols) * ch + sy), srcSize = IntSize(sw, sh),
                                dstOffset = androidx.compose.ui.unit.IntOffset((cx - w / 2 + fx).toInt(), (cy - h / 2 + fy).toInt()), dstSize = IntSize(fw.toInt(), fh.toInt()))
                        }
                    } else if (editor) {
                        val o = Offset((cx - w / 2 + fx).toFloat(), (cy - h / 2 + fy).toFloat()); val sz = Size(fw.toFloat(), fh.toFloat())
                        drawRect(Color(0x33FFFFFF), o, sz)
                        drawRect(Color(0x99FFFFFF), o, sz, style = Stroke(2f))
                    }
                }
                // a DynamicBar looks like its two Sprite children; it only shows a marker in the editor until its script says which is which
                "DynamicBar" -> if (editor && Bars.emptySprite(n) == null && Bars.fullSprite(n) == null) marker(cx, cy)
                "TileMap" -> drawTiles(n, images, cx, cy, editor)
                "CollisionBox" -> if (editor) {         // a hitbox is never drawn in the running game
                    val w = n.f("width").toFloat(); val h = n.f("height").toFloat()
                    drawRect(Color(0x33FF7043), Offset(cx - w / 2, cy - h / 2), Size(w, h))
                    drawRect(Color(0xCCFF7043), Offset(cx - w / 2, cy - h / 2), Size(w, h), style = Stroke(2f))
                }
                "Label" -> text(n.s("text"), cx, cy, (n.f("fontSize") * s).toFloat(), parseColor(n.s("color"), Color.White), true)
                "Button" -> {
                    val w = (n.f("width") * s).toFloat(); val h = (n.f("height") * s).toFloat()
                    if (n.children.any { it.type == "Sprite" }) {
                        // a Button with Sprite children looks like them (they draw themselves); only the tap area is outlined in the editor
                        if (editor) drawRect(Color(0x66FFB74D), Offset(cx - w / 2, cy - h / 2), Size(w, h), style = Stroke(2f))
                    } else {
                        drawRoundRect(parseColor(n.s("color"), Color(0xFF3949AB)), Offset(cx - w / 2, cy - h / 2), Size(w, h), CornerRadius(14f))
                        text(n.s("text"), cx, cy, (n.f("fontSize") * s).toFloat(), Color.White, true)
                    }
                }
                else -> if (editor) marker(cx, cy)
            }
        }
    }

    /** The small cross-in-a-circle the editor shows for a node that has no picture. */
    private fun DrawScope.marker(cx: Float, cy: Float) {
        drawCircle(Color(0xAAB388FF), 10f, Offset(cx, cy), style = Stroke(3f))
        drawLine(Color(0xAAB388FF), Offset(cx - 16, cy), Offset(cx + 16, cy), 2f)
        drawLine(Color(0xAAB388FF), Offset(cx, cy - 16), Offset(cx, cy + 16), 2f)
    }

    /** A flat colour for a tile when the map has no picture (or the picture is missing), so maps work before any art exists. */
    fun tileColor(tile: Int): Color = Color.hsv(((tile * 47) % 360).toFloat(), 0.45f, 0.85f)

    /** Draws only the cells inside the visible area, so a big map costs about as much as a screenful. */
    private fun DrawScope.drawTiles(n: SceneNode, images: ImageCache?, cx: Float, cy: Float, editor: Boolean) {
        val g = TileGrid.of(n)
        val tw = n.f("tileWidth").coerceAtLeast(1.0).toFloat(); val th = n.f("tileHeight").coerceAtLeast(1.0).toFloat()
        val left = cx - g.cols * tw / 2f; val top = cy - g.rows * th / 2f
        val bmp = images?.get(n.s("image"))
        val hc = n.f("hframes").toInt().coerceAtLeast(1); val vc = n.f("vframes").toInt().coerceAtLeast(1)
        val clip = drawContext.canvas.nativeCanvas.clipBounds
        val c0 = Math.floor(((clip.left - left) / tw).toDouble()).toInt().coerceAtLeast(0)
        val c1 = Math.floor(((clip.right - left) / tw).toDouble()).toInt().coerceAtMost(g.cols - 1)
        val r0 = Math.floor(((clip.top - top) / th).toDouble()).toInt().coerceAtLeast(0)
        val r1 = Math.floor(((clip.bottom - top) / th).toDouble()).toInt().coerceAtMost(g.rows - 1)
        val cw = if (bmp != null) (bmp.width / hc).coerceAtLeast(1) else 1; val ch = if (bmp != null) (bmp.height / vc).coerceAtLeast(1) else 1
        for (r in r0..r1) for (c in c0..c1) {
            val t = g[c, r]; if (t == 0) continue
            val x0 = Math.round(left + c * tw); val y0 = Math.round(top + r * th)
            val x1 = Math.round(left + (c + 1) * tw); val y1 = Math.round(top + (r + 1) * th)
            val idx = t - 1
            if (bmp != null && idx < hc * vc) {
                drawImage(bmp, srcOffset = androidx.compose.ui.unit.IntOffset((idx % hc) * cw, (idx / hc) * ch), srcSize = IntSize(cw, ch),
                    dstOffset = androidx.compose.ui.unit.IntOffset(x0, y0), dstSize = IntSize(x1 - x0, y1 - y0))
            } else drawRect(tileColor(t), Offset(x0.toFloat(), y0.toFloat()), Size((x1 - x0).toFloat(), (y1 - y0).toFloat()))
        }
        if (editor) {   // outline of the map and a faint cell grid, so an empty map can still be found and painted
            val gridColor = Color(0x16FFFFFF)
            for (c in c0..c1 + 1) drawLine(gridColor, Offset(left + c * tw, top), Offset(left + c * tw, top + g.rows * th), 1f)
            for (r in r0..r1 + 1) drawLine(gridColor, Offset(left, top + r * th), Offset(left + g.cols * tw, top + r * th), 1f)
            drawRect(Color(0x66B388FF), Offset(left, top), Size(g.cols * tw, g.rows * th), style = Stroke(2f))
        }
    }

    fun DrawScope.text(t: String, cx: Float, cy: Float, size: Float, color: Color, center: Boolean) {
        textPaint.textSize = size; textPaint.color = color.toArgb()
        textPaint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(t, cx, cy + size * 0.35f, textPaint)
    }
}
