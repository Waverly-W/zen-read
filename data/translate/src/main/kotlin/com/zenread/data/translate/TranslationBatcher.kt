package com.zenread.data.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Collects the paragraphs foliate-js asks for within [windowMillis] and sends them as as few
 * requests as the service's limits allow. Identical paragraphs in one window are translated
 * once. Requests of one window run in order, so the translator's rate limiter paces them.
 *
 * Cancelling [scope] fails every waiting caller with a `CancellationException` instead of
 * leaving it suspended: foliate-js translates one queue at a time and would otherwise stall.
 */
class TranslationBatcher(
    private val scope: CoroutineScope,
    private val maxItems: Int,
    private val maxChars: Int,
    private val windowMillis: Long = 40,
    private val request: suspend (List<String>) -> List<String>,
) {
    private class Pending(val text: String, val result: CompletableDeferred<String> = CompletableDeferred())

    private val pending = mutableListOf<Pending>()
    private var scheduled = false

    suspend fun translate(text: String): String {
        val item = Pending(text)
        val schedule = synchronized(pending) {
            pending += item
            !scheduled.also { scheduled = true }
        }
        if (schedule) {
            // ATOMIC: the block runs (and its finally fails the callers) even if the scope is already cancelled.
            scope.launch(start = CoroutineStart.ATOMIC) {
                var taken = emptyList<Pending>()
                try {
                    delay(windowMillis)
                    taken = take()
                    flush(taken)
                } finally {
                    if (taken.isEmpty()) taken = take()
                    taken.forEach { it.result.cancel() } // No-op for completed ones.
                }
            }
        }
        return item.result.await()
    }

    private fun take(): List<Pending> = synchronized(pending) {
        pending.toList().also {
            pending.clear()
            scheduled = false
        }
    }

    private suspend fun flush(items: List<Pending>) {
        val byText = items.groupBy { it.text } // Keeps first-seen order.
        for (batch in TextChunks.batch(byText.keys.toList(), maxItems, maxChars) { it.length }) {
            try {
                val results = request(batch)
                check(results.size == batch.size) { "Translator returned ${results.size} of ${batch.size}" }
                batch.forEachIndexed { i, text -> byText.getValue(text).forEach { it.result.complete(results[i]) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                batch.forEach { text -> byText.getValue(text).forEach { it.result.completeExceptionally(e) } }
            }
        }
    }
}
