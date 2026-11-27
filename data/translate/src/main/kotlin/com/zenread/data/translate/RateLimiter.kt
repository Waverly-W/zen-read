package com.zenread.data.translate

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces request starts at least [minIntervalMillis] apart. Callers queue in order (the
 * mutex is fair), so a burst of page paragraphs becomes a steady trickle instead of
 * tripping the service's rate limit.
 */
class RateLimiter(
    private val minIntervalMillis: Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private var last: Long? = null

    suspend fun acquire() {
        if (minIntervalMillis <= 0) return
        mutex.withLock {
            last?.let { previous ->
                val wait = previous + minIntervalMillis - now()
                if (wait > 0) delay(wait)
            }
            last = now()
        }
    }
}
