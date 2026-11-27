package com.zenread.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zenread.core.model.Annotation
import com.zenread.core.model.AnnotationType
import java.util.UUID

@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // One mark per range and type; re-marking the same text updates it.
    indices = [
        Index(value = ["book_id", "type", "cfi"], unique = true),
        Index(value = ["uuid"], unique = true),
    ],
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    val type: String,
    val cfi: String,
    val text: String,
    val chapter: String,
    val color: String,
    val note: String?,
    val position: Float,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Stable id shared by every device (sync identity). Added in schema v3. */
    @ColumnInfo(defaultValue = "''") val uuid: String = UUID.randomUUID().toString(),
)

fun AnnotationEntity.asModel() = Annotation(
    id = id,
    bookId = bookId,
    type = AnnotationType.valueOf(type),
    cfi = cfi,
    text = text,
    chapter = chapter,
    color = color,
    note = note,
    position = position,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
