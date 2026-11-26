package com.zenread.data.ai.rag

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.zenread.data.ai.rag.AiIndexDatabase.Companion.FTS_TABLE
import javax.inject.Inject
import javax.inject.Singleton

enum class IndexStatus(val dbValue: String) {
    /** Reading the book; chunks are incomplete. */
    EXTRACTING("extracting"),

    /** All chunks stored (keyword search works); vectors are being computed. */
    EMBEDDING("embedding"),
    READY("ready"),

    /** Stopped by the user or the system; a new run resumes the missing vectors. */
    PAUSED("paused"),
    FAILED("failed"),
    ;

    companion object {
        fun of(value: String) = entries.firstOrNull { it.dbValue == value } ?: FAILED
    }
}

/** Writes that Room's DAO cannot express (FTS5) or that must be one transaction. */
@Singleton
class IndexStore @Inject constructor(private val db: AiIndexDatabase) {
    val dao: IndexDao get() = db.index()

    val ftsAvailable: Boolean get() = AiIndexDatabase.FtsCallback.ftsAvailable

    /** Replaces every chunk of [bookId] (and its FTS rows) atomically. */
    suspend fun replaceChunks(bookId: Long, chunks: List<TextChunk>) {
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (ftsAvailable) {
                    usePrepared("DELETE FROM $FTS_TABLE WHERE bookId = ?") { it.bindLong(1, bookId); it.step() }
                }
                usePrepared("DELETE FROM chunks WHERE bookId = ?") { it.bindLong(1, bookId); it.step() }
                usePrepared(
                    "INSERT INTO chunks (id, bookId, ordinal, sectionIndex, chapter, cfi, text, vector) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                ) { statement ->
                    chunks.forEach { chunk ->
                        statement.bindLong(1, chunkId(bookId, chunk.ordinal))
                        statement.bindLong(2, bookId)
                        statement.bindLong(3, chunk.ordinal.toLong())
                        statement.bindLong(4, chunk.sectionIndex.toLong())
                        statement.bindText(5, chunk.chapter)
                        statement.bindText(6, chunk.cfi)
                        statement.bindText(7, chunk.text)
                        statement.step()
                        statement.reset()
                    }
                }
                if (ftsAvailable) {
                    usePrepared("INSERT INTO $FTS_TABLE (rowid, terms, bookId) VALUES (?, ?, ?)") { statement ->
                        chunks.forEach { chunk ->
                            statement.bindLong(1, chunkId(bookId, chunk.ordinal))
                            statement.bindText(2, TextTokenizer.indexTerms(chunk.chapter + "\n" + chunk.text))
                            statement.bindLong(3, bookId)
                            statement.step()
                            statement.reset()
                        }
                    }
                }
            }
        }
    }

    /** Stores vectors for a batch of chunks in one transaction. */
    suspend fun saveVectors(vectors: List<Pair<Long, FloatArray>>) {
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                usePrepared("UPDATE chunks SET vector = ? WHERE id = ?") { statement ->
                    vectors.forEach { (id, vector) ->
                        statement.bindBlob(1, VectorMath.encode(vector))
                        statement.bindLong(2, id)
                        statement.step()
                        statement.reset()
                    }
                }
            }
        }
    }

    suspend fun deleteBook(bookId: Long) {
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (ftsAvailable) {
                    usePrepared("DELETE FROM $FTS_TABLE WHERE bookId = ?") { it.bindLong(1, bookId); it.step() }
                }
                usePrepared("DELETE FROM chunks WHERE bookId = ?") { it.bindLong(1, bookId); it.step() }
            }
        }
        dao.deleteHeader(bookId)
    }

    companion object {
        /** Deterministic ids let the FTS rowid equal the chunk id without a lookup. */
        fun chunkId(bookId: Long, ordinal: Int): Long = bookId * 1_000_000L + ordinal
    }
}
