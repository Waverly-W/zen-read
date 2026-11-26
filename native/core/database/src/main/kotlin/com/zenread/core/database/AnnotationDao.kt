package com.zenread.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE book_id = :bookId ORDER BY position, created_at")
    fun observeForBook(bookId: Long): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations ORDER BY book_id, position, created_at")
    fun observeAll(): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE id = :id")
    suspend fun get(id: Long): AnnotationEntity?

    @Query("SELECT * FROM annotations WHERE book_id = :bookId AND type = :type AND cfi = :cfi")
    suspend fun find(bookId: Long, type: String, cfi: String): AnnotationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(annotation: AnnotationEntity): Long

    @Query("UPDATE annotations SET color = :color, type = :type, updated_at = :now WHERE id = :id")
    suspend fun updateStyle(id: Long, type: String, color: String, now: Long)

    @Query("UPDATE annotations SET note = :note, updated_at = :now WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?, now: Long)

    /** Deletes the annotation and records a tombstone so the deletion syncs. */
    @Transaction
    suspend fun delete(id: Long) {
        val existing = get(id) ?: return
        insertTombstone(existing.tombstone())
        deleteRow(existing.id)
    }

    /** Deletes the mark on that exact range and records a tombstone so the deletion syncs. */
    @Transaction
    suspend fun delete(bookId: Long, type: String, cfi: String) {
        val existing = find(bookId, type, cfi) ?: return
        insertTombstone(existing.tombstone())
        deleteRow(existing.id)
    }

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: SyncTombstoneEntity)
}

private fun AnnotationEntity.tombstone() =
    SyncTombstoneEntity(SyncTombstoneEntity.KIND_ANNOTATION, uuid, System.currentTimeMillis())
