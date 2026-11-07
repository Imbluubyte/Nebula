package nebula.core

/**
 * Markdown files (.md) open in the script editor like any other text file. This only knows which files are Markdown and which
 * parts of the text to colour (the editor turns [Span]s into styles); nothing is rendered. Android-free.
 */
object Markdown {
    enum class Kind { HEADING, CODE, BOLD, LINK, MARKER }
    class Span(val kind: Kind, val from: Int, val to: Int)

    fun isMarkdown(path: String): Boolean { val l = path.lowercase(); return l.endsWith(".md") || l.endsWith(".markdown") }

    private val heading = Regex("^#{1,6}(\\s|$)")
    private val list = Regex("^(?:[-*+]|\\d{1,9}[.)])\\s")
    private val rule = Regex("^([-*_])(\\s*\\1){2,}\\s*$")
    private val inline = Regex("""(`[^`\n]+`)|(\*\*[^*\n]+\*\*|__[^_\n]+__)|(\[[^\]\n]*\]\([^)\n]*\))""")

    /** Spans to colour, in text order: headings, fenced and inline code, bold, links, and list / quote / rule markers. */
    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        var fenced = false
        var pos = 0
        for (line in text.split('\n')) {
            val end = pos + line.length
            val t = line.trimStart(); val lead = line.length - t.length
            when {
                t.startsWith("```") || t.startsWith("~~~") -> { out.add(Span(Kind.CODE, pos, end)); fenced = !fenced }
                fenced -> out.add(Span(Kind.CODE, pos, end))
                lead < 4 && heading.containsMatchIn(t) -> out.add(Span(Kind.HEADING, pos, end))
                lead < 4 && rule.matches(t.trimEnd()) -> out.add(Span(Kind.MARKER, pos, end))
                else -> {
                    var from = pos + lead
                    if (lead < 4 && t.startsWith(">")) { out.add(Span(Kind.MARKER, from, from + 1)); from++ }
                    else list.find(t)?.takeIf { lead < 4 }?.let { out.add(Span(Kind.MARKER, from, from + it.value.length - 1)) }
                    for (m in inline.findAll(line)) {
                        val kind = if (m.groups[1] != null) Kind.CODE else if (m.groups[2] != null) Kind.BOLD else Kind.LINK
                        out.add(Span(kind, pos + m.range.first, pos + m.range.last + 1))
                    }
                }
            }
            pos = end + 1
        }
        return out
    }
}
