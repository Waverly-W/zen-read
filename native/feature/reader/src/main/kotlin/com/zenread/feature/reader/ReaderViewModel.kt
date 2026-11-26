package com.zenread.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.core.datastore.ReaderSettingsRepository
import com.zenread.core.model.Annotation
import com.zenread.core.model.AnnotationType
import com.zenread.core.model.Book
import com.zenread.core.model.HighlightColor
import com.zenread.core.model.ReaderSettings
import com.zenread.core.model.TocItem
import com.zenread.data.library.AnnotationRepository
import com.zenread.data.library.BookRepository
import com.zenread.reader.engine.BookFileRegistry
import com.zenread.reader.engine.Cfi
import com.zenread.reader.engine.PageRect
import com.zenread.reader.engine.ReaderEvent
import com.zenread.reader.engine.ReaderWebViewFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderUiState(
    val book: Book? = null,
    /** URL the page fetches the book from; null until registered. */
    val bookUrl: String? = null,
    /** Position to open at. Captured once so re-creating the WebView resumes in place. */
    val initialLocation: String? = null,
    val error: ReaderError? = null,
    val ready: Boolean = false,
    val toc: List<TocItem> = emptyList(),
    val location: ReaderEvent.Relocated? = null,
    val chromeVisible: Boolean = false,
    /** Current text selection; drives the selection menu. */
    val selection: ReaderEvent.SelectionEnd? = null,
    /** Highlight or underline the user tapped; drives the annotation menu. */
    val tapped: TappedAnnotation? = null,
    /** Annotation whose note is being edited. */
    val editingNoteId: Long? = null,
)

data class TappedAnnotation(val annotation: Annotation, val rect: PageRect)

enum class ReaderError { BOOK_MISSING, WEBVIEW_UNSUPPORTED, LOAD_FAILED, RENDERER_GONE }

/** Instructions for the page, applied by the screen through its `ReaderController`. */
sealed interface PageCommand {
    data class ReplaceAnnotations(val annotations: List<Annotation>) : PageCommand
    data class Show(val annotation: Annotation) : PageCommand
    data class Hide(val cfi: String) : PageCommand
    data class GoTo(val cfi: String) : PageCommand
    data object ClearSelection : PageCommand
    data object BookmarkHere : PageCommand
}

@HiltViewModel(assistedFactory = ReaderViewModel.Factory::class)
class ReaderViewModel @AssistedInject constructor(
    @Assisted private val bookId: Long,
    @Assisted private val openAt: String?,
    private val books: BookRepository,
    private val annotationRepository: AnnotationRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val files: BookFileRegistry,
    val webViewFactory: ReaderWebViewFactory,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        /** [openAt] is a CFI to open at instead of the saved position (e.g. from the notes list). */
        fun create(bookId: Long, openAt: String?): ReaderViewModel
    }

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    val settings: StateFlow<ReaderSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val annotations: StateFlow<List<Annotation>> = annotationRepository.observeForBook(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _commands = Channel<PageCommand>(Channel.UNLIMITED)
    val commands: Flow<PageCommand> = _commands.receiveAsFlow()

    private var registration: BookFileRegistry.Registration? = null

    init {
        viewModelScope.launch {
            val book = books.getBook(bookId)
            val file = book?.let { File(it.filePath) }
            when {
                book == null || file?.exists() != true ->
                    _state.update { it.copy(error = ReaderError.BOOK_MISSING) }
                !webViewFactory.isSupported ->
                    _state.update { it.copy(book = book, error = ReaderError.WEBVIEW_UNSUPPORTED) }
                else -> {
                    val reg = files.register(file, book.format.primaryExtension)
                    registration = reg
                    _state.update {
                        it.copy(book = book, bookUrl = reg.url, initialLocation = openAt ?: book.lastLocation)
                    }
                }
            }
        }
    }

    fun onEvent(event: ReaderEvent) {
        when (event) {
            ReaderEvent.LoadEnd -> _state.update { it.copy(ready = true, error = null) }
            is ReaderEvent.LoadError -> _state.update { it.copy(error = ReaderError.LOAD_FAILED) }
            is ReaderEvent.TocLoaded -> _state.update { it.copy(toc = event.items) }
            is ReaderEvent.Relocated -> onRelocated(event)
            is ReaderEvent.Tap -> Unit // Handled by the screen, which owns the page controller.
            is ReaderEvent.SelectionEnd ->
                _state.update { it.copy(selection = event, tapped = null, chromeVisible = false) }
            ReaderEvent.SelectionCleared -> _state.update { it.copy(selection = null) }
            is ReaderEvent.AnnotationTap -> {
                val annotation = annotations.value.firstOrNull { it.id == event.id } ?: return
                _state.update { it.copy(tapped = TappedAnnotation(annotation, event.rect), chromeVisible = false) }
            }
            is ReaderEvent.BookmarkRequest -> onBookmarkRequest(event)
            is ReaderEvent.BookmarkState -> setBookmarkCfi(event.cfi)
            is ReaderEvent.TranslateRequest -> Unit // Answered through the bridge by ReaderTranslationViewModel.
            ReaderEvent.AnnotationsWanted -> viewModelScope.launch {
                // Wait for Room's first emission so a fresh page never gets an empty list.
                val list = annotationRepository.observeForBook(bookId).first()
                _commands.send(PageCommand.ReplaceAnnotations(list))
            }
            is ReaderEvent.TtsChapter -> _ttsChapters.tryEmit(event.chapterTitle)
            is ReaderEvent.TtsProgress -> onTtsProgress(event)
        }
    }

    // --- Read aloud (phase 3) ------------------------------------------------------------------

    private val _ttsChapters = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Chapter titles the read-aloud cursor enters; shown in the playback notification. */
    val ttsChapters: SharedFlow<String> = _ttsChapters.asSharedFlow()

    /** The speech cursor moved; save it, since a hidden page does not relocate. */
    private fun onTtsProgress(event: ReaderEvent.TtsProgress) {
        val position = Cfi.collapseToStart(event.cfi)
        _state.update { it.copy(initialLocation = position) }
        books.saveProgress(bookId, position, event.fraction)
    }

    private fun onRelocated(event: ReaderEvent.Relocated) {
        _state.update { it.copy(location = event) }
        // Only real moves (page turns, jumps, user scrolling) are saved. Re-layouts after
        // a resize or style change report a shifted range; saving those drifts the position,
        // e.g. while the window shrinks during the exit transition.
        if (event.userAction) {
            val position = Cfi.collapseToStart(event.cfi)
            _state.update { it.copy(initialLocation = position) }
            books.saveProgress(bookId, position, event.fraction)
        }
    }

    // --- Selection ---------------------------------------------------------------------------

    /** Marks the current selection; re-marking an existing range restyles it. */
    fun markSelection(type: AnnotationType, color: HighlightColor) {
        val selection = _state.value.selection ?: return
        viewModelScope.launch {
            val annotation = annotationRepository.upsert(
                bookId = bookId,
                type = type,
                cfi = selection.cfi,
                text = selection.text,
                chapter = currentChapter(),
                color = color.hex,
                position = currentFraction(),
            )
            _commands.send(PageCommand.Show(annotation))
            clearSelection()
        }
    }

    /** Highlights the selection and opens the note editor for it. */
    fun noteOnSelection() {
        val selection = _state.value.selection ?: return
        viewModelScope.launch {
            val existing = annotations.value.firstOrNull { it.cfi == selection.cfi && it.type != AnnotationType.BOOKMARK }
            val annotation = existing ?: annotationRepository.upsert(
                bookId = bookId,
                type = AnnotationType.HIGHLIGHT,
                cfi = selection.cfi,
                text = selection.text,
                chapter = currentChapter(),
                color = HighlightColor.DEFAULT.hex,
                position = currentFraction(),
            ).also { _commands.send(PageCommand.Show(it)) }
            clearSelection()
            _state.update { it.copy(editingNoteId = annotation.id) }
        }
    }

    /** Removes the marks the current selection overlaps. */
    fun deleteMarksInSelection() {
        val ids = _state.value.selection?.annotationIds.orEmpty()
        viewModelScope.launch {
            ids.forEach { id -> annotations.value.firstOrNull { it.id == id }?.let { delete(it) } }
            clearSelection()
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = null) }
        _commands.trySend(PageCommand.ClearSelection)
    }

    // --- Existing annotations ------------------------------------------------------------------

    fun restyle(annotation: Annotation, type: AnnotationType, color: HighlightColor) {
        viewModelScope.launch {
            val updated = annotationRepository.restyle(annotation.id, type, color.hex) ?: return@launch
            // Hide first: a highlight→underline switch must not leave the old overlay behind.
            _commands.send(PageCommand.Hide(annotation.cfi))
            _commands.send(PageCommand.Show(updated))
            _state.update { it.copy(tapped = it.tapped?.copy(annotation = updated)) }
        }
    }

    fun deleteAnnotation(annotation: Annotation) {
        viewModelScope.launch {
            delete(annotation)
            _state.update { it.copy(tapped = null) }
        }
    }

    private suspend fun delete(annotation: Annotation) {
        annotationRepository.delete(annotation.id)
        _commands.send(PageCommand.Hide(annotation.cfi))
    }

    fun dismissTapped() = _state.update { it.copy(tapped = null) }

    fun editNote(annotation: Annotation) = _state.update { it.copy(tapped = null, editingNoteId = annotation.id) }

    fun saveNote(id: Long, note: String) {
        viewModelScope.launch {
            val updated = annotationRepository.setNote(id, note) ?: return@launch
            // Redraw so foliate-js picks up hasReaderNote.
            _commands.send(PageCommand.Show(updated))
            _state.update { it.copy(editingNoteId = null) }
        }
    }

    fun dismissNoteEditor() = _state.update { it.copy(editingNoteId = null) }

    // --- Bookmarks -----------------------------------------------------------------------------

    fun toggleBookmark() {
        val bookmarkCfi = _state.value.location?.bookmarkCfi
        if (bookmarkCfi == null) {
            _commands.trySend(PageCommand.BookmarkHere)
        } else {
            // foliate-js answers Hide with a remove request, which deletes the row.
            _commands.trySend(PageCommand.Hide(bookmarkCfi))
            setBookmarkCfi(null)
        }
    }

    private fun onBookmarkRequest(request: ReaderEvent.BookmarkRequest) {
        viewModelScope.launch {
            if (request.remove) {
                annotationRepository.deleteBookmark(bookId, request.cfi)
                setBookmarkCfi(null)
            } else {
                val bookmark = annotationRepository.upsert(
                    bookId = bookId,
                    type = AnnotationType.BOOKMARK,
                    cfi = request.cfi,
                    text = request.excerpt,
                    chapter = currentChapter(),
                    color = HighlightColor.DEFAULT.hex,
                    position = request.fraction,
                )
                _commands.send(PageCommand.Show(bookmark))
                setBookmarkCfi(bookmark.cfi)
            }
        }
    }

    private fun setBookmarkCfi(cfi: String?) =
        _state.update { it.copy(location = it.location?.copy(bookmarkCfi = cfi)) }

    // --- Navigation ----------------------------------------------------------------------------

    fun goTo(cfi: String) {
        _commands.trySend(PageCommand.GoTo(cfi))
        _state.update { it.copy(chromeVisible = false) }
    }

    fun onRendererGone() = _state.update { it.copy(ready = false, error = ReaderError.RENDERER_GONE) }

    /** Called when the screen builds a fresh WebView after a crash. */
    fun onReload() = _state.update { it.copy(ready = false, error = null) }

    fun setChromeVisible(visible: Boolean) = _state.update { it.copy(chromeVisible = visible) }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    private fun currentChapter() = _state.value.location?.chapterTitle.orEmpty()

    private fun currentFraction() = _state.value.location?.fraction ?: 0f

    override fun onCleared() {
        registration?.let(files::unregister)
    }
}
