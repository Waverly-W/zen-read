// Retrieval modelled on Modu `book_knowledge_retriever.dart` (ReadAny-derived,
// GPL-3.0-or-later): never builds an index or substitutes another model; a broken
// optional index or vector query degrades to keyword search instead of failing the chat.
package com.zenread.data.ai.rag

import android.util.Log
import androidx.room.RoomRawQuery
import com.zenread.data.ai.ChatClient
import com.zenread.data.ai.chat.ModelRef
import com.zenread.data.ai.settings.AiSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class RetrievedPassage(
    val chunkId: Long,
    val ordinal: Int,
    val chapter: String,
    val cfi: String,
    val text: String,
    val score: Double,
)

@Singleton
class BookRetriever @Inject constructor(
    private val store: IndexStore,
    private val settings: AiSettingsRepository,
    private val chat: ChatClient,
) {
    private val cacheLock = Mutex()
    private var cacheKey: String? = null
    private var cachedVectors: List<Pair<Long, FloatArray>> = emptyList()

    /** Hybrid keyword + vector search over [bookId]'s index; empty when there is none. */
    suspend fun search(bookId: Long, query: String, limit: Int): List<RetrievedPassage> {
        val dao = store.dao
        val header = dao.get(bookId) ?: return emptyList()
        if (header.chunkCount == 0 || query.isBlank() || limit <= 0) return emptyList()
        val candidates = limit * 3

        val lexical = try {
            keywordSearch(bookId, query, candidates)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Keyword search failed: ${e.javaClass.simpleName}")
            emptyList()
        }

        val vector = vectorSearch(header, query, candidates)
        val ranked = if (vector.isEmpty()) lexical.take(limit) else HybridRanker.merge(lexical, vector, limit)
        if (ranked.isEmpty()) return emptyList()
        val rows = dao.chunks(ranked.map { it.id }).associateBy { it.id }
        return ranked.mapNotNull { scored ->
            rows[scored.id]?.let { RetrievedPassage(it.id, it.ordinal, it.chapter, it.cfi, it.text, scored.score) }
        }
    }

    /** The opening chunk of each chapter in order, trimmed to [maxChars] in total. */
    suspend fun bookOutline(bookId: Long, maxChars: Int): List<RetrievedPassage> {
        val heads = store.dao.sectionHeads(bookId)
        if (heads.isEmpty()) return emptyList()
        val perSection = (maxChars / heads.size).coerceIn(120, 1200)
        var budget = maxChars
        val out = mutableListOf<RetrievedPassage>()
        for (row in heads) {
            if (budget <= 0) break
            val text = row.text.take(perSection.coerceAtMost(budget))
            budget -= text.length
            out += RetrievedPassage(row.id, row.ordinal, row.chapter, row.cfi, text, 0.0)
        }
        return out
    }

    private suspend fun keywordSearch(bookId: Long, query: String, limit: Int): List<Scored> {
        if (store.ftsAvailable) {
            val match = TextTokenizer.ftsQuery(query) ?: return emptyList()
            val sql = "SELECT rowid AS id, bm25(${AiIndexDatabase.FTS_TABLE}) AS score FROM ${AiIndexDatabase.FTS_TABLE} " +
                "WHERE ${AiIndexDatabase.FTS_TABLE} MATCH ? AND bookId = ? ORDER BY score LIMIT ?"
            val hits = store.dao.ftsSearch(
                RoomRawQuery(sql) { statement ->
                    statement.bindText(1, match)
                    statement.bindLong(2, bookId)
                    statement.bindLong(3, limit.toLong())
                },
            )
            // FTS5 bm25() is lower-is-better.
            return hits.map { Scored(it.id, -it.score) }.filter { it.score > 0 }
        }
        val texts = store.dao.allTexts(bookId)
        return Bm25(texts.map { it.id to it.text }).search(query, limit)
    }

    private suspend fun vectorSearch(header: BookIndexEntity, query: String, limit: Int): List<Scored> {
        val model = header.embeddingModel ?: return emptyList()
        val providerId = header.embeddingProviderId ?: return emptyList()
        // Only query with the model that produced the vectors.
        if (settings.current().resolvedEmbeddingModel != ModelRef(providerId, model)) return emptyList()
        if (header.embeddedCount == 0) return emptyList()
        return try {
            val queryVector = withTimeoutOrNull(QUERY_TIMEOUT_MS) {
                chat.embed(listOf(query.take(4096)), ModelRef(providerId, model)).single()
            } ?: return emptyList()
            VectorMath.topK(queryVector, vectorsFor(header), limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Vector query unavailable, using keywords: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private suspend fun vectorsFor(header: BookIndexEntity): List<Pair<Long, FloatArray>> = cacheLock.withLock {
        val key = "${header.bookId}:${header.embeddedCount}:${header.updatedAt}"
        if (cacheKey != key) {
            cachedVectors = store.dao.vectors(header.bookId).map { it.id to VectorMath.normalized(VectorMath.decode(it.vector)) }
            cacheKey = key
        }
        cachedVectors
    }

    private companion object {
        const val TAG = "ZenAiRetriever"
        const val QUERY_TIMEOUT_MS = 15_000L
    }
}
