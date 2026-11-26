package com.zenread.data.fonts

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.Deflater

/** Builds minimal font containers for tests: enough structure for [FontInspector], no glyphs. */
object TestFonts {
    data class Name(val platform: Int, val encoding: Int, val language: Int, val nameId: Int, val value: String)

    fun windows(nameId: Int, value: String, language: Int = 0x0409) = Name(3, 1, language, nameId, value)

    fun nameTable(names: List<Name>): ByteArray {
        val strings = ByteArrayOutputStream()
        val records = ByteArrayOutputStream()
        val r = DataOutputStream(records)
        for (name in names) {
            val bytes = if (name.platform == 1) name.value.toByteArray(Charsets.ISO_8859_1) else name.value.toByteArray(Charsets.UTF_16BE)
            r.writeShort(name.platform)
            r.writeShort(name.encoding)
            r.writeShort(name.language)
            r.writeShort(name.nameId)
            r.writeShort(bytes.size)
            r.writeShort(strings.size())
            strings.write(bytes)
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeShort(0)
            writeShort(names.size)
            writeShort(6 + names.size * 12)
        }
        out.write(records.toByteArray())
        out.write(strings.toByteArray())
        return out.toByteArray()
    }

    /** An sfnt with `cmap`, `head` and the given extra tables. [magic] picks TrueType or CFF. */
    fun sfnt(
        tables: Map<String, ByteArray>,
        magic: ByteArray = byteArrayOf(0, 1, 0, 0),
    ): ByteArray {
        val all = (mapOf("cmap" to ByteArray(4), "head" to ByteArray(54)) + tables).toSortedMap()
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write(magic)
        d.writeShort(all.size)
        d.writeShort(0)
        d.writeShort(0)
        d.writeShort(0)
        var offset = 12 + all.size * 16
        for ((tag, data) in all) {
            d.write(tag.toByteArray(Charsets.ISO_8859_1))
            d.writeInt(0)
            d.writeInt(offset)
            d.writeInt(data.size)
            offset += data.size.padded()
        }
        for (data in all.values) {
            d.write(data)
            d.write(ByteArray(data.size.padded() - data.size))
        }
        return out.toByteArray()
    }

    /** A WOFF 1.0 file; tables are zlib-compressed when that makes them smaller, like real encoders. */
    fun woff(tables: Map<String, ByteArray>): ByteArray {
        val all = (mapOf("cmap" to ByteArray(4), "head" to ByteArray(54)) + tables).toSortedMap()
        val stored = all.mapValues { (_, data) ->
            val packed = deflate(data)
            if (packed.size < data.size) packed else data
        }
        val headerSize = 44 + all.size * 20
        val total = headerSize + stored.values.sumOf { it.size.padded() }
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write("wOFF".toByteArray(Charsets.ISO_8859_1))
        d.write(byteArrayOf(0, 1, 0, 0))
        d.writeInt(total)
        d.writeShort(all.size)
        d.writeShort(0)
        d.writeInt(0) // totalSfntSize (unused here)
        d.writeShort(1)
        d.writeShort(0)
        repeat(5) { d.writeInt(0) } // meta and private blocks
        var offset = headerSize
        for ((tag, data) in all) {
            d.write(tag.toByteArray(Charsets.ISO_8859_1))
            d.writeInt(offset)
            d.writeInt(stored.getValue(tag).size)
            d.writeInt(data.size)
            d.writeInt(0)
            offset += stored.getValue(tag).size.padded()
        }
        for (data in stored.values) {
            d.write(data)
            d.write(ByteArray(data.size.padded() - data.size))
        }
        return out.toByteArray()
    }

    /** A WOFF2 header with a consistent length field; the body is opaque. */
    fun woff2(bodySize: Int = 64): ByteArray {
        val total = 48 + bodySize
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write("wOF2".toByteArray(Charsets.ISO_8859_1))
        d.write(byteArrayOf(0, 1, 0, 0))
        d.writeInt(total)
        d.writeShort(3)
        d.write(ByteArray(48 - 14))
        d.write(ByteArray(bodySize))
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun Int.padded() = (this + 3) and 3.inv()
}
