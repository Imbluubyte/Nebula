package nebula.core

/**
 * The .nscn text format (indent = nesting, one tab per level):
 *
 *   scene "Main"
 *   node World Node2D
 *   	node Player Body script "player.nbs" tags "hero"
 *   		x = 100
 *   		tweak speed = 220
 *   	node Enemy1 scene "enemy.nscn"
 *
 * Property lines (`key = value`) belong to the node one level up. Values: numbers, "text", true/false.
 */
data class NodeDef(
    var name: String,
    var type: String,
    var script: String? = null,
    var instance: String? = null,
    val tags: MutableList<String> = ArrayList(),
    val props: MutableMap<String, Any> = LinkedHashMap(),
    val tweaks: MutableMap<String, Any> = LinkedHashMap(),
    val children: MutableList<NodeDef> = ArrayList(),
)

data class SceneDef(var title: String, var root: NodeDef)

class SceneParseException(val line: Int, message: String) : Exception("line $line: $message")

object SceneFile {
    fun parse(text: String): SceneDef {
        var title = "Scene"
        var root: NodeDef? = null
        val stack = ArrayList<NodeDef>()   // stack[depth] = node at that depth
        var lineNo = 0
        for (raw in text.lines()) {
            lineNo++
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val depth = raw.takeWhile { it == '\t' }.length
            val line = raw.trim()
            val tok = tokenize(line, lineNo)
            when (tok[0]) {
                "scene" -> { if (depth == 0 && tok.size >= 2) title = tok[1] else throw SceneParseException(lineNo, "expected: scene \"Title\"") }
                "node" -> {
                    if (tok.size < 3 && !(tok.size == 4 && tok[2] == "scene")) throw SceneParseException(lineNo, "expected: node Name Type")
                    val def = if (tok[2] == "scene") NodeDef(tok[1], "Node2D", instance = tok.getOrNull(3))
                              else NodeDef(tok[1], tok[2]).also {
                                  if (!NodeTypes.exists(it.type)) throw SceneParseException(lineNo, "unknown node type '${it.type}'")
                              }
                    var i = if (tok[2] == "scene") 4 else 3
                    while (i < tok.size) {
                        when (tok[i]) {
                            "script" -> { def.script = tok.getOrNull(i + 1); i += 2 }
                            "scene" -> { def.instance = tok.getOrNull(i + 1); i += 2 }
                            "tags" -> { i++; while (i < tok.size && tok[i] !in listOf("script", "scene")) def.tags.add(tok[i++]) }
                            else -> throw SceneParseException(lineNo, "unexpected '${tok[i]}'")
                        }
                    }
                    while (stack.size > depth) stack.removeAt(stack.size - 1)
                    if (depth == 0) { if (root != null) throw SceneParseException(lineNo, "only one root node") ; root = def }
                    else {
                        if (stack.size != depth) throw SceneParseException(lineNo, "bad indentation")
                        stack.last().children.add(def)
                    }
                    stack.add(def)
                }
                else -> {
                    // property line: key = value   |   tweak key = value
                    val eq = line.indexOf('=')
                    if (eq < 0 || stack.isEmpty()) throw SceneParseException(lineNo, "expected 'key = value'")
                    var key = line.substring(0, eq).trim()
                    val value = parseValue(line.substring(eq + 1).trim(), lineNo)
                    val owner = stack.getOrNull(depth - 1) ?: throw SceneParseException(lineNo, "property outside a node")
                    if (key.startsWith("tweak ")) { key = key.removePrefix("tweak ").trim(); owner.tweaks[key] = value }
                    else owner.props[key] = value
                }
            }
        }
        return SceneDef(title, root ?: throw SceneParseException(lineNo, "scene has no nodes"))
    }

    private fun tokenize(line: String, lineNo: Int): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == ' ' || c == '\t') { i++; continue }
            if (c == '"') {
                val sb = StringBuilder(); i++
                while (i < line.length && line[i] != '"') {
                    if (line[i] == '\\' && i + 1 < line.length) { i++; sb.append(when (line[i]) { 'n' -> '\n'; 't' -> '\t'; else -> line[i] }) } else sb.append(line[i])
                    i++
                }
                if (i >= line.length) throw SceneParseException(lineNo, "unterminated string")
                i++; out.add(sb.toString())
            } else {
                val st = i
                while (i < line.length && line[i] != ' ' && line[i] != '\t') i++
                out.add(line.substring(st, i))
                if (out.last() == "=" ) break
            }
        }
        return out
    }

    private fun parseValue(v: String, lineNo: Int): Any = when {
        v == "true" -> true
        v == "false" -> false
        v.startsWith("\"") -> tokenize(v, lineNo).first()
        else -> v.toDoubleOrNull() ?: throw SceneParseException(lineNo, "bad value '$v'")
    }

    private fun fmt(v: Any): String = when (v) {
        is Boolean -> v.toString()
        is Number -> { val d = v.toDouble(); if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString() }
        else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }

    fun write(scene: SceneDef): String {
        val sb = StringBuilder()
        sb.append("scene ").append(fmt(scene.title)).append('\n')
        fun node(n: NodeDef, depth: Int) {
            val ind = "\t".repeat(depth)
            sb.append(ind).append("node ").append(n.name).append(' ')
            if (n.instance != null) sb.append("scene ").append(fmt(n.instance!!)) else sb.append(n.type)
            if (n.script != null) sb.append(" script ").append(fmt(n.script!!))
            if (n.tags.isNotEmpty()) sb.append(" tags ").append(n.tags.joinToString(" ") { fmt(it) })
            sb.append('\n')
            for ((k, v) in n.props) sb.append(ind).append('\t').append(k).append(" = ").append(fmt(v)).append('\n')
            for ((k, v) in n.tweaks) sb.append(ind).append('\t').append("tweak ").append(k).append(" = ").append(fmt(v)).append('\n')
            for (c in n.children) node(c, depth + 1)
        }
        node(scene.root, 0)
        return sb.toString()
    }

    /** Snapshot of a live tree back into a definition (used by the editor's Save). */
    fun fromTree(title: String, n: SceneNode): SceneDef = SceneDef(title, defOf(n))

    private fun defOf(n: SceneNode): NodeDef {
        val d = NodeDef(n.name, n.type, n.script, n.instanceOf)
        d.tags.addAll(n.tags)
        val defaults = NodeTypes.propsOf(n.type).associate { it.name to it.default }
        for ((k, v) in n.props) if (defaults[k] != v) d.props[k] = v
        d.tweaks.putAll(n.tweaks)
        if (n.instanceOf == null) for (c in n.children) d.children.add(defOf(c))
        return d
    }
}
