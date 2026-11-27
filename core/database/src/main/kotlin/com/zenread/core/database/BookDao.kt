package com.zenread.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    /** Recently read first, then newest imports. */
    @Query("SELECT * FROM books ORDER BY COALESCE(last_read_at, created_at) DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE file_hash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(book: BookEntity): Long

    @Query(
        "UPDATE books SET last_location = :location, progress = :progress, last_read_at = :readAt " +
            "WHERE id = :id",
    )
    suspend fun updateProgress(id: Long, location: String, progress: Float, readAt: Long)

    /**
     * Deletes the book (its annotations cascade) and records a tombstone keyed by the file
     * hash so the deletion syncs. Annotations get no tombstones: they stay in the cloud and
     * come back if the same file is imported again.
     */
    @Transaction
    suspend fun delete(id: Long) {
        val existing = get(id) ?: return
        insertTombstone(
            SyncTombstoneEntity(SyncTombstoneEntity.KIND_BOOK, existing.fileHash, System.currentTimeMillis()),
        )
        deleteRow(id)
    }

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: SyncTombstoneEntity)
}
