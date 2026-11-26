// Ported from Modu lib/service/tts/tts_text_filter.dart.
package com.zenread.data.tts

/** Text helpers shared by the engines and the playback loop. */
object TtsText {
    // Skip only separators, not arbitrary text whose synthesis happens to fail. Keep symbols
    // (e.g. mathematical operators) and all scripts/numbers eligible for speech; an ASCII/CJK
    // allowlist would silently drop other languages. U+22EF is commonly used as an ellipsis.
    private val separatorOnly = Regex("""^[\p{P}\p{Z}\p{Cc}\p{Cf}\s⋯]*$""")

    /** True for text made only of punctuation and whitespace, e.g. "……" or "* * *". */
    fun isSeparator(text: String): Boolean = separatorOnly.matches(text)

    /** True when [text] has anything an engine can pronounce. */
    fun isSpeakable(text: String): Boolean = text.isNotBlank() && !isSeparator(text)

    /** Collapses runs of whitespace (line breaks inside a paragraph) into single spaces. */
    fun normalize(text: String): String = text.replace(whitespace, " ").trim()

    private val whitespace = Regex("""\s+""")

    /**
     * Splits [text] into pieces of at most [maxChars] characters, preferring sentence ends,
     * then spaces, and never splitting a surrogate pair. System engines reject input longer
     * than `TextToSpeech.getMaxSpeechInputLength()`.
     */
    fun split(text: String, maxChars: Int): List<String> {
        require(maxChars > 1)
        if (text.length <= maxChars) return listOf(text)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxChars).coerceAtMost(text.length)
            if (end < text.length) {
                val window = text.substring(start, end)
                val sentence = window.indexOfLast { it in sentenceEnds }
                val space = window.lastIndexOf(' ')
                end = when {
                    sentence >= maxChars / 2 -> start + sentence + 1
                    space >= maxChars / 2 -> start + space + 1
                    else -> end
                }
                if (Character.isHighSurrogate(text[end - 1])) end--
            }
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(parts::add)
            start = end
        }
        return parts
    }

    private val sentenceEnds = setOf('.', '!', '?', '。', '！', '？', '；', ';', '…', '\n')
}
