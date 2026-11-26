package com.zenread.feature.reader.tts

import com.zenread.data.tts.SpeechProgress
import com.zenread.data.tts.TtsSentence
import com.zenread.data.tts.TtsTextSource
import com.zenread.reader.engine.ReaderController

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

    override suspend fun start(fromCfi: String?, paragraphMode: Boolean): TtsSentence? =
        if (disposed) null else page.ttsStart(fromCfi, paragraphMode)?.toSentence()

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
}
