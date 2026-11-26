package com.zenread.data.tts

/**
 * Supplies sentences in reading order and presents the one being spoken (highlight, page
 * follow). The reader implements it on top of foliate-js (`ttsHere`, `ttsNext`, …), whose
 * cursor lives in the WebView; only the playback loop moves it.
 *
 * All calls happen on the main thread.
 */
interface TtsTextSource {
    /** Book the sentences come from. */
    val bookId: Long

    /**
     * Places the cursor at [fromCfi], or at the start of the visible page when null, and returns
     * that sentence (highlighted). [paragraphMode] groups sentences into larger passages,
     * which online engines prefer. Returns null when there is nothing to read.
     */
    suspend fun start(fromCfi: String?, paragraphMode: Boolean): TtsSentence?

    /** Advances the cursor (across chapters) and returns the new sentence, or null at the end. */
    suspend fun next(): TtsSentence?

    /** Moves the cursor back and returns that sentence, or null at the beginning. */
    suspend fun prev(): TtsSentence?

    /** The current sentence followed by the next ones, without moving the cursor. */
    suspend fun peek(count: Int): List<TtsSentence>

    /** Follows the spoken position inside [sentence] on the page. Fire-and-forget. */
    fun followProgress(sentence: TtsSentence, progress: SpeechProgress)

    /**
     * True while the app is in the background: the page stops redrawing highlights and turning
     * pages (it cannot lay out anyway), but the cursor keeps moving.
     */
    fun setBackground(background: Boolean)

    /** Clears the highlight and drops the cursor. */
    fun stop()
}

/** What the notification shows. */
data class TtsBookInfo(
    val bookId: Long,
    val title: String,
    val author: String,
    val coverPath: String?,
)
