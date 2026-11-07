package com.nebula.studio.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import nebula.core.Argb
import nebula.core.VShape

/** A shape colour as drawn: [alpha] scales it, and a non-zero [tint] mixes it towards that colour (onion skin frames). */
private fun paintColor(c: Int, tint: Int, alpha: Float): Color {
    if (Argb.a(c) == 0) return Color.Transparent
    if (tint == 0) { val k = Color(c); return k.copy(alpha = (k.alpha * alpha).coerceIn(0f, 1f)) }
    return Color(Argb.make(Math.round(Argb.a(c) * alpha), (Argb.r(c) + Argb.r(tint)) / 2, (Argb.g(c) + Argb.g(tint)) / 2, (Argb.b(c) + Argb.b(tint)) / 2))
}

/**
 * Draws vector shapes in picture units (the caller scales the scope to the screen or leaves it at one unit per pixel). The same code
 * draws the editor canvas, the frame thumbnails and the PNG that the game uses, so they always look alike. Shapes whose position is in
 * [dim] are drawn faintly (the eraser is about to remove them).
 */
fun DrawScope.drawShapes(shapes: List<VShape>, dim: Set<Int> = emptySet(), tint: Int = 0, alpha: Float = 1f) {
    for ((i, s) in shapes.withIndex()) {
        val a = if (i in dim) alpha * 0.25f else alpha
        val stroke = paintColor(s.stroke, tint, a); val fill = paintColor(s.fill, tint, a)
        val line = Stroke(width = s.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = s.pts
        when (s.kind) {
            VShape.Kind.Rect -> {
                val topLeft = Offset(p[0], p[1]); val box = Size(p[2] - p[0], p[3] - p[1])
                if (s.fill != 0) drawRect(fill, topLeft, box)
                if (s.stroke != 0) drawRect(stroke, topLeft, box, style = line)
            }
            VShape.Kind.Ellipse -> {
                val topLeft = Offset(p[0], p[1]); val box = Size(p[2] - p[0], p[3] - p[1])
                if (s.fill != 0) drawOval(fill, topLeft, box)
                if (s.stroke != 0) drawOval(stroke, topLeft, box, style = line)
            }
            VShape.Kind.Line -> if (s.stroke != 0) drawLine(stroke, Offset(p[0], p[1]), Offset(p[2], p[3]), strokeWidth = s.width, cap = StrokeCap.Round)
            VShape.Kind.Path -> {
                if (p.size == 2) {
                    if (s.stroke != 0) drawCircle(stroke, s.width / 2f, Offset(p[0], p[1]))
                } else {
                    val path = Path()
                    path.moveTo(p[0], p[1])
                    var k = 2
                    while (k + 1 < p.size) { path.lineTo(p[k], p[k + 1]); k += 2 }
                    if (s.fill != 0 && p.size >= 6) drawPath(path, fill)
                    if (s.stroke != 0) drawPath(path, stroke, style = line)
                }
            }
        }
    }
}

/** Turns vector frames into pixels, for the PNG sheet the game reads. */
object VectorRender {
    /** The ARGB pixels of [shapes] drawn on a transparent [w] x [h] picture. */
    fun rasterize(shapes: List<VShape>, w: Int, h: Int): IntArray {
        val image = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) { drawShapes(shapes) }
        val px = IntArray(w * h)
        image.asAndroidBitmap().getPixels(px, 0, w, 0, 0, w, h)
        return px
    }
}
