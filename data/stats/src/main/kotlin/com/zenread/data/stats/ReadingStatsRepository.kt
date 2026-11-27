package com.zenread.data.stats

import android.util.Log
import com.zenread.core.common.ApplicationScope
import com.zenread.core.common.IoDispatcher
import com.zenread.core.database.BookFinishEntity
import com.zenread.core.database.ReadingSessionEntity
import com.zenread.core.database.ReadingStatsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Reading statistics: records what [ReadingSession]s time and reports totals.
 *
 * Writes go through one queue drained in the application scope, so they keep their order
 * (a stretch's row only ever grows) and survive the reader screen closing.
 */
@Singleton
class ReadingStatsRepository @Inject constructor(
    private val dao: ReadingStatsDao,
    @StatsClock private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** Books known to be finished, so progress reports stop hitting the database. */
    private val finished = java.util.Collections.synchronizedSet(HashSet<Long>())

    init {
        appScope.launch(io) {
            for (write in writes) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // E.g. the book was deleted while its reader was open (foreign key).
                    Log.w(TAG, "Dropped a reading statistics write", e)
                }
            }
        }
    }

    /** Starts timing [bookId]; the session flushes in [scope] until [ReadingSession.close]. */
    fun openSession(bookId: Long, scope: CoroutineScope): ReadingSession =
        ReadingSession(bookId, ReadingTimer(clock), this, scope)

    /** Persists stretches from a [ReadingTimer]; rewriting a stretch with the same id extends it. */
    fun record(bookId: Long, spans: List<ReadingSpan>) {
        if (spans.isEmpty()) return
        writes.trySend { spans.forEach { dao.upsertSession(it.toEntity(bookId)) } }
    }

    /**
     * The reader moved to [fraction] of the book; records the first finish (see [FinishRule]).
     * Section numbers are 1-based, as in `ReaderEvent.Relocated`.
     */
    fun reportProgress(bookId: Long, fraction: Float, section: Int = 0, sections: Int = 0, chapterPage: Int = 0, chapterPages: Int = 0) {
        if (bookId in finished) return
        if (!FinishRule.isFinished(fraction, section, sections, chapterPage, chapterPages)) return
        finished += bookId
        val now = clock.millis()
        val day = Instant.ofEpochMilli(now).atZone(clock.zone).toLocalDate().toEpochDay()
        writes.trySend { dao.insertFinish(BookFinishEntity(bookId, UUID.randomUUID().toString(), now, day)) }
    }

    /** Totals per local day, keyed by epoch day. */
    fun observeDays(): Flow<Map<Long, DayTotal>> = dao.observeDayTotals().map { rows ->
        ReadingStats.aggregateByDay(
            rows.mapNotNull { row ->
                val mode = runCatching { ReadingMode.valueOf(row.mode) }.getOrNull() ?: return@mapNotNull null
                DayModeTotal(row.day, mode, row.durationMs, row.pages)
            },
        )
    }

    /** Books with any recorded time, most read first. */
    fun observeBooks(): Flow<List<BookReading>> = dao.observeBookTotals().map { rows ->
        rows.map {
            BookReading(it.bookId, it.title, it.author, it.coverPath, it.totalMs, it.listenMs, it.lastReadAt, it.finished)
        }
    }

    fun observeFinishedCount(): Flow<Int> = dao.observeFinishedCount()

    fun observeBookTotal(bookId: Long): Flow<Long> = dao.observeBookTotal(bookId)

    /** Today's local date as an epoch day, in the device's current zone. */
    fun today(): Long = Instant.ofEpochMilli(clock.millis()).atZone(clock.zone).toLocalDate().toEpochDay()

    private fun ReadingSpan.toEntity(bookId: Long) = ReadingSessionEntity(
        uuid = id,
        bookId = bookId,
        mode = when (mode) {
            ReadingMode.READ -> ReadingSessionEntity.MODE_READ
            ReadingMode.LISTEN -> ReadingSessionEntity.MODE_LISTEN
        },
        startedAt = startedAt,
        endedAt = endedAt,
        localDay = localDay,
        utcOffsetMinutes = utcOffsetMinutes,
        pages = pages,
    )

    private companion object {
        const val TAG = "ReadingStats"
    }
}

/** The clock reading statistics use; tests replace it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StatsClock

/**
 * The system clock in the device's *current* zone. `Clock.systemDefaultZone()` would keep
 * the zone it was created in, so days would stay wrong after the user travels.
 */
internal object CurrentZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = system(zone)
    override fun instant(): Instant = Instant.now()
    override fun millis(): Long = System.currentTimeMillis()
}

@Module
@InstallIn(SingletonComponent::class)
object StatsModule {
    @Provides
    @StatsClock
    fun clock(): Clock = CurrentZoneClock
}
