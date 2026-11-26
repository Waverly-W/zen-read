package com.zenread.feature.reader.tts

import androidx.lifecycle.ViewModel
import com.zenread.core.model.Book
import com.zenread.data.tts.TtsBookInfo
import com.zenread.data.tts.TtsPlaybackController
import com.zenread.data.tts.TtsPlaybackState
import com.zenread.data.tts.TtsTextSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** The reader's handle on the app-wide read-aloud session. */
@HiltViewModel
class ReaderTtsViewModel @Inject constructor(
    private val controller: TtsPlaybackController,
) : ViewModel() {
    val state: StateFlow<TtsPlaybackState> = controller.state

    internal fun start(source: TtsTextSource, book: Book) =
        controller.start(source, TtsBookInfo(book.id, book.title, book.author, book.coverPath))

    fun togglePlayPause() = controller.togglePlayPause()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun stop() = controller.stop()
    fun setRate(rate: Float) = controller.setRate(rate)
    fun setSleepTimer(minutes: Int) = controller.setSleepTimer(minutes)
    fun clearError() = controller.clearError()
    fun onChapter(bookId: Long, title: String) = controller.onChapter(bookId, title)
    fun isReading(bookId: Long) = controller.isReading(bookId)

    internal fun attach(source: TtsTextSource) = controller.attach(source)
    internal fun detach(source: TtsTextSource, resumable: Boolean) = controller.detach(source, resumable)
}
