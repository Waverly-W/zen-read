package com.zenread.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Raw access for the sync engine (`data:sync`). Unlike [BookDao] and [AnnotationDao] these
 * writes record no tombstones and keep the clocks they are given: they apply changes that
 * were made on another device. Call them inside a transaction.
 */
@Dao
interface SyncDao {
    @Query("SELECT * FROM books")
    suspend fun books(): List<BookEntity>

    @Query("SELECT * FROM annotations")
    suspend fun annotations(): List<AnnotationEntity>

    @Query("SELECT * FROM sync_tombstones")
    suspend fun tombstones(): List<SyncTombstoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(tombstone: SyncTombstoneEntity)

    @Query("DELETE FROM sync_tombstones WHERE kind = :kind AND sync_id = :syncId")
    suspend fun deleteTombstone(kind: String, syncId: String)

    @Query("SELECT * FROM books WHERE file_hash = :hash LIMIT 1")
    suspend fun bookByHash(hash: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun bookById(id: Long): BookEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBook(book: BookEntity): Long

    @Query(
        "UPDATE books SET title = :title, author = :author, format = :format, created_at = :createdAt, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateBookMetadata(id: Long, title: String, author: String, format: String, createdAt: Long, updatedAt: Long)

    @Query("UPDATE books SET last_location = :location, progress = :progress, last_read_at = :readAt WHERE id = :id")
    suspend fun updateProgress(id: Long, location: String, progress: Float, readAt: Long)

    @Query("UPDATE books SET cover_path = :coverPath WHERE id = :id")
    suspend fun updateCoverPath(id: Long, coverPath: String?)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: Long)

    @Query("SELECT * FROM annotations WHERE uuid = :uuid LIMIT 1")
    suspend fun annotationByUuid(uuid: String): AnnotationEntity?

    @Query("SELECT * FROM annotations WHERE book_id = :bookId AND type = :type AND cfi = :cfi LIMIT 1")
    suspend fun annotationByRange(bookId: Long, type: String, cfi: String): AnnotationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnnotation(annotation: AnnotationEntity): Long

    @Update
    suspend fun updateAnnotation(annotation: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE uuid = :uuid")
    suspend fun deleteAnnotation(uuid: String)
}
