package com.zenread.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zenread.core.model.Book
import com.zenread.core.model.BookFormat

@Entity(
    tableName = "books",
    indices = [Index(value = ["file_hash"], unique = true)],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val format: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "cover_path") val coverPath: String?,
    @ColumnInfo(name = "file_hash") val fileHash: String,
    @ColumnInfo(name = "last_location") val lastLocation: String?,
    val progress: Float,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long?,
    /**
     * When the book's metadata (title, author, format) last changed; the sync clock for the
     * book row. Reading progress uses [lastReadAt] as its own clock. Added in schema v3.
     */
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = createdAt,
)

fun BookEntity.asModel() = Book(
    id = id,
    title = title,
    author = author,
    format = BookFormat.valueOf(format),
    filePath = filePath,
    coverPath = coverPath,
    fileHash = fileHash,
    lastLocation = lastLocation,
    progress = progress,
    createdAt = createdAt,
    lastReadAt = lastReadAt,
)
