package com.zenread.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Reading statistics (`data:stats`). These tables are not synced. */
@Dao
interface ReadingStatsDao {
    /** Inserts the stretch, or extends it when an earlier flush already wrote it. */
    @Upsert
    suspend fun upsertSession(session: ReadingSessionEntity)

    /** Records the first finish only; later ones are ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFinish(finish: BookFinishEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM book_finishes WHERE book_id = :bookId)")
    suspend fun isFinished(bookId: Long): Boolean

    @Query(
        "SELECT local_day AS day, mode, SUM(ended_at - started_at) AS durationMs, SUM(pages) AS pages " +
            "FROM reading_sessions GROUP BY local_day, mode ORDER BY local_day",
    )
    fun observeDayTotals(): Flow<List<DayModeTotalRow>>

    @Query(
        "SELECT s.book_id AS bookId, b.title AS title, b.author AS author, b.cover_path AS coverPath, " +
            "SUM(s.ended_at - s.started_at) AS totalMs, " +
            "SUM(CASE WHEN s.mode = 'LISTEN' THEN s.ended_at - s.started_at ELSE 0 END) AS listenMs, " +
            "MAX(s.ended_at) AS lastReadAt, " +
            "EXISTS(SELECT 1 FROM book_finishes f WHERE f.book_id = s.book_id) AS finished " +
            "FROM reading_sessions s INNER JOIN books b ON b.id = s.book_id " +
            "GROUP BY s.book_id ORDER BY totalMs DESC",
    )
    fun observeBookTotals(): Flow<List<BookReadingRow>>

    @Query("SELECT COUNT(*) FROM book_finishes")
    fun observeFinishedCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(ended_at - started_at), 0) FROM reading_sessions WHERE book_id = :bookId")
    fun observeBookTotal(bookId: Long): Flow<Long>

    @Query("SELECT * FROM reading_sessions ORDER BY started_at")
    suspend fun sessions(): List<ReadingSessionEntity>
}
