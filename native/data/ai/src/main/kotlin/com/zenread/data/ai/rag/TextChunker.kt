// Chunking and tokenisation ported from Modu `lib/service/knowledge/knowledge_engine.dart`
// (TextChunker, _tokens), itself derived from ReadAny. GPL-3.0-or-later.
// Changes: chunks follow block (paragraph) boundaries with overlap and keep the CFI of their
// first block; CJK text is indexed as unigrams + bigrams for FTS5.
package com.zenread.data.ai.rag

/** A retrievable piece of a book. [ordinal] is its position in the whole book. */
data class TextChunk(
    val ordinal: Int,
    val sectionIndex: Int,
    val chapter: String,
    val cfi: String,
    val text: String,
)

class TextChunker(
    private val maxChars: Int = 800,
    private val overlapChars: Int = 120,
) {
    init {
        require(maxChars >= 50) { "maxChars too small" }
        require(overlapChars in 0 until maxChars) { "overlap must be smaller than a chunk" }
    }

    /** Chunks every section; ordinals continue across sections starting at [firstOrdinal]. */
    fun chunk(sections: List<ExtractedSection>, firstOrdinal: Int = 0): List<TextChunk> {
        val out = mutableListOf<TextChunk>()
        sections.forEach { out += chunkSection(it, firstOrdinal + out.size) }
        return out
    }

    fun chunkSection(section: ExtractedSection, firstOrdinal: Int): List<TextChunk> {
        // Split oversized blocks first so every piece fits in a chunk.
        val pieces = section.blocks.flatMap { block ->
            splitLong(block.text.trim(), maxChars).map { TextBlock(it, block.cfi) }
        }.filter { it.text.isNotEmpty() }

        val chunks = mutableListOf<TextChunk>()
        val buffer = StringBuilder()
        var bufferCfi: String? = null
        var carry = ""

        fun flush() {
            if (buffer.isBlank()) return
            chunks += TextChunk(firstOrdinal + chunks.size, section.index, section.title, bufferCfi!!, buffer.toString())
            carry = overlapTail(buffer.toString(), overlapChars)
            buffer.setLength(0)
            bufferCfi = null
        }

        for (piece in pieces) {
            val separatorLength = if (buffer.isEmpty()) 0 else 1
            if (buffer.isNotEmpty() && buffer.length + separatorLength + piece.text.length > maxChars) flush()
            if (buffer.isEmpty()) {
                bufferCfi = piece.cfi
                // Overlap only when it still leaves room for the new block.
                if (carry.isNotEmpty() && carry.length + 1 + piece.text.length <= maxChars) {
                    buffer.append(carry).append('\n')
                }
                carry = ""
            } else {
                buffer.append('\n')
            }
            buffer.append(piece.text)
        }
        flush()

        // Fold a tiny tail into the previous chunk of the same section.
        if (chunks.size >= 2) {
            val last = chunks.last()
            val previous = chunks[chunks.size - 2]
            val tailOnly = last.text.removePrefix(overlapTail(previous.text, overlapChars)).trimStart('\n')
            if (tailOnly.length < maxChars / 4 && previous.text.length + 1 + tailOnly.length <= maxChars + maxChars / 4) {
                chunks[chunks.size - 2] = previous.copy(text = previous.text + "\n" + tailOnly)
                chunks.removeAt(chunks.lastIndex)
            }
        }
        return chunks
    }

    companion object {
        private val SENTENCE_END = Regex("""(?<=[。！？!?；;…])|(?<=\.)(?=\s)""")

        /** Splits [text] into pieces of at most [max] chars, preferring sentence then word boundaries. */
        internal fun splitLong(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text)
            val out = mutableListOf<String>()
            var rest = text
            while (rest.length > max) {
                val window = rest.substring(0, max)
                val sentenceCut = SENTENCE_END.findAll(window).map { it.range.first }.filter { it >= max / 2 }.lastOrNull()
                val spaceCut = window.lastIndexOf(' ').takeIf { it >= max / 2 }
                val cut = sentenceCut ?: spaceCut ?: max
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trimStart()
            }
            if (rest.isNotBlank()) out += rest.trim()
            return out.filter { it.isNotEmpty() }
        }

        /** The last ~[max] chars of [text], starting at a sentence or word boundary when possible. */
        internal fun overlapTail(text: String, max: Int): String {
            if (max <= 0 || text.isEmpty()) return ""
            if (text.length <= max) return ""
            val tail = text.substring(text.length - max)
            val sentence = SENTENCE_END.find(tail)?.range?.first?.takeIf { it in 1 until tail.length - 1 }
            val start = sentence ?: tail.indexOf(' ').takeIf { it in 0 until tail.length - 1 }?.plus(1) ?: 0
            return tail.substring(start).trim()
        }
    }
}

/** Tokeniser shared by indexing and querying. */
object TextTokenizer {
    private val SPLIT = Regex("""[^\p{L}\p{N}]+""")

    private fun isCjk(c: Char): Boolean = c.code >= 0x2E80 && Character.isLetter(c)

    /**
     * Lower-cased words; runs of CJK characters become single characters (as upstream
     * `_tokens`). Mixed runs like `AI时代` are split by script.
     */
    fun tokens(input: String): List<String> = scriptRuns(input).flatMap { (run, cjk) ->
        if (cjk) run.map { it.toString() } else listOf(run)
    }

    /** Terms stored in FTS5: words, CJK unigrams and CJK bigrams. */
    fun indexTerms(input: String): String = buildString {
        scriptRuns(input).forEach { (run, cjk) ->
            if (cjk) {
                run.forEach { append(it).append(' ') }
                run.windowed(2).forEach { append(it).append(' ') }
            } else {
                append(run).append(' ')
            }
        }
    }.trimEnd()

    /**
     * Query terms: words, and CJK bigrams (unigram for a lone character), which keeps
     * Chinese queries precise without a word segmenter.
     */
    fun queryTerms(input: String, limit: Int = 48): List<String> = scriptRuns(input).flatMap { (run, cjk) ->
        when {
            !cjk -> listOf(run)
            run.length == 1 -> listOf(run)
            else -> run.windowed(2)
        }
    }.filter { it.length > 1 || isCjk(it[0]) || it[0].isDigit() }.distinct().take(limit)

    /** FTS5 MATCH expression OR-ing every query term, or null when there is nothing to match. */
    fun ftsQuery(input: String): String? {
        val terms = queryTerms(input)
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ") { "\"" + it.replace("\"", "\"\"") + "\"" }
    }

    private fun scriptRuns(input: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        input.lowercase().split(SPLIT).forEach { word ->
            if (word.isEmpty()) return@forEach
            var start = 0
            for (i in 1..word.length) {
                if (i == word.length || isCjk(word[i]) != isCjk(word[start])) {
                    out += word.substring(start, i) to isCjk(word[start])
                    start = i
                }
            }
        }
        return out
    }
}
