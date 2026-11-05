package com.nebula.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebula.studio.Studio
import com.nebula.studio.runtime.Renderer
import nebula.core.SceneNode

/** Shown over the scene view while a TileMap is being painted: the three tools and the tiles of its picture to choose from. */
@Composable
fun TilePalette(studio: Studio, node: SceneNode, modifier: Modifier = Modifier) {
    val cols = node.f("hframes").toInt().coerceAtLeast(1); val rows = node.f("vframes").toInt().coerceAtLeast(1)
    val bmp = studio.images?.get(node.s("image"))
    Column(modifier.widthIn(max = 560.dp).background(C.panel.copy(alpha = 0.94f), RoundedCornerShape(12.dp)).padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in listOf("paint", "erase", "fill")) TextBtn(t, { studio.tileTool = t }, primary = studio.tileTool == t)
            Text("tile ${studio.tileBrush}", color = C.dim, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 6.dp))
            TextBtn("Done", { studio.paintPath = null })
        }
        if (cols * rows > 1 || bmp != null) {
            LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(cols * rows) { i ->
                    val on = studio.tileBrush == i + 1
                    Box(Modifier.size(48.dp).background(Color(0xFF0B0C18)).border(if (on) 3.dp else 1.dp, if (on) C.accent2 else C.line)
                        .clickable { studio.tileBrush = i + 1; if (studio.tileTool == "erase") studio.tileTool = "paint" }) {
                        Canvas(Modifier.fillMaxSize()) {
                            if (bmp != null) {
                                val cw = (bmp.width / cols).coerceAtLeast(1); val ch = (bmp.height / rows).coerceAtLeast(1)
                                drawImage(bmp, srcOffset = IntOffset((i % cols) * cw, (i / cols) * ch), srcSize = IntSize(cw, ch), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                            } else drawRect(Renderer.tileColor(i + 1), Offset.Zero, Size(size.width, size.height))
                        }
                        Text("${i + 1}", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
                    }
                }
            }
        } else {
            Text("Choose an image and set hframes / vframes in the inspector to cut it into tiles. Until then each tile number draws as a coloured square; pick one below.",
                color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in 1..6) TextBtn("$n", { studio.tileBrush = n; if (studio.tileTool == "erase") studio.tileTool = "paint" }, primary = studio.tileBrush == n)
            }
        }
    }
}
