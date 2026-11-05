package nebula.core

/**
 * The cells of a TileMap. Cell values are tile numbers: 0 is empty, 1.. count the cells of the tileset picture
 * left to right, top to bottom (the same numbering as Sprite frames). Cell coordinates here are 0-based;
 * scripts use 1-based columns and rows like everything else in SuperNova.
 *
 * Stored in a scene as one string: rows joined by ';', cells by ',' (`1,1,1;;0,2`). Trailing empty cells and rows are left out,
 * and anything missing reads as empty, so changing `columns` or `rows` never loses or shifts the painted cells.
 */
class TileGrid(val cols: Int, val rows: Int) {
    val cells = IntArray(cols * rows)

    operator fun get(c: Int, r: Int): Int = if (c in 0 until cols && r in 0 until rows) cells[r * cols + c] else 0
    operator fun set(c: Int, r: Int, v: Int) { if (c in 0 until cols && r in 0 until rows) cells[r * cols + c] = v.coerceAtLeast(0) }
    fun fill(v: Int) { cells.fill(v.coerceAtLeast(0)) }

    /** Replaces the connected area of equal cells that contains (c, r). Returns whether anything changed. */
    fun floodFill(c: Int, r: Int, v: Int): Boolean {
        if (c !in 0 until cols || r !in 0 until rows) return false
        val target = this[c, r]; val value = v.coerceAtLeast(0)
        if (target == value) return false
        val stack = ArrayList<Int>(); stack.add(r * cols + c)
        while (stack.isNotEmpty()) {
            val i = stack.removeAt(stack.size - 1)
            if (cells[i] != target) continue
            cells[i] = value
            val x = i % cols; val y = i / cols
            if (x > 0) stack.add(i - 1); if (x < cols - 1) stack.add(i + 1)
            if (y > 0) stack.add(i - cols); if (y < rows - 1) stack.add(i + cols)
        }
        return true
    }

    fun toText(): String {
        val lines = ArrayList<String>(rows)
        for (r in 0 until rows) {
            var last = cols - 1
            while (last >= 0 && cells[r * cols + last] == 0) last--
            val sb = StringBuilder()
            for (c in 0..last) { if (c > 0) sb.append(','); sb.append(cells[r * cols + c]) }
            lines.add(sb.toString())
        }
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return lines.joinToString(";")
    }

    companion object {
        /** Upper bound on cells in one map, so a typo in `columns` cannot exhaust memory. */
        const val MAX_CELLS = 1_000_000

        fun parse(text: String, cols: Int, rows: Int): TileGrid {
            val g = TileGrid(cols, rows)
            if (text.isEmpty()) return g
            var r = 0
            for (line in text.split(';')) {
                if (r >= rows) break
                if (line.isNotEmpty()) {
                    var c = 0
                    for (cell in line.split(',')) { if (c >= cols) break; g.cells[r * cols + c] = cell.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0; c++ }
                }
                r++
            }
            return g
        }

        /** The grid of a TileMap node, parsed once and kept on the node until its `tiles` string or size changes. */
        fun of(n: SceneNode): TileGrid {
            val cols = n.f("columns").toInt().coerceIn(1, 4096)
            val rows = n.f("rows").toInt().coerceIn(1, maxOf(1, MAX_CELLS / cols))
            val src = n.s("tiles")
            val cached = n.tileCache
            if (cached != null && cached.cols == cols && cached.rows == rows && (n.tileDirty || n.tileSrc == src)) return cached
            val g = parse(src, cols, rows)
            n.tileCache = g; n.tileSrc = src; n.tileDirty = false
            return g
        }

        /** Script edits go to the cache first (cheap); this copies them into the node's `tiles` string. Called once per frame and before reading `tiles`. */
        fun sync(n: SceneNode) {
            if (!n.tileDirty) return
            val g = n.tileCache ?: return
            val text = g.toText()
            n.props["tiles"] = text; n.tileSrc = text; n.tileDirty = false
        }

        /** The 0-based cell under a world point, or null outside the map. */
        fun cellAt(n: SceneNode, wx: Double, wy: Double): Pair<Int, Int>? {
            val g = of(n); val tw = n.f("tileWidth").coerceAtLeast(1.0); val th = n.f("tileHeight").coerceAtLeast(1.0)
            val (x, y) = n.worldPos()
            val c = Math.floor((wx - (x - g.cols * tw / 2)) / tw).toInt(); val r = Math.floor((wy - (y - g.rows * th / 2)) / th).toInt()
            return if (c in 0 until g.cols && r in 0 until g.rows) c to r else null
        }

        /** The editor's tile tools: "paint" puts [tile] in the cell, "erase" empties it, "fill" floods the connected area. Writes the `tiles` string straight away. */
        fun apply(n: SceneNode, tool: String, c: Int, r: Int, tile: Int): Boolean {
            val g = of(n)
            val changed = when (tool) {
                "erase" -> (g[c, r] != 0).also { if (it) g[c, r] = 0 }
                "fill" -> g.floodFill(c, r, tile)
                else -> (g[c, r] != tile).also { if (it) g[c, r] = tile }
            }
            if (changed) { val text = g.toText(); n.props["tiles"] = text; n.tileSrc = text; n.tileDirty = false }
            return changed
        }

        /** Tile numbers that stop bodies: `solidTiles` is a list like "1, 2, 7"; empty means every painted tile. */
        fun solidSet(n: SceneNode): Set<Int>? {
            val text = n.s("solidTiles").trim()
            if (text.isEmpty()) return null
            return text.split(Regex("[,;\\s]+")).mapNotNull { it.toIntOrNull() }.toSet()
        }
    }
}
