// Ranking ported from Modu `lib/service/knowledge/knowledge_engine.dart` (Bm25Index,
// VectorIndex, HybridSearch), derived from ReadAny. GPL-3.0-or-later.
// Changes: real BM25 instead of term counting, float vectors, configurable weights.
package com.zenread.data.ai.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt

/** A candidate chunk and its score (higher is better). */
data class Scored(val id: Long, val score: Double)

object VectorMath {
    fun dot(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i].toDouble() * b[i]
        return sum
    }

    fun norm(a: FloatArray): Double = sqrt(dot(a, a))

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        val denominator = norm(a) * norm(b)
        return if (denominator == 0.0) 0.0 else dot(a, b) / denominator
    }

    /** Unit-length copy, so ranking can use a plain dot product. */
    fun normalized(a: FloatArray): FloatArray {
        val n = norm(a)
        if (n == 0.0) return a.copyOf()
        return FloatArray(a.size) { (a[it] / n).toFloat() }
    }

    /** Brute-force top-[k] by cosine; vectors of another dimension are skipped. */
    fun topK(query: FloatArray, vectors: List<Pair<Long, FloatArray>>, k: Int): List<Scored> {
        val q = normalized(query)
        return vectors.asSequence()
            .filter { it.second.size == q.size }
            .map { (id, v) -> Scored(id, dot(q, normalized(v))) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(k)
            .toList()
    }

    /** Little-endian float32, the BLOB format of `chunks.vector`. */
    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
}

/**
 * In-memory BM25 over [TextTokenizer] terms. Used when FTS5 is unavailable, and as the
 * reference the FTS path mirrors.
 */
class Bm25(documents: List<Pair<Long, String>>, private val k1: Double = 1.2, private val b: Double = 0.75) {
    private val docs = documents.map { (id, text) -> id to TextTokenizer.indexTerms(text).split(' ').filter { it.isNotEmpty() } }
    private val avgLength = docs.map { it.second.size }.average().takeIf { !it.isNaN() && it > 0 } ?: 1.0
    private val documentFrequency: Map<String, Int> = HashMap<String, Int>().also { df ->
        docs.forEach { (_, terms) -> terms.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
    }

    fun search(query: String, limit: Int): List<Scored> {
        val terms = TextTokenizer.queryTerms(query)
        if (terms.isEmpty()) return emptyList()
        val n = docs.size.toDouble()
        return docs.mapNotNull { (id, docTerms) ->
            val counts = docTerms.groupingBy { it }.eachCount()
            var score = 0.0
            for (term in terms) {
                val tf = counts[term] ?: continue
                val df = documentFrequency[term] ?: continue
                val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
                score += idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * docTerms.size / avgLength))
            }
            if (score > 0) Scored(id, score) else null
        }.sortedByDescending { it.score }.take(limit)
    }
}

/**
 * Weighted fusion of keyword and vector rankings, each normalised by its best score
 * (upstream `HybridSearch.merge`). Upstream weighted lexical 0.7 / vector 0.3 for its
 * feature-hashed local vectors; real embedding models are stronger, so the default here
 * favours vectors.
 */
object HybridRanker {
    fun merge(
        lexical: List<Scored>,
        vector: List<Scored>,
        limit: Int,
        lexicalWeight: Double = 0.4,
        vectorWeight: Double = 0.6,
    ): List<Scored> {
        val maxLexical = lexical.maxOfOrNull { it.score }?.takeIf { it > 0 } ?: 1.0
        val maxVector = vector.maxOfOrNull { it.score }?.takeIf { it > 0 } ?: 1.0
        val scores = LinkedHashMap<Long, Double>()
        lexical.forEach { scores[it.id] = (scores[it.id] ?: 0.0) + lexicalWeight * it.score / maxLexical }
        vector.forEach { scores[it.id] = (scores[it.id] ?: 0.0) + vectorWeight * it.score / maxVector }
        return scores.entries
            .map { Scored(it.key, it.value) }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.id })
            .take(limit)
    }
}
