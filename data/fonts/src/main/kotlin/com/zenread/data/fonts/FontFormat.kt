package com.zenread.data.fonts

/** Font container formats the reader accepts. TrueType/OpenType collections (`ttcf`) are not. */
enum class FontFormat(val extension: String) {
    /** sfnt with TrueType outlines (`00 01 00 00` or `true`). */
    TTF("ttf"),

    /** sfnt with CFF outlines (`OTTO`). */
    OTF("otf"),
    WOFF("woff"),
    WOFF2("woff2"),
    ;

    /** Android's Typeface (and so the in-app preview) reads only plain sfnt files. */
    val previewable: Boolean get() = this == TTF || this == OTF

    companion object {
        const val MAGIC_SIZE = 4

        /** Detects the format from the first four bytes; null for anything else. */
        fun sniff(header: ByteArray): FontFormat? {
            if (header.size < MAGIC_SIZE) return null
            val tag = header.readTag(0)
            return when {
                header[0] == 0.toByte() && header[1] == 1.toByte() && header[2] == 0.toByte() && header[3] == 0.toByte() -> TTF
                tag == "true" -> TTF
                tag == "OTTO" -> OTF
                tag == "wOFF" -> WOFF
                tag == "wOF2" -> WOFF2
                else -> null
            }
        }

        fun fromExtension(extension: String): FontFormat? =
            entries.firstOrNull { it.extension.equals(extension, ignoreCase = true) }
    }
}

internal fun ByteArray.readTag(offset: Int): String =
    String(CharArray(4) { (this[offset + it].toInt() and 0xFF).toChar() })
