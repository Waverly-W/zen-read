package com.zenread.data.stats

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class ReadingMode { READ, LISTEN }

/**
 * One continuous stretch of reading or listening, always within one local day.
 *
 * @param id stable while the stretch grows, so repeated flushes rewrite the same row.
 * @param startedAt UTC epoch milliseconds.
 * @param localDay local date of [startedAt] as `LocalDate.toEpochDay()`.
 * @param utcOffsetMinutes offset of the zone [localDay] was computed in.
 */
data class ReadingSpan(
    val id: String,
    val mode: ReadingMode,
    val startedAt: Long,
    val endedAt: Long,
    val localDay: Long,
    val utcOffsetMinutes: Int,
    val pages: Int,
) {
    val durationMs: Long get() = endedAt - startedAt
}

/**
 * Decides which time counts as reading. Pure Kotlin; all time comes from [clock] (its millis
 * and its zone), so tests drive it with a fake clock. Not thread-safe: call it from one
 * thread (the reader uses the main thread).
 *
 * Rules (see ARCHITECTURE.md §14):
 * - **Reading** counts while the reader is in the foreground ([setForeground]) and the last
 *   interaction ([onInteraction], [onPageTurn]) is less than [idleTimeoutMs] ago. Past that,
 *   the stretch ends at last interaction + [idleTimeoutMs]; the next interaction starts a new one.
 *   Coming to the foreground and stopping read-aloud count as interactions.
 * - **Listening** counts the whole time read-aloud plays ([setListening]), in the foreground
 *   or not, and takes precedence over reading.
 * - A stretch never crosses local midnight: it is closed at midnight and a new one starts.
 *
 * Callers take finished and in-progress stretches with [drain] and persist them.
 */
class ReadingTimer(
    private val clock: Clock,
    val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private class Open(
        val id: String,
        val mode: ReadingMode,
        val startedAt: Long,
        val localDay: Long,
        val zone: ZoneId,
        val utcOffsetMinutes: Int,
        var endedAt: Long,
        var pages: Int,
    ) {
        fun toSpan() = ReadingSpan(id, mode, startedAt, endedAt, localDay, utcOffsetMinutes, pages)
    }

    private var foreground = false
    private var listening = false
    private var lastInteractionAt = Long.MIN_VALUE
    private var open: Open? = null
    private val closed = mutableListOf<ReadingSpan>()

    /** Mode being timed right now, or null when the timer is idle. */
    val activeMode: ReadingMode? get() = open?.mode

    /** The reader screen became resumed (true) or paused (false). */
    fun setForeground(value: Boolean) = update { now ->
        if (value && !foreground) lastInteractionAt = now
        foreground = value
    }

    /** Read-aloud started (true) or paused/stopped (false) on this book. */
    fun setListening(value: Boolean) = update { now ->
        // After listening, the reader gets a fresh idle window instead of stopping at once.
        if (listening && !value && foreground) lastInteractionAt = now
        listening = value
    }

    /** A tap, selection or other sign of an attentive reader. */
    fun onInteraction() = update { now -> lastInteractionAt = now }

    /** The user turned a page: an interaction that is also counted. */
    fun onPageTurn() {
        update { now -> lastInteractionAt = now }
        open?.let { it.pages++ }
    }

    /** Re-evaluates idle time and midnight; call periodically. */
    fun tick() = update { }

    /** Ends the current stretch, e.g. when the reader closes. */
    fun stop() {
        val now = clock.millis()
        advance(now)
        foreground = false
        listening = false
        close()
    }

    /**
     * Stretches to persist: every stretch closed since the last call, followed by the open
     * one (if it has any time or pages yet). The open one is reported again on later calls
     * with the same id and a later end.
     */
    fun drain(): List<ReadingSpan> {
        val result = ArrayList<ReadingSpan>(closed.size + 1)
        result += closed
        closed.clear()
        open?.takeIf { it.endedAt > it.startedAt || it.pages > 0 }?.let { result += it.toSpan() }
        return result
    }

    private inline fun update(change: (now: Long) -> Unit) {
        val now = clock.millis()
        advance(now)
        change(now)
        reconcile(now)
    }

    /** Extends the open stretch up to [now] (or the idle limit), splitting it at midnight. */
    private fun advance(now: Long) {
        var span = open ?: return
        val activeUntil = when (span.mode) {
            ReadingMode.LISTEN -> if (listening) now else span.endedAt
            ReadingMode.READ -> if (foreground) minOf(now, idleDeadline()) else span.endedAt
        }
        val end = maxOf(span.endedAt, activeUntil)
        while (true) {
            val midnight = startOfDayMillis(span.localDay + 1, span.zone)
            if (end <= midnight) break
            span.endedAt = midnight
            close()
            span = start(span.mode, midnight).also { open = it }
        }
        span.endedAt = end
        // An idle reading stretch is over even if the reader comes back right now: the gap
        // must not be bridged by the next interaction.
        if (span.mode == ReadingMode.READ && now >= idleDeadline()) close()
    }

    /** Closes or opens a stretch so the open one matches what should be timed at [now]. */
    private fun reconcile(now: Long) {
        val wanted = when {
            listening -> ReadingMode.LISTEN
            foreground && now < idleDeadline() -> ReadingMode.READ
            else -> null
        }
        if (open?.mode == wanted) return
        close()
        if (wanted != null) open = start(wanted, now)
    }

    private fun idleDeadline(): Long =
        if (lastInteractionAt == Long.MIN_VALUE) Long.MIN_VALUE else lastInteractionAt + idleTimeoutMs

    private fun close() {
        val span = open ?: return
        open = null
        if (span.endedAt > span.startedAt || span.pages > 0) closed += span.toSpan()
    }

    private fun start(mode: ReadingMode, at: Long): Open {
        val zone = clock.zone
        val instant = Instant.ofEpochMilli(at)
        return Open(
            id = newId(),
            mode = mode,
            startedAt = at,
            localDay = instant.atZone(zone).toLocalDate().toEpochDay(),
            zone = zone,
            utcOffsetMinutes = zone.rules.getOffset(instant).totalSeconds / 60,
            endedAt = at,
            pages = 0,
        )
    }

    companion object {
        /** Three minutes without a page turn or tap ends a reading stretch. */
        const val DEFAULT_IDLE_TIMEOUT_MS = 3 * 60_000L

        internal fun startOfDayMillis(epochDay: Long, zone: ZoneId): Long =
            LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
