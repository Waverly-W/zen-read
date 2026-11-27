package com.zenread.data.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationBatcherTest {
    private val requests = mutableListOf<List<String>>()

    private fun TestScope.batcher(
        scope: CoroutineScope = backgroundScope,
        maxItems: Int = 10,
        maxChars: Int = 1_000,
        translate: suspend (List<String>) -> List<String> = { texts -> texts.map { it.uppercase() } },
    ) = TranslationBatcher(scope, maxItems, maxChars, windowMillis = 40) { texts ->
        requests += texts
        translate(texts)
    }

    @Test
    fun concurrentParagraphsShareOneRequest() = runTest {
        val b = batcher()
        val results = listOf("one", "two", "three").map { async { b.translate(it) } }.awaitAll()
        assertEquals(listOf("ONE", "TWO", "THREE"), results)
        assertEquals(listOf(listOf("one", "two", "three")), requests)
    }

    @Test
    fun duplicatesAreTranslatedOnce() = runTest {
        val b = batcher()
        val results = listOf("same", "other", "same").map { async { b.translate(it) } }.awaitAll()
        assertEquals(listOf("SAME", "OTHER", "SAME"), results)
        assertEquals(listOf(listOf("same", "other")), requests)
    }

    @Test
    fun limitsSplitAWindowIntoSeveralRequests() = runTest {
        val b = batcher(maxItems = 2)
        listOf("a", "b", "c").map { async { b.translate(it) } }.awaitAll()
        assertEquals(listOf(listOf("a", "b"), listOf("c")), requests)
    }

    @Test
    fun laterParagraphsStartANewWindow() = runTest {
        val b = batcher()
        b.translate("first")
        b.translate("second")
        assertEquals(listOf(listOf("first"), listOf("second")), requests)
    }

    @Test
    fun failureReachesEveryWaiterOfTheBatch() = runTest {
        val b = batcher(translate = { throw TranslateException(TranslateException.Reason.QUOTA, "429") })
        val results = listOf("a", "b").map { async { runCatching { b.translate(it) } } }.awaitAll()
        results.forEach { assertTrue(it.exceptionOrNull() is TranslateException) }
    }

    @Test
    fun cancellingTheScopeReleasesWaiters() = runTest {
        val scope = CoroutineScope(SupervisorJob())
        val b = batcher(scope = scope, translate = { kotlinx.coroutines.awaitCancellation() })
        val waiter = async { runCatching { b.translate("stuck") } }
        advanceUntilIdle()
        scope.cancel()
        val result = waiter.await()
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun translateAfterCloseFailsInsteadOfHanging() = runTest {
        val scope = CoroutineScope(SupervisorJob()).apply { cancel() }
        val b = batcher(scope = scope)
        val result = runCatching { b.translate("late") }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}

class RateLimiterTest {
    @Test
    fun spacesRequestStarts() = runTest {
        val limiter = RateLimiter(300) { currentTime }
        val starts = mutableListOf<Long>()
        repeat(3) { launch { limiter.acquire(); starts += currentTime } }
        advanceUntilIdle()
        assertEquals(listOf(0L, 300L, 600L), starts)
    }

    @Test
    fun noWaitAfterAQuietPeriod() = runTest {
        val limiter = RateLimiter(300) { currentTime }
        limiter.acquire()
        kotlinx.coroutines.delay(1_000)
        val before = currentTime
        limiter.acquire()
        assertEquals(before, currentTime)
    }
}
