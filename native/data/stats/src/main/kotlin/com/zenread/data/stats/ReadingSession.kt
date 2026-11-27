package com.zenread.data.stats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Times one open book: a [ReadingTimer] plus a flush every [flushIntervalMs], so a killed
 * process loses at most that much. Stretches that end (pause, idle, mode switch) are written
 * at once. Call everything from [scope]'s thread (the reader uses the main thread).
 */
class ReadingSession internal constructor(
    private val timer: ReadingTimer,
    private val record: (List<ReadingSpan>) -> Unit,
    scope: CoroutineScope,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
) {
    internal constructor(bookId: Long, timer: ReadingTimer, repository: ReadingStatsRepository, scope: CoroutineScope) :
        this(timer, { spans -> repository.record(bookId, spans) }, scope)

    /** Last state written of the open stretch, to skip writes while nothing changes. */
    private var lastWritten: ReadingSpan? = null
    private var closed = false

    private val ticker: Job = scope.launch {
        while (isActive) {
            delay(flushIntervalMs)
            timer.tick()
            flush()
        }
    }

    fun setForeground(foreground: Boolean) = act { timer.setForeground(foreground) }

    fun setListening(listening: Boolean) = act { timer.setListening(listening) }

    fun onInteraction() = act { timer.onInteraction() }

    fun onPageTurn() = act { timer.onPageTurn() }

    /** Stops timing and writes what is left. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        ticker.cancel()
        timer.stop()
        flush()
    }

    private inline fun act(block: () -> Unit) {
        if (closed) return
        val before = timer.activeMode
        block()
        // A stretch just ended or began: write now rather than at the next tick.
        if (timer.activeMode != before) flush()
    }

    private fun flush() {
        val spans = timer.drain().filter { it != lastWritten }
        if (spans.isEmpty()) return
        lastWritten = spans.last()
        record(spans)
    }

    companion object {
        /** Well under the one minute a killed process may lose. */
        const val DEFAULT_FLUSH_INTERVAL_MS = 30_000L
    }
}
