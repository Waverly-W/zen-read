package com.zenread.feature.reader.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zenread.data.stats.ReadingSession
import com.zenread.data.stats.ReadingStatsRepository
import com.zenread.data.tts.TtsPlaybackController
import com.zenread.data.tts.TtsStatus
import com.zenread.reader.engine.ReaderEvent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Feeds one reader's activity into reading statistics (ARCHITECTURE.md §14): the screen's
 * lifecycle, page turns and other interactions, read-aloud playback, and progress for the
 * "finished" check. Owned by `ReaderViewModel`; all calls on the main thread.
 */
class ReadingTimeTracker @Inject constructor(
    private val stats: ReadingStatsRepository,
    private val tts: TtsPlaybackController,
) {
    private var bookId = 0L
    private var session: ReadingSession? = null
    private var lastPageCfi: String? = null

    fun start(bookId: Long, scope: CoroutineScope) {
        if (session != null) return
        this.bookId = bookId
        val opened = stats.openSession(bookId, scope)
        session = opened
        // Listening counts even with the screen off, so follow the controller, not the UI.
        scope.launch {
            tts.state
                .map { it.status == TtsStatus.PLAYING && it.book?.bookId == bookId }
                .distinctUntilChanged()
                .collect { opened.setListening(it) }
        }
    }

    fun onEvent(event: ReaderEvent) {
        val session = session ?: return
        when (event) {
            is ReaderEvent.Relocated -> if (event.userAction) {
                // Scrolling reports many relocations within one page; count real moves only.
                if (event.cfi != lastPageCfi) session.onPageTurn() else session.onInteraction()
                lastPageCfi = event.cfi
                stats.reportProgress(bookId, event.fraction, event.section, event.sections, event.chapterPage, event.chapterPages)
            }
            is ReaderEvent.TtsProgress -> stats.reportProgress(bookId, event.fraction)
            is ReaderEvent.SelectionEnd, is ReaderEvent.AnnotationTap, is ReaderEvent.BookmarkRequest ->
                session.onInteraction()
            else -> Unit
        }
    }

    /** A tap that did not turn the page, e.g. showing the menu. */
    fun onInteraction() {
        session?.onInteraction()
    }

    fun setForeground(foreground: Boolean) {
        session?.setForeground(foreground)
    }

    fun stop() {
        session?.close()
        session = null
    }
}

/** Times reading while this screen is resumed. */
@Composable
internal fun ReadingTimeLifecycle(tracker: ReadingTimeTracker) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, tracker) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> tracker.setForeground(true)
                Lifecycle.Event.ON_PAUSE -> tracker.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tracker.setForeground(false)
        }
    }
}
