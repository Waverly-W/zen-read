package com.zenread.data.translate

import com.zenread.data.translate.cache.TranslationCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunksTest {
    @Test
    fun shortTextIsOneChunk() {
        assertEquals(listOf("Hello."), TextChunks.split("Hello.", 100))
    }

    @Test
    fun splitsAtLastSentenceEndInSecondHalf() {
        val text = "aaaaaaa. bbbbbbbbbbbb"
        val chunks = TextChunks.split(text, 10)
        assertEquals("aaaaaaa.", chunks.first())
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 10 })
    }

    @Test
    fun splitsAtChinesePunctuation() {
        val text = "这是第一句话。这是第二句话。"
        assertEquals(listOf("这是第一句话。", "这是第二句话。"), TextChunks.split(text, 10))
    }

    @Test
    fun hardSplitWithoutBoundaryNeverBreaksSurrogatePairs() {
        val text = "😀".repeat(10) // 20 UTF-16 units
        val chunks = TextChunks.split(text, 5)
        assertEquals(text, chunks.joinToString(""))
        chunks.forEach { chunk ->
            assertFalse(Character.isHighSurrogate(chunk.last()))
            assertFalse(Character.isLowSurrogate(chunk.first()))
            assertTrue(chunk.length <= 5)
        }
    }

    @Test
    fun boundaryInFirstHalfIsIgnored() {
        val chunks = TextChunks.split("a. bbbbbbbbbbbbbbbbbb", 10)
        assertEquals("a. bbbbbbb", chunks.first())
    }

    @Test
    fun batchesRespectItemAndCharLimits() {
        val texts = listOf("aaaa", "bbbb", "cc", "dddddddddddd", "e")
        val batches = TextChunks.batch(texts, maxItems = 2, maxChars = 8) { it.length }
        assertEquals(listOf(listOf("aaaa", "bbbb"), listOf("cc"), listOf("dddddddddddd"), listOf("e")), batches)
    }

    @Test
    fun batchKeepsOrderAndAllItems() {
        val texts = (1..23).map { "p$it" }
        val batches = TextChunks.batch(texts, maxItems = 5, maxChars = 1_000) { it.length }
        assertEquals(5, batches.size)
        assertEquals(texts, batches.flatten())
    }

    @Test
    fun ornamentsAndNumbersAreNotTranslatable() {
        assertFalse(TextChunks.isTranslatable("* * *"))
        assertFalse(TextChunks.isTranslatable("42"))
        assertTrue(TextChunks.isTranslatable("Chapter 1"))
        assertTrue(TextChunks.isTranslatable("第一章"))
    }
}

class ScriptDetectorTest {
    @Test
    fun recognizesUniqueScripts() {
        assertEquals(Language.SIMPLIFIED_CHINESE, ScriptDetector.guess("这是一本书。"))
        assertEquals(Language.SIMPLIFIED_CHINESE, ScriptDetector.guess("這是一本書。"))
        assertEquals(Language.JAPANESE, ScriptDetector.guess("これは本です。"))
        assertEquals(Language.JAPANESE, ScriptDetector.guess("吾輩は猫である。名前はまだ無い。"))
        assertEquals(Language.KOREAN, ScriptDetector.guess("이것은 책입니다."))
        assertEquals(Language.THAI, ScriptDetector.guess("นี่คือหนังสือ"))
        assertEquals(Language.GREEK, ScriptDetector.guess("Αυτό είναι ένα βιβλίο"))
    }

    @Test
    fun sharedScriptsAreLeftToTheService() {
        assertNull(ScriptDetector.guess("This is a book."))
        assertNull(ScriptDetector.guess("Это книга."))
        assertNull(ScriptDetector.guess("123 ..."))
        // A Chinese word inside English text stays undecided.
        assertNull(ScriptDetector.guess("The word 道 means way."))
    }
}

class TranslationCacheKeyTest {
    @Test
    fun keyIsStableHex() {
        val key = TranslationCacheKey.of("deepl", null, Language.GERMAN, "Hello")
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(key, TranslationCacheKey.of("deepl", null, Language.GERMAN, "Hello"))
    }

    @Test
    fun everyInputChangesTheKey() {
        val base = TranslationCacheKey.of("deepl", null, Language.GERMAN, "Hello")
        assertNotEquals(base, TranslationCacheKey.of("google_free", null, Language.GERMAN, "Hello"))
        assertNotEquals(base, TranslationCacheKey.of("deepl", Language.ENGLISH, Language.GERMAN, "Hello"))
        assertNotEquals(base, TranslationCacheKey.of("deepl", null, Language.FRENCH, "Hello"))
        assertNotEquals(base, TranslationCacheKey.of("deepl", null, Language.GERMAN, "Hello!"))
    }

    @Test
    fun fieldsCannotRunTogether() {
        // Without separators "ab" + "c" and "a" + "bc" would hash the same.
        assertNotEquals(
            TranslationCacheKey.of("ab", null, Language.GERMAN, "c"),
            TranslationCacheKey.of("a", null, Language.GERMAN, "bc"),
        )
    }
}
