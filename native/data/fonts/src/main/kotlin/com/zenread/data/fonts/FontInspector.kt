package com.zenread.data.fonts

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Random access to font bytes, so large CJK fonts are never read whole. */
interface FontBytes {
    val size: Long

    /** Reads exactly [length] bytes at [offset]; throws [MalformedFontException] when out of range. */
    fun read(offset: Long, length: Int): ByteArray
}

class ByteArrayFontBytes(private val bytes: ByteArray) : FontBytes {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        checkRange(offset, length, size)
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

class FileFontBytes(private val file: RandomAccessFile) : FontBytes {
    override val size: Long = file.length()

    override fun read(offset: Long, length: Int): ByteArray {
        checkRange(offset, length, size)
        return ByteArray(length).also {
            file.seek(offset)
            file.readFully(it)
        }
    }
}

private fun checkRange(offset: Long, length: Int, size: Long) {
    if (offset < 0 || length < 0 || offset + length > size) throw MalformedFontException("Read past end of font")
}

class MalformedFontException(message: String) : Exception(message)

/** What [FontInspector] learned about a font file. Names are null when the file has none we can read. */
data class FontInfo(
    val format: FontFormat,
    val family: String?,
    val subfamily: String?,
) {
    /** Family plus a style suffix unless the style is the plain one, e.g. "Source Han Serif CN Bold". */
    val displayName: String?
        get() {
            val base = family ?: return null
            val style = subfamily?.takeUnless { it.lowercase(Locale.ROOT) in PLAIN_STYLES }
            return FontLabels.clean(if (style == null) base else "$base $style")
        }

    private companion object {
        val PLAIN_STYLES = setOf("regular", "normal", "book", "roman", "plain", "standard")
    }
}

/**
 * Validates a font file's container structure and reads its family name from the `name` table.
 * Handles TrueType/OpenType and WOFF (zlib). WOFF2 names need Brotli, so only its header is checked.
 */
object FontInspector {
    private const val MAX_TABLES = 1024
    private const val MAX_NAME_TABLE = 1 shl 20
    private const val WOFF_HEADER = 44
    private const val WOFF2_HEADER = 48

    /** Windows language ids per ISO language, best first. */
    private val WINDOWS_LANGUAGES = mapOf(
        "zh" to listOf(0x0804, 0x1004, 0x0404, 0x0C04, 0x1404),
        "ja" to listOf(0x0411),
        "ko" to listOf(0x0412),
        "en" to listOf(0x0409, 0x0809),
    )
    fun inspect(file: File, locale: Locale = Locale.getDefault()): FontInfo =
        RandomAccessFile(file, "r").use { inspect(FileFontBytes(it), locale) }

    /** @throws MalformedFontException when the bytes are not a font we can serve. */
    fun inspect(bytes: FontBytes, locale: Locale = Locale.getDefault()): FontInfo {
        if (bytes.size < FontFormat.MAGIC_SIZE) throw MalformedFontException("Too short")
        val format = FontFormat.sniff(bytes.read(0, FontFormat.MAGIC_SIZE))
            ?: throw MalformedFontException("Not a TrueType, OpenType, WOFF or WOFF2 font")
        val languages = WINDOWS_LANGUAGES[locale.language].orEmpty()
        val nameTable = when (format) {
            FontFormat.TTF, FontFormat.OTF -> sfntNameTable(bytes)
            FontFormat.WOFF -> woffNameTable(bytes)
            FontFormat.WOFF2 -> {
                woff2Header(bytes)
                null
            }
        }
        val names = nameTable?.let { NameTable.parse(it, languages) }
        return FontInfo(format, names?.family, names?.subfamily)
    }

    private class TableRecord(val tag: String, val offset: Long, val length: Int, val originalLength: Int)

    private fun sfntNameTable(bytes: FontBytes): ByteArray? {
        val header = bytes.read(0, 12)
        val count = header.u16(4)
        if (count !in 1..MAX_TABLES) throw MalformedFontException("Bad table count")
        val directory = bytes.read(12, count * 16)
        val tables = (0 until count).map { i ->
            val at = i * 16
            TableRecord(directory.readTag(at), directory.u32(at + 8), directory.u32(at + 12).toIntChecked(), 0)
        }
        tables.requireCoreTables(bytes.size)
        val name = tables.firstOrNull { it.tag == "name" } ?: return null
        if (name.length > MAX_NAME_TABLE) return null
        return bytes.read(name.offset, name.length)
    }

    private fun woffNameTable(bytes: FontBytes): ByteArray? {
        val header = bytes.read(0, WOFF_HEADER)
        if (header.u32(8) != bytes.size) throw MalformedFontException("WOFF length mismatch")
        val count = header.u16(12)
        if (count !in 1..MAX_TABLES) throw MalformedFontException("Bad table count")
        val directory = bytes.read(WOFF_HEADER.toLong(), count * 20)
        val tables = (0 until count).map { i ->
            val at = i * 20
            TableRecord(
                tag = directory.readTag(at),
                offset = directory.u32(at + 4),
                length = directory.u32(at + 8).toIntChecked(),
                originalLength = directory.u32(at + 12).toIntChecked(),
            )
        }
        tables.requireCoreTables(bytes.size)
        val name = tables.firstOrNull { it.tag == "name" } ?: return null
        if (name.originalLength > MAX_NAME_TABLE || name.length > name.originalLength) return null
        val stored = bytes.read(name.offset, name.length)
        return if (name.length == name.originalLength) stored else inflate(stored, name.originalLength)
    }

    private fun woff2Header(bytes: FontBytes) {
        val header = bytes.read(0, WOFF2_HEADER)
        if (header.u32(8) != bytes.size) throw MalformedFontException("WOFF2 length mismatch")
        if (header.u16(12) !in 1..MAX_TABLES) throw MalformedFontException("Bad table count")
    }

    private fun List<TableRecord>.requireCoreTables(fileSize: Long) {
        for (table in this) {
            if (table.offset + table.length > fileSize) throw MalformedFontException("Table ${table.tag} out of range")
        }
        val tags = mapTo(HashSet()) { it.tag }
        if ("cmap" !in tags || "head" !in tags) throw MalformedFontException("Missing cmap or head table")
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(expected)
            val buffer = ByteArray(8192)
            while (!inflater.finished() && out.size() <= expected) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
            out.toByteArray().takeIf { it.size == expected }
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun Long.toIntChecked(): Int =
        if (this in 0..Int.MAX_VALUE) toInt() else throw MalformedFontException("Table too large")
}

/** The OpenType `name` table: picks the family/style name best matching the user's language. */
internal object NameTable {
    private const val FAMILY = 1
    private const val SUBFAMILY = 2
    private const val TYPOGRAPHIC_FAMILY = 16
    private const val TYPOGRAPHIC_SUBFAMILY = 17
    private const val PLATFORM_UNICODE = 0
    private const val PLATFORM_MAC = 1
    private const val PLATFORM_WINDOWS = 3
    private const val WINDOWS_ENGLISH = 0x0409

    data class Names(val family: String?, val subfamily: String?)

    private class Record(val platform: Int, val encoding: Int, val language: Int, val nameId: Int, val value: String)

    fun parse(table: ByteArray, preferredLanguages: List<Int>): Names? {
        if (table.size < 6) return null
        val count = table.u16(2)
        val storage = table.u16(4)
        if (6 + count * 12 > table.size) return null
        val records = (0 until count).mapNotNull { i ->
            val at = 6 + i * 12
            val nameId = table.u16(at + 6)
            if (nameId != FAMILY && nameId != SUBFAMILY && nameId != TYPOGRAPHIC_FAMILY && nameId != TYPOGRAPHIC_SUBFAMILY) {
                return@mapNotNull null
            }
            val start = storage + table.u16(at + 10)
            val length = table.u16(at + 8)
            if (start + length > table.size || length == 0) return@mapNotNull null
            val platform = table.u16(at)
            val encoding = table.u16(at + 2)
            val value = decode(platform, encoding, table.copyOfRange(start, start + length)) ?: return@mapNotNull null
            Record(platform, encoding, table.u16(at + 4), nameId, value)
        }

        fun best(nameId: Int): String? = records
            .filter { it.nameId == nameId && it.value.isNotBlank() }
            .minByOrNull { rank(it, preferredLanguages) }
            ?.value

        val typographic = best(TYPOGRAPHIC_FAMILY)
        val family = typographic ?: best(FAMILY) ?: return null
        val subfamily = if (typographic != null) best(TYPOGRAPHIC_SUBFAMILY) ?: best(SUBFAMILY) else best(SUBFAMILY)
        return Names(family.trim(), subfamily?.trim())
    }

    private fun rank(record: Record, preferred: List<Int>): Int = when (record.platform) {
        PLATFORM_WINDOWS -> when (val i = preferred.indexOf(record.language)) {
            -1 -> if (record.language == WINDOWS_ENGLISH) 100 else 200
            else -> i
        }
        PLATFORM_MAC -> if (record.language == 0) 300 else 400
        else -> 500
    }

    private fun decode(platform: Int, encoding: Int, bytes: ByteArray): String? = when {
        platform == PLATFORM_WINDOWS || platform == PLATFORM_UNICODE -> String(bytes, Charsets.UTF_16BE)
        // Mac Roman: the ASCII range is what font names use in practice.
        platform == PLATFORM_MAC && encoding == 0 -> String(bytes, Charsets.ISO_8859_1)
        else -> null
    }
}

internal fun ByteArray.u16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

internal fun ByteArray.u32(offset: Int): Long =
    ((u16(offset).toLong()) shl 16) or u16(offset + 2).toLong()

/** Display-name hygiene shared by parsed names and file names. */
object FontLabels {
    private const val MAX_LENGTH = 80

    /** Drops control characters, collapses whitespace and caps the length; null when nothing is left. */
    fun clean(raw: String): String? {
        val text = raw.filterNot { it.isISOControl() || it == '\uFEFF' }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isEmpty()) return null
        return if (text.length <= MAX_LENGTH) text else text.take(MAX_LENGTH).trimEnd() + "…"
    }

    /** A label from a picked file's display name: the name without its font extension. */
    fun fromFileName(displayName: String?): String? {
        val name = displayName?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
        val stem = Regex("""\.(ttf|otf|woff2?)$""", RegexOption.IGNORE_CASE).replace(name, "")
        return clean(stem.replace('_', ' '))
    }
}
