package com.nebula.studio.build

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites strings in a compiled (binary) AndroidManifest.xml: the package id and the app label.
 * Only the string pool is rebuilt; every other chunk is copied untouched, and string indexes keep their order.
 */
object AxmlPatcher {
    fun patch(data: ByteArray, replaceExact: Map<String, String>): ByteArray {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getShort(0).toInt() == 0x0003) { "not a binary XML file" }
        val sp = 8
        require(b.getShort(sp).toInt() == 0x0001) { "string pool not found" }
        val headerSize = b.getShort(sp + 2).toInt() and 0xFFFF
        val poolSize = b.getInt(sp + 4)
        val count = b.getInt(sp + 8); val styleCount = b.getInt(sp + 12)
        val flags = b.getInt(sp + 16); val stringsStart = b.getInt(sp + 20)
        require(styleCount == 0) { "manifest has styled strings" }
        val utf8 = (flags and 0x100) != 0

        val strings = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = b.getInt(sp + headerSize + i * 4)
            var p = sp + stringsStart + off
            if (utf8) {
                fun len8(): Int { var l = b.get(p++).toInt() and 0xFF; if (l and 0x80 != 0) l = ((l and 0x7F) shl 8) or (b.get(p++).toInt() and 0xFF); return l }
                len8(); val bytes = len8()
                strings.add(String(data, p, bytes, Charsets.UTF_8))
            } else {
                var l = b.getShort(p).toInt() and 0xFFFF; p += 2
                if (l and 0x8000 != 0) { l = ((l and 0x7FFF) shl 16) or (b.getShort(p).toInt() and 0xFFFF); p += 2 }
                strings.add(String(data, p, l * 2, Charsets.UTF_16LE))
            }
        }
        for (i in strings.indices) replaceExact[strings[i]]?.let { strings[i] = it }

        val body = ByteArrayOutputStream(); val offsets = IntArray(count)
        for (i in 0 until count) {
            offsets[i] = body.size()
            val s = strings[i]
            if (utf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                fun len8(l: Int) { if (l > 0x7F) { body.write((l shr 8) or 0x80); body.write(l and 0xFF) } else body.write(l) }
                len8(s.length); len8(bytes.size); body.write(bytes); body.write(0)
            } else {
                if (s.length > 0x7FFF) { body.write(((s.length shr 16) or 0x8000) and 0xFF); body.write(((s.length shr 16) or 0x8000) shr 8); body.write(s.length and 0xFF); body.write((s.length shr 8) and 0xFF) }
                else { body.write(s.length and 0xFF); body.write(s.length shr 8) }
                body.write(s.toByteArray(Charsets.UTF_16LE)); body.write(0); body.write(0)
            }
        }
        while (body.size() % 4 != 0) body.write(0)
        val newStringsStart = headerSize + count * 4
        val newPoolSize = newStringsStart + body.size()

        val out = ByteBuffer.allocate(8 + newPoolSize + (data.size - sp - poolSize)).order(ByteOrder.LITTLE_ENDIAN)
        out.put(data, 0, 8)
        out.put(data, sp, headerSize)                      // pool header, then patch sizes
        out.putInt(sp + 4, newPoolSize); out.putInt(sp + 20, newStringsStart)
        for (o in offsets) out.putInt(o)
        out.put(body.toByteArray())
        out.put(data, sp + poolSize, data.size - sp - poolSize)
        out.putInt(4, out.position())
        return out.array()
    }
}
