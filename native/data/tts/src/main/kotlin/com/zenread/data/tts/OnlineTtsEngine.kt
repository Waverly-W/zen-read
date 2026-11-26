package com.zenread.data.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wraps a [SpeechSynthesizer] (Edge, OpenAI-compatible) into a [TtsEngine]: synthesizes the
 * next sentences ahead while the current one plays, then plays each clip with [ClipPlayer].
 *
 * Mirrors upstream `online_tts.dart`: the producer only *looks* at upcoming sentences
 * (`ttsCollectDetails`); the reader's cursor advances only after a clip finished playing.
 */
internal class OnlineTtsEngine(
    private val synthesizer: SpeechSynthesizer,
    private val player: ClipPlayer,
    private val prefetchCount: Int = DEFAULT_PREFETCH,
) : TtsEngine {
    override val type = synthesizer.type
    override val wantsPrefetch = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class Key(val text: String, val params: SpeechParams)

    /** In-flight or finished syntheses; only touched on the main thread. */
    private val cache = LinkedHashMap<Key, Deferred<ByteArray>>()

    override fun prefetch(upcoming: List<TtsSentence>, params: SpeechParams) {
        val keys = upcoming.asSequence()
            .filter { TtsText.isSpeakable(it.text) }
            .take(prefetchCount)
            .map { Key(TtsText.normalize(it.text), params) }
            .toList()
        PrefetchWindow.retain(cache, keys).forEach { it.cancel() }
        keys.forEach(::fetch)
    }

    private fun fetch(key: Key): Deferred<ByteArray> = cache.getOrPut(key) {
        scope.async(Dispatchers.IO) { synthesizeWithRetry(key) }
    }

    private suspend fun synthesizeWithRetry(key: Key): ByteArray {
        var attempt = 0
        while (true) {
            try {
                val bytes = synthesizer.synthesize(key.text, key.params.voice, key.params.rate, key.params.pitch)
                if (bytes.isEmpty()) throw TtsException(TtsException.Reason.INVALID_AUDIO, "Empty speech audio")
                return bytes
            } catch (e: TtsException) {
                if (!PrefetchWindow.retryable(e) || attempt >= MAX_RETRIES) throw e
                attempt++
                delay(RETRY_DELAY_MS * attempt)
            }
        }
    }

    override suspend fun speak(sentence: TtsSentence, params: SpeechParams, onProgress: (SpeechProgress) -> Unit) {
        val key = Key(TtsText.normalize(sentence.text), params)
        val audio = try {
            fetch(key).await()
        } catch (e: TtsException) {
            cache.remove(key) // Let a retry synthesize again.
            throw e
        }
        cache.remove(key)
        // A pause that arrived while the clip was still being synthesized holds it back.
        paused.first { !it }
        coroutineScope {
            val ticker = launch {
                var last: Float? = null
                while (isActive) {
                    delay(PROGRESS_INTERVAL_MS)
                    val fraction = player.fraction
                    // Paused clips report the same position; don't keep poking the page.
                    if (fraction != null && fraction != last) onProgress(SpeechProgress(fraction = fraction))
                    last = fraction
                }
            }
            try {
                player.play(audio)
            } finally {
                ticker.cancel()
            }
        }
    }

    private val paused = MutableStateFlow(false)

    override fun pause(): Boolean {
        paused.value = true
        player.pause()
        return true
    }

    override fun resume() {
        paused.value = false
        player.resume()
    }

    override suspend fun voices(): List<TtsVoice> = synthesizer.voices()

    override fun release() {
        scope.cancel()
        cache.clear()
        player.release()
    }

    companion object {
        const val DEFAULT_PREFETCH = 3
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 800L
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}

/** Pure prefetch bookkeeping, separated for unit tests. */
internal object PrefetchWindow {
    /**
     * Keeps only entries whose key is in [wanted] and returns the removed values (to cancel).
     * Entries for the sentence being played are always in [wanted], since it comes first.
     */
    fun <K, V> retain(cache: MutableMap<K, V>, wanted: Collection<K>): List<V> {
        val wantedSet = wanted.toSet()
        val dropped = cache.filterKeys { it !in wantedSet }
        dropped.keys.forEach(cache::remove)
        return dropped.values.toList()
    }

    /** Network hiccups, rate limits and server errors are retried; configuration errors are not. */
    fun retryable(e: TtsException): Boolean = when (e.reason) {
        TtsException.Reason.NETWORK, TtsException.Reason.INVALID_AUDIO -> true
        TtsException.Reason.HTTP -> e.statusCode.let { it == null || it == 429 || it >= 500 }
        TtsException.Reason.CONFIGURATION, TtsException.Reason.ENGINE_UNAVAILABLE -> false
    }
}
