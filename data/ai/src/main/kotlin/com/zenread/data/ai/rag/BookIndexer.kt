// Index build flow modelled on Modu `book_knowledge_index_service.dart` (ReadAny-derived,
// GPL-3.0-or-later): extract → chunk → embed → commit, never marking partial work complete.
package com.zenread.data.ai.rag

import android.util.Log
import com.zenread.core.model.BookFormat
import com.zenread.data.ai.ChatClient
import com.zenread.data.ai.chat.ChatException
import com.zenread.data.ai.chat.ModelRef
import com.zenread.data.ai.settings.AiSettingsRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the indexer needs to know about a book. */
data class IndexTarget(
    val bookId: Long,
    val title: String,
    val filePath: String,
    val format: BookFormat,
    val fileHash: String,
)

enum class IndexStage { EXTRACTING, EMBEDDING }

data class IndexProgress(val stage: IndexStage, val done: Int, val total: Int)

@Singleton
class BookIndexer @Inject constructor(
    private val extractor: BookTextExtractor,
    private val store: IndexStore,
    private val settings: AiSettingsRepository,
    private val chat: ChatClient,
) {
    /**
     * Builds or resumes the index of [target]. Chunks are reused when the file and chunking
     * options are unchanged, and only missing vectors are embedded. Book text goes only to
     * the embedding provider the user chose.
     */
    suspend fun build(target: IndexTarget, rebuild: Boolean, onProgress: suspend (IndexProgress) -> Unit) {
        val current = settings.current()
        val options = current.index.sanitized()
        val embedding: ModelRef? = current.resolvedEmbeddingModel
        val dao = store.dao
        val existing = dao.get(target.bookId)
        val reusable = existing?.takeIf {
            !rebuild && it.chunkCount > 0 && it.fileHash == target.fileHash &&
                it.chunkSize == options.chunkSize && it.chunkOverlap == options.chunkOverlap
        }
        val reuseChunks = reusable != null
        val sameEmbedding = existing?.embeddingProviderId == embedding?.providerId && existing?.embeddingModel == embedding?.model

        var header = BookIndexEntity(
            bookId = target.bookId,
            title = target.title,
            fileHash = target.fileHash,
            status = IndexStatus.EXTRACTING.dbValue,
            embeddingProviderId = embedding?.providerId,
            embeddingModel = embedding?.model,
            dimensions = if (sameEmbedding) reusable?.dimensions ?: 0 else 0,
            chunkSize = options.chunkSize,
            chunkOverlap = options.chunkOverlap,
            chunkCount = reusable?.chunkCount ?: 0,
            embeddedCount = 0,
            sectionCount = reusable?.sectionCount ?: 0,
            error = null,
            updatedAt = System.currentTimeMillis(),
        )
        try {
            if (reuseChunks) {
                if (!sameEmbedding) dao.clearVectors(target.bookId)
            } else {
                dao.upsert(header)
                val sections = mutableListOf<ExtractedSection>()
                extractor.extract(
                    file = File(target.filePath),
                    format = target.format,
                    onProgress = { done, total -> onProgress(IndexProgress(IndexStage.EXTRACTING, done, total)) },
                    onSection = { sections += it },
                )
                val chunks = TextChunker(options.chunkSize, options.chunkOverlap).chunk(sections)
                if (chunks.isEmpty()) {
                    throw BookExtractionException(
                        if (target.format == BookFormat.PDF) "This PDF has no text layer" else "No readable text was found",
                    )
                }
                store.replaceChunks(target.bookId, chunks)
                header = header.copy(chunkCount = chunks.size, sectionCount = sections.size)
            }
            header = header.copy(
                status = (if (embedding == null) IndexStatus.READY else IndexStatus.EMBEDDING).dbValue,
                updatedAt = System.currentTimeMillis(),
            )
            dao.upsert(header)

            if (embedding != null) {
                header = embedAll(header, embedding, onProgress)
            }
            dao.upsert(header.copy(status = IndexStatus.READY.dbValue, error = null, updatedAt = System.currentTimeMillis()))
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val embedded = dao.embeddedCount(target.bookId)
                dao.setStatus(target.bookId, IndexStatus.PAUSED.dbValue, null, embedded, System.currentTimeMillis())
            }
            throw e
        } catch (e: Exception) {
            // Messages are ours or the provider's; they never contain book text or keys.
            Log.w(TAG, "Indexing book ${target.bookId} failed: ${e.javaClass.simpleName}")
            withContext(NonCancellable) {
                val embedded = dao.embeddedCount(target.bookId)
                dao.setStatus(target.bookId, IndexStatus.FAILED.dbValue, e.message ?: e.javaClass.simpleName, embedded, System.currentTimeMillis())
            }
            throw e
        }
    }

    private suspend fun embedAll(
        start: BookIndexEntity,
        model: ModelRef,
        onProgress: suspend (IndexProgress) -> Unit,
    ): BookIndexEntity {
        val dao = store.dao
        var header = start
        var embedded = dao.embeddedCount(header.bookId)
        onProgress(IndexProgress(IndexStage.EMBEDDING, embedded, header.chunkCount))
        while (true) {
            val batch = dao.pendingVectors(header.bookId, BATCH_SIZE)
            if (batch.isEmpty()) break
            val vectors = embedWithBackoff(batch.map { it.text }, model)
            val dimensions = vectors.first().size
            if (vectors.any { it.size != dimensions } || (header.dimensions != 0 && header.dimensions != dimensions)) {
                throw ChatException(ChatException.Kind.PROTOCOL, "The embedding model returned vectors of different sizes")
            }
            if (header.dimensions == 0) {
                header = header.copy(dimensions = dimensions)
                dao.upsert(header)
            }
            store.saveVectors(batch.map { it.id }.zip(vectors))
            embedded += batch.size
            dao.setStatus(header.bookId, IndexStatus.EMBEDDING.dbValue, null, embedded, System.currentTimeMillis())
            onProgress(IndexProgress(IndexStage.EMBEDDING, embedded, header.chunkCount))
        }
        return header.copy(embeddedCount = embedded)
    }

    private suspend fun embedWithBackoff(texts: List<String>, model: ModelRef): List<FloatArray> {
        var wait = 2_000L
        repeat(MAX_RETRIES) {
            try {
                return chat.embed(texts, model)
            } catch (e: ChatException) {
                val transient = e.kind == ChatException.Kind.RATE_LIMIT || e.kind == ChatException.Kind.SERVER ||
                    e.kind == ChatException.Kind.NETWORK
                if (!transient) throw e
                delay(wait)
                wait = (wait * 2).coerceAtMost(60_000L)
            }
        }
        return chat.embed(texts, model)
    }

    private companion object {
        const val TAG = "ZenAiIndex"
        const val BATCH_SIZE = 32
        const val MAX_RETRIES = 5
    }
}
