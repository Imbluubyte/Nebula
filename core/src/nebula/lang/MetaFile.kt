package nebula.lang

/** Plain-text script metadata shipped next to game.sn / libscripts.so (read by the engine at run time). */
object MetaFile {
    fun write(metas: List<ScriptMeta>): String {
        val sb = StringBuilder("# nebula script metadata v1\n")
        for (m in metas) {
            sb.append("script ${m.name} ${m.base} ${m.file} frame=${if (m.frame) 1 else 0} ticks=${m.ticks.joinToString(",")} every=${m.everyMs.joinToString(",")}\n")
            for (t in m.tweaks) sb.append("tweak ${m.name} ${t.name} ${t.kind} ${t.default}\n")
            for (b in m.broadcasts) sb.append("bc ${m.name} ${b.name} ${b.params.joinToString(",") { it.first + ":" + it.second }}\n")
            if (m.barEmpty.isNotEmpty()) sb.append("bar ${m.name} empty ${m.barEmpty}\n")
            if (m.barFull.isNotEmpty()) sb.append("bar ${m.name} full ${m.barFull}\n")
        }
        return sb.toString()
    }

    fun read(text: String): Map<String, ScriptMeta> {
        class B(val name: String, val base: String, val file: String, val frame: Boolean, val ticks: List<Int>, val every: List<Int>) {
            val tweaks = ArrayList<TweakMeta>(); val bcs = ArrayList<BroadcastMeta>()
            var barEmpty = ""; var barFull = ""
        }
        val map = LinkedHashMap<String, B>()
        fun nums(v: String) = if (v.isEmpty()) emptyList() else v.split(',').map { it.toInt() }
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.split(' ')
            when (p[0]) {
                "script" -> {
                    val kv = p.drop(4).associate { it.substringBefore('=') to it.substringAfter('=', "") }
                    map[p[1]] = B(p[1], p[2], p[3], kv["frame"] == "1", nums(kv["ticks"] ?: ""), nums(kv["every"] ?: ""))
                }
                "tweak" -> map[p[1]]?.tweaks?.add(TweakMeta(p[2], p[3], p.drop(4).joinToString(" ")))
                "bc" -> map[p[1]]?.bcs?.add(BroadcastMeta(p[2], if (p.size < 4 || p[3].isEmpty()) emptyList() else p[3].split(',').map { it.substringBefore(':') to it.substringAfter(':') }))
                "bar" -> map[p[1]]?.let { if (p[2] == "empty") it.barEmpty = p[3] else it.barFull = p[3] }
            }
        }
        return map.mapValues { (_, b) -> ScriptMeta(b.name, b.base, b.file, b.tweaks, b.ticks, b.every, b.frame, b.bcs, b.barEmpty, b.barFull) }
    }
}
