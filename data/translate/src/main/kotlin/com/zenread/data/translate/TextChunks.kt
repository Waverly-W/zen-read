package com.zenread.data.translate

/**
 * Splitting and grouping of text for request size limits.
 */
object TextChunks {
    private val SENTENCE_END = Regex("[\\n。！？.!?]")

    /**
     * Splits [text] into pieces of at most [maxLength] UTF-16 units. Port of upstream
     * `MicrosoftFreeTranslateProvider._splitText`: each piece ends at the last sentence end
     * or newline when one lies in its second half, otherwise at the hard limit (never inside
     * a surrogate pair). Joining the pieces gives back [text].
     */
    fun split(text: String, maxLength: Int): List<String> {
        require(maxLength >= 2) { "maxLength must be at least 2" }
        if (text.length <= maxLength) return listOf(text)
        val chunks = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            var end = minOf(offset + maxLength, text.length)
            if (end < text.length) {
                val candidate = text.substring(offset, end)
                val boundary = SENTENCE_END.findAll(candidate).lastOrNull()?.range?.first ?: -1
                if (boundary > maxLength / 2) {
                    end = offset + boundary + 1
                } else if (Character.isHighSurrogate(text[end - 1])) {
                    end -= 1
                }
            }
            chunks += text.substring(offset, end)
            offset = end
        }
        return chunks
    }

    /**
     * Groups [texts] (in order) into batches of at most [maxItems] items and [maxChars]
     * characters. A single text longer than [maxChars] gets a batch of its own.
     */
    fun <T> batch(texts: List<T>, maxItems: Int, maxChars: Int, length: (T) -> Int): List<List<T>> {
        val batches = mutableListOf<List<T>>()
        var current = mutableListOf<T>()
        var chars = 0
        for (item in texts) {
            val size = length(item)
            if (current.isNotEmpty() && (current.size >= maxItems || chars + size > maxChars)) {
                batches += current
                current = mutableListOf()
                chars = 0
            }
            current += item
            chars += size
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    /** False for text with no letters at all (page numbers, ornaments like `* * *`). */
    fun isTranslatable(text: String): Boolean = text.any { Character.isLetter(it) }
}

/**
 * Guesses the language of text written in a script only one offered language uses, so the
 * selection sheet can pick a fallback target without a detection round trip. Latin, Cyrillic,
 * Arabic and Devanagari text return null: several languages share those scripts.
 */
object ScriptDetector {
    fun guess(text: String): Language? {
        var letters = 0
        var han = 0
        var kana = 0
        var hangul = 0
        var thai = 0
        var greek = 0
        var tamil = 0
        var telugu = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            letters++
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.THAI -> thai++
                Character.UnicodeScript.GREEK -> greek++
                Character.UnicodeScript.TAMIL -> tamil++
                Character.UnicodeScript.TELUGU -> telugu++
                else -> Unit
            }
        }
        if (letters == 0) return null
        val half = letters / 2.0
        return when {
            // Japanese mixes kanji and kana; a little kana is enough to tell it from Chinese.
            kana > 0 && kana + han > half && kana * 10 >= letters -> Language.JAPANESE
            hangul > half -> Language.KOREAN
            han > half -> Language.SIMPLIFIED_CHINESE
            thai > half -> Language.THAI
            greek > half -> Language.GREEK
            tamil > half -> Language.TAMIL
            telugu > half -> Language.TELUGU
            else -> null
        }
    }
}
