package com.nebula.studio.build

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Minimal ZIP writer that 4-byte-aligns stored entries (Android requires that for resources.arsc). */
class AlignedZip(private val out: OutputStream) {
    private class Entry(val name: ByteArray, val method: Int, val crc: Long, val csize: Int, val usize: Int, val offset: Long)
    private val entries = ArrayList<Entry>()
    private var pos = 0L

    private fun w(b: ByteArray) { out.write(b); pos += b.size }
    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun u32(v: Long) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    fun add(name: String, data: ByteArray, store: Boolean) {
        val nameB = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().also { it.update(data) }.value
        val body: ByteArray; val method: Int
        if (store) { body = data; method = 0 } else {
            val d = Deflater(6, true); d.setInput(data); d.finish()
            val bos = ByteArrayOutputStream(); val buf = ByteArray(32768)
            while (!d.finished()) { val n = d.deflate(buf); bos.write(buf, 0, n) }
            d.end(); body = bos.toByteArray(); method = 8
        }
        val headerEnd = pos + 30 + nameB.size
        val pad = if (store) ((4 - (headerEnd % 4)) % 4).toInt() else 0
        val offset = pos
        w(u32(0x04034b50)); w(u16(20)); w(u16(0x0800)); w(u16(method)); w(u16(0)); w(u16(0x21))
        w(u32(crc)); w(u32(body.size.toLong())); w(u32(data.size.toLong())); w(u16(nameB.size)); w(u16(pad))
        w(nameB); if (pad > 0) w(ByteArray(pad)); w(body)
        entries.add(Entry(nameB, method, crc, body.size, data.size, offset))
    }

    fun finish() {
        val cdStart = pos
        for (e in entries) {
            w(u32(0x02014b50)); w(u16(20)); w(u16(20)); w(u16(0x0800)); w(u16(e.method)); w(u16(0)); w(u16(0x21))
            w(u32(e.crc)); w(u32(e.csize.toLong())); w(u32(e.usize.toLong())); w(u16(e.name.size)); w(u16(0)); w(u16(0))
            w(u16(0)); w(u16(0)); w(u32(0)); w(u32(e.offset)); w(e.name)
        }
        val cdSize = pos - cdStart
        w(u32(0x06054b50)); w(u16(0)); w(u16(0)); w(u16(entries.size)); w(u16(entries.size)); w(u32(cdSize)); w(u32(cdStart)); w(u16(0))
        out.flush()
    }
}
