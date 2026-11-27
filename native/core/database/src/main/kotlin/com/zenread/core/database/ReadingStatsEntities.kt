package com.zenread.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One continuous stretch of reading (or listening) of a book, always within one local day:
 * a stretch that crosses midnight is stored as two rows. Added in schema v4.
 *
 * While the stretch is still going on, the row is rewritten with a later [endedAt] on every
 * flush, keyed by [uuid]. Times are UTC epoch milliseconds; [localDay] is the device's local
 * date at [startedAt] (`LocalDate.toEpochDay()`), fixed at write time so a later time-zone
 * change does not move past reading to another day. Not synced yet; [uuid] is its future
 * sync id.
 */
@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["book_id"]), Index(value = ["local_day"])],
)
data class ReadingSessionEntity(
    @PrimaryKey val uuid: String,
    @ColumnInfo(name = "book_id") val bookId: Long,
    /** [MODE_READ] or [MODE_LISTEN]. */
    val mode: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
    @ColumnInfo(name = "local_day") val localDay: Long,
    /** UTC offset of the device's zone at [startedAt], for reference. */
    @ColumnInfo(name = "utc_offset_minutes") val utcOffsetMinutes: Int,
    /** Pages turned by the user during the stretch. */
    val pages: Int,
) {
    companion object {
        const val MODE_READ = "READ"
        const val MODE_LISTEN = "LISTEN"
    }
}

/** The first time this device saw [bookId] read to the end. Added in schema v4. */
@Entity(
    tableName = "book_finishes",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["uuid"], unique = true)],
)
data class BookFinishEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: Long,
    val uuid: String,
    @ColumnInfo(name = "finished_at") val finishedAt: Long,
    @ColumnInfo(name = "local_day") val localDay: Long,
)

/** Reading time of one local day in one mode (see [ReadingStatsDao.observeDayTotals]). */
data class DayModeTotalRow(
    val day: Long,
    val mode: String,
    val durationMs: Long,
    val pages: Int,
)

/** Reading time per book, for the ranking on the statistics page. */
data class BookReadingRow(
    val bookId: Long,
    val title: String,
    val author: String,
    val coverPath: String?,
    val totalMs: Long,
    val listenMs: Long,
    val lastReadAt: Long,
    val finished: Boolean,
)
