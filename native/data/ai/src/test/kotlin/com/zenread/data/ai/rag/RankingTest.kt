package com.zenread.data.ai.rag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingTest {
    @Test
    fun `cosine handles direction, length and mismatched sizes`() {
        assertEquals(1.0, VectorMath.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-9)
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 3f)), 1e-9)
        assertEquals(-1.0, VectorMath.cosine(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-9)
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 1f)), 1e-9)
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 1e-9)
    }

    @Test
    fun `top k orders by cosine and skips other dimensions and opposites`() {
        val vectors = listOf(
            1L to floatArrayOf(1f, 0f),
            2L to floatArrayOf(0.7f, 0.7f),
            3L to floatArrayOf(-1f, 0f),
            4L to floatArrayOf(1f, 0f, 0f),
            5L to floatArrayOf(0.9f, 0.1f),
        )
        val top = VectorMath.topK(floatArrayOf(2f, 0f), vectors, k = 2)
        assertEquals(listOf(1L, 5L), top.map { it.id })
        assertEquals(1.0, top[0].score, 1e-6)
    }

    @Test
    fun `vector blobs round-trip as little-endian float32`() {
        val vector = floatArrayOf(0.25f, -1.5f, 3.4028235e38f, 1e-7f)
        val bytes = VectorMath.encode(vector)
        assertEquals(16, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x3E.toByte(), bytes[3]) // 0.25f = 0x3E800000, little-endian
        assertArrayEquals(vector, VectorMath.decode(bytes), 0f)
    }

    @Test
    fun `bm25 prefers rare terms and shorter documents`() {
        val bm25 = Bm25(
            listOf(
                1L to "the whale the sea the ship",
                2L to "Ahab hunts the white whale",
                3L to "the sea the sea the sea the sea the sea the sea and a whale",
                4L to "nothing relevant here",
            ),
        )
        val results = bm25.search("Ahab whale", limit = 10)
        assertEquals(2L, results.first().id)
        assertTrue(results.none { it.id == 4L })
        val whale = bm25.search("whale", limit = 10).map { it.id }
        assertTrue("shorter document ranks above the padded one", whale.indexOf(1L) < whale.indexOf(3L))
    }

    @Test
    fun `bm25 matches chinese through bigrams`() {
        val bm25 = Bm25(listOf(1L to "贾宝玉初见林黛玉。", 2L to "王熙凤协理宁国府。"))
        assertEquals(listOf(1L), bm25.search("林黛玉", 5).map { it.id })
    }

    @Test
    fun `hybrid merge normalises each list and combines weights`() {
        val lexical = listOf(Scored(1, 10.0), Scored(2, 5.0))
        val vector = listOf(Scored(2, 0.9), Scored(3, 0.45))
        val merged = HybridRanker.merge(lexical, vector, limit = 3, lexicalWeight = 0.5, vectorWeight = 0.5)
        // id 2: 0.5*0.5 + 0.5*1.0 = 0.75; id 1: 0.5; id 3: 0.25
        assertEquals(listOf(2L, 1L, 3L), merged.map { it.id })
        assertEquals(0.75, merged[0].score, 1e-9)
        assertEquals(1, HybridRanker.merge(lexical, vector, limit = 1).size)
    }

    @Test
    fun `hybrid merge works with only one list`() {
        assertEquals(listOf(7L), HybridRanker.merge(emptyList(), listOf(Scored(7, 0.2)), limit = 5).map { it.id })
        assertEquals(listOf(8L), HybridRanker.merge(listOf(Scored(8, 3.0)), emptyList(), limit = 5).map { it.id })
    }
}
