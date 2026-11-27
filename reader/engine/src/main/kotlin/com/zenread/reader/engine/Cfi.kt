package com.zenread.reader.engine

/** Small EPUB CFI helpers (see https://idpf.org/epub/linking/cfi/). */
object Cfi {
    /**
     * Turns a range CFI `epubcfi(parent,start,end)` into the point `epubcfi(parent+start)`.
     *
     * foliate-js reports each page as a range. Restoring a range measures its client
     * rects, and when the range begins exactly at a page break the first box sits at the
     * end of the previous page, so the book reopens one page early. A point at the start
     * of the range resolves to the right page. Non-range CFIs are returned unchanged.
     */
    fun collapseToStart(cfi: String): String {
        if (!cfi.startsWith("epubcfi(") || !cfi.endsWith(")")) return cfi
        val body = cfi.substring("epubcfi(".length, cfi.length - 1)
        val parts = splitUnescaped(body, ',')
        if (parts.size != 3) return cfi
        return "epubcfi(${parts[0]}${parts[1]})"
    }

    /** Splits on [separator] except where escaped with `^` (CFI's escape character). */
    private fun splitUnescaped(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in text) {
            when {
                escaped -> { current.append(ch); escaped = false }
                ch == '^' -> { current.append(ch); escaped = true }
                ch == separator -> { parts += current.toString(); current.clear() }
                else -> current.append(ch)
            }
        }
        parts += current.toString()
        return parts
    }
}
