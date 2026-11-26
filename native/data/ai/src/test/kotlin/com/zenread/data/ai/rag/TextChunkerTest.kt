package com.zenread.data.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    private fun section(index: Int, vararg blocks: String) = ExtractedSection(
        index = index,
        title = "Chapter $index",
        blocks = blocks.mapIndexed { i, text -> TextBlock(text, "epubcfi(/6/${index * 2}!/4/${i * 2})") },
    )

    @Test
    fun `packs paragraphs up to the limit and keeps the first block's cfi`() {
        val p = "x".repeat(300)
        val chunks = TextChunker(maxChars = 700, overlapChars = 0).chunkSection(section(1, p, p, p, p), firstOrdinal = 0)
        assertEquals(2, chunks.size)
        assertEquals("$p\n$p", chunks[0].text)
        assertEquals("epubcfi(/6/2!/4/0)", chunks[0].cfi)
        assertEquals("epubcfi(/6/2!/4/4)", chunks[1].cfi)
        assertEquals(listOf(0, 1), chunks.map { it.ordinal })
        assertTrue(chunks.all { it.chapter == "Chapter 1" && it.sectionIndex == 1 })
    }

    @Test
    fun `never exceeds the limit and splits long blocks at sentences`() {
        val sentence = "This is a sentence of moderate length. "
        val long = sentence.repeat(60).trim()
        val chunks = TextChunker(maxChars = 400, overlapChars = 80).chunkSection(section(0, long), 0)
        assertTrue(chunks.size > 4)
        // A folded tail may exceed the limit by at most a quarter.
        assertTrue(chunks.all { it.text.length <= 500 })
        assertTrue("pieces end at sentence boundaries", chunks.dropLast(1).all { it.text.trimEnd().endsWith(".") })
        assertEquals(long.length, TextChunker.splitLong(long, 400).sumOf { it.length } + TextChunker.splitLong(long, 400).size - 1)
    }

    @Test
    fun `adds overlap from the previous chunk`() {
        val a = "First sentence here. Second sentence here. Third sentence closes it."
        val b = "Next paragraph starts."
        val chunks = TextChunker(maxChars = 80, overlapChars = 40).chunkSection(section(0, a, b), 0)
        assertEquals(2, chunks.size)
        assertTrue(chunks[1].text.startsWith("Third sentence closes it."))
        assertTrue(chunks[1].text.endsWith(b))
    }

    @Test
    fun `folds a tiny tail into the previous chunk`() {
        val chunks = TextChunker(maxChars = 100, overlapChars = 0).chunkSection(section(0, "a".repeat(98), "tail"), 0)
        assertEquals(1, chunks.size)
        assertEquals("a".repeat(98) + "\ntail", chunks[0].text)
    }

    @Test
    fun `ordinals continue across sections`() {
        val chunks = TextChunker(maxChars = 100, overlapChars = 0).chunk(
            listOf(section(0, "a".repeat(80), "b".repeat(80)), section(1, "c".repeat(80))),
        )
        assertEquals(listOf(0, 1, 2), chunks.map { it.ordinal })
        assertEquals(listOf(0, 0, 1), chunks.map { it.sectionIndex })
    }

    @Test
    fun `chinese text splits at full stops`() {
        val text = "他走进房间。".repeat(40)
        val pieces = TextChunker.splitLong(text, 50)
        assertTrue(pieces.all { it.length <= 50 && it.endsWith("。") })
    }
}

class TextTokenizerTest {
    @Test
    fun `tokens split by script like upstream`() {
        assertEquals(listOf("ai", "时", "代", "hello", "42"), TextTokenizer.tokens("AI时代, Hello-42!"))
    }

    @Test
    fun `index terms contain cjk unigrams and bigrams`() {
        assertEquals("红 楼 梦 红楼 楼梦 dream", TextTokenizer.indexTerms("《红楼梦》 Dream"))
    }

    @Test
    fun `query terms use bigrams for cjk and drop single latin letters`() {
        assertEquals(listOf("贾宝", "宝玉", "is", "who"), TextTokenizer.queryTerms("贾宝玉 is a who"))
        assertEquals(listOf("林"), TextTokenizer.queryTerms("林"))
    }

    @Test
    fun `fts query quotes every term`() {
        assertEquals("\"don\"", TextTokenizer.ftsQuery("don't"))
        assertEquals("\"whale\" OR \"ahab\"", TextTokenizer.ftsQuery("Whale? Ahab!"))
        assertEquals(null, TextTokenizer.ftsQuery("  ?! "))
    }
}
