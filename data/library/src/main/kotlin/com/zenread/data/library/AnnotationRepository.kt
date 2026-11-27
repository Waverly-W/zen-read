package com.zenread.data.library

import com.zenread.core.common.IoDispatcher
import com.zenread.core.database.AnnotationDao
import com.zenread.core.database.AnnotationEntity
import com.zenread.core.database.asModel
import com.zenread.core.model.Annotation
import com.zenread.core.model.AnnotationType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class AnnotationRepository @Inject constructor(
    private val dao: AnnotationDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun observeForBook(bookId: Long): Flow<List<Annotation>> =
        dao.observeForBook(bookId).map { list -> list.map { it.asModel() } }

    fun observeAll(): Flow<List<Annotation>> = dao.observeAll().map { list -> list.map { it.asModel() } }

    /**
     * Creates the mark, or restyles the existing one on the same range, and returns it.
     * A note passed here replaces the stored note; null keeps it.
     */
    suspend fun upsert(
        bookId: Long,
        type: AnnotationType,
        cfi: String,
        text: String,
        chapter: String,
        color: String,
        position: Float,
        note: String? = null,
    ): Annotation = withContext(io) {
        val now = System.currentTimeMillis()
        val existing = dao.find(bookId, type.name, cfi)
        val id = if (existing != null) {
            dao.updateStyle(existing.id, type.name, color, now)
            if (note != null) dao.updateNote(existing.id, note.ifBlank { null }, now)
            existing.id
        } else {
            dao.insert(
                AnnotationEntity(
                    bookId = bookId,
                    type = type.name,
                    cfi = cfi,
                    text = text,
                    chapter = chapter,
                    color = color,
                    note = note?.ifBlank { null },
                    position = position,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        checkNotNull(dao.get(id)).asModel()
    }

    suspend fun restyle(id: Long, type: AnnotationType, color: String): Annotation? = withContext(io) {
        dao.updateStyle(id, type.name, color, System.currentTimeMillis())
        dao.get(id)?.asModel()
    }

    suspend fun setNote(id: Long, note: String?): Annotation? = withContext(io) {
        dao.updateNote(id, note?.trim()?.ifBlank { null }, System.currentTimeMillis())
        dao.get(id)?.asModel()
    }

    suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }

    suspend fun deleteBookmark(bookId: Long, cfi: String) = withContext(io) {
        dao.delete(bookId, AnnotationType.BOOKMARK.name, cfi)
    }
}
