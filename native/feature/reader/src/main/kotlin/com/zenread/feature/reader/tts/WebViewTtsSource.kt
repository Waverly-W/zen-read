package com.zenread.feature.reader.tts

import com.zenread.data.tts.SpeechProgress
import com.zenread.data.tts.TtsSentence
import com.zenread.data.tts.TtsTextSource
import com.zenread.reader.engine.ReaderController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Feeds read-aloud from foliate-js's speech cursor in the reader's WebView (see `book.js`
 * `ttsHere` / `ttsNext` / `ttsCollectDetails` and `tts-navigation.js`). Chapters are parsed
 * off-screen by foliate-js, so the cursor keeps moving while the page is hidden.
 *
 * After [dispose] (the WebView is being destroyed) every call is a no-op.
 */
internal class WebViewTtsSource(
    override val bookId: Long,
    private val page: ReaderController,
) : TtsTextSource {
    @Volatile
    private var disposed = false

    fun dispose() {
        disposed = true
    }

    override suspend fun start(fromCfi: String?, paragraphMode: Boolean): TtsSentence? {
        if (disposed || !awaitBook()) return null
        return page.ttsStart(fromCfi, paragraphMode)?.toSentence()
    }

    /**
     * Waits until foliate-js has opened the book. After a configuration change the reader's
     * "ready" state survives in the view model while the new page is still loading.
     */
    private suspend fun awaitBook(): Boolean {
        repeat(READY_ATTEMPTS) {
            if (disposed) return false
            val ready = try {
                page.evaluateAsync(READY_PROBE, timeoutMs = 1_500)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if ((ready as? JsonPrimitive)?.booleanOrNull == true) return true
            delay(300)
        }
        return false
    }

    override suspend fun next(): TtsSentence? = if (disposed) null else page.ttsNext()?.toSentence()

    override suspend fun prev(): TtsSentence? = if (disposed) null else page.ttsPrev()?.toSentence()

    override suspend fun peek(count: Int): List<TtsSentence> =
        if (disposed) emptyList() else page.ttsCollect(count).map { it.toSentence() }

    override fun followProgress(sentence: TtsSentence, progress: SpeechProgress) {
        if (!disposed) page.ttsFollowProgress(sentence.cfi, sentence.text, progress.offset, progress.fraction)
    }

    override fun setBackground(background: Boolean) {
        if (!disposed) page.ttsSetBackground(background)
    }

    override fun stop() {
        if (!disposed) page.ttsStop()
    }

    private fun ReaderController.TtsDetail.toSentence() = TtsSentence(text = text, cfi = cfi, section = section)

    private companion object {
        const val READY_ATTEMPTS = 12
        const val READY_PROBE =
            "return typeof reader !== 'undefined' && !!reader?.view?.book && typeof window.ttsHere === 'function'"
    }
}
