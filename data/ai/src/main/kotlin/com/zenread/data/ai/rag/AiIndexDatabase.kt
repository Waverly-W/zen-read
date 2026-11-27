package com.zenread.data.ai.rag

import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.RoomRawQuery
import androidx.room.Upsert
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/** One book's index header. [status] is one of [IndexStatus]'s db values. */
@Entity(tableName = "book_index")
data class BookIndexEntity(
    @PrimaryKey val bookId: Long,
    val title: String,
    /** Identity of the indexed file (the library's SHA-256); a changed file needs a rebuild. */
    val fileHash: String,
    val status: String,
    /** Embedding model the vectors came from; null for a keyword-only index. */
    val embeddingProviderId: String?,
    val embeddingModel: String?,
    val dimensions: Int,
    val chunkSize: Int,
    val chunkOverlap: Int,
    val chunkCount: Int,
    val embeddedCount: Int,
    val sectionCount: Int,
    val error: String?,
    val updatedAt: Long,
)

@Entity(tableName = "chunks", indices = [Index("bookId", "ordinal")])
data class ChunkEntity(
    /** [IndexStore.chunkId]; also the FTS5 rowid. */
    @PrimaryKey val id: Long,
    val bookId: Long,
    val ordinal: Int,
    val sectionIndex: Int,
    val chapter: String,
    val cfi: String,
    val text: String,
    /** Little-endian float32 ([VectorMath.encode]); null until embedded. */
    val vector: ByteArray?,
)

data class ChunkRow(
    val id: Long,
    val ordinal: Int,
    val sectionIndex: Int,
    val chapter: String,
    val cfi: String,
    val text: String,
)

data class ChunkText(val id: Long, val text: String)

data class ChunkVector(val id: Long, val vector: ByteArray)

data class FtsHit(val id: Long, val score: Double)

@Dao
interface IndexDao {
    @Query("SELECT * FROM book_index WHERE bookId = :bookId")
    fun observe(bookId: Long): Flow<BookIndexEntity?>

    @Query("SELECT * FROM book_index ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<BookIndexEntity>>

    @Query("SELECT * FROM book_index WHERE bookId = :bookId")
    suspend fun get(bookId: Long): BookIndexEntity?

    @Upsert
    suspend fun upsert(index: BookIndexEntity)

    @Query("UPDATE book_index SET status = :status, error = :error, embeddedCount = :embedded, updatedAt = :time WHERE bookId = :bookId")
    suspend fun setStatus(bookId: Long, status: String, error: String?, embedded: Int, time: Long)

    @Query("SELECT id, ordinal, sectionIndex, chapter, cfi, text FROM chunks WHERE id IN (:ids)")
    suspend fun chunks(ids: List<Long>): List<ChunkRow>

    @Query("SELECT id, text FROM chunks WHERE bookId = :bookId")
    suspend fun allTexts(bookId: Long): List<ChunkText>

    /** First chunk of every section, in reading order (for whole-book prompts). */
    @Query(
        "SELECT id, ordinal, sectionIndex, chapter, cfi, text FROM chunks c WHERE bookId = :bookId AND ordinal = " +
            "(SELECT MIN(ordinal) FROM chunks WHERE bookId = :bookId AND sectionIndex = c.sectionIndex) ORDER BY ordinal",
    )
    suspend fun sectionHeads(bookId: Long): List<ChunkRow>

    @Query("SELECT id, text FROM chunks WHERE bookId = :bookId AND vector IS NULL ORDER BY ordinal LIMIT :limit")
    suspend fun pendingVectors(bookId: Long, limit: Int): List<ChunkText>

    @Query("SELECT COUNT(*) FROM chunks WHERE bookId = :bookId AND vector IS NOT NULL")
    suspend fun embeddedCount(bookId: Long): Int

    @Query("SELECT id, vector FROM chunks WHERE bookId = :bookId AND vector IS NOT NULL")
    suspend fun vectors(bookId: Long): List<ChunkVector>

    @Query("UPDATE chunks SET vector = :vector WHERE id = :id")
    suspend fun setVector(id: Long, vector: ByteArray)

    @Query("UPDATE chunks SET vector = NULL WHERE bookId = :bookId")
    suspend fun clearVectors(bookId: Long)

    @Query("DELETE FROM book_index WHERE bookId = :bookId")
    suspend fun deleteHeader(bookId: Long)

    @RawQuery
    suspend fun ftsSearch(query: RoomRawQuery): List<FtsHit>
}

@Database(entities = [BookIndexEntity::class, ChunkEntity::class], version = 1, exportSchema = true)
abstract class AiIndexDatabase : RoomDatabase() {
    abstract fun index(): IndexDao

    /** Creates the FTS5 table Room cannot declare (Room only knows FTS3/4). */
    object FtsCallback : Callback() {
        @Volatile
        var ftsAvailable: Boolean = false
            private set

        override fun onOpen(connection: SQLiteConnection) {
            ftsAvailable = try {
                connection.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS $FTS_TABLE USING fts5(terms, bookId UNINDEXED, tokenize = 'unicode61 remove_diacritics 2')",
                )
                true
            } catch (e: Exception) {
                Log.w("ZenAiIndex", "FTS5 unavailable, keyword search falls back to in-memory BM25", e)
                false
            }
        }
    }

    companion object {
        const val FTS_TABLE = "chunk_fts"
    }
}
