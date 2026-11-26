package com.zenread.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.core.datastore.ReaderSettingsRepository
import com.zenread.core.model.Book
import com.zenread.core.model.ReaderSettings
import com.zenread.core.model.TocItem
import com.zenread.data.library.BookRepository
import com.zenread.reader.engine.BookFileRegistry
import com.zenread.reader.engine.Cfi
import com.zenread.reader.engine.ReaderEvent
import com.zenread.reader.engine.ReaderWebViewFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

enum class ReaderError { BOOK_MISSING, WEBVIEW_UNSUPPORTED, LOAD_FAILED, RENDERER_GONE }

@HiltViewModel(assistedFactory = ReaderViewModel.Factory::class)
class ReaderViewModel @AssistedInject constructor(
    @Assisted private val bookId: Long,
    private val books: BookRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val files: BookFileRegistry,
    val webViewFactory: ReaderWebViewFactory,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(bookId: Long): ReaderViewModel
    }

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    val settings: StateFlow<ReaderSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
                        it.copy(book = book, bookUrl = reg.url, initialLocation = book.lastLocation)
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
            is ReaderEvent.Relocated -> {
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
            is ReaderEvent.Tap -> Unit // Handled by the screen, which owns the page controller.
        }
    }

    fun onRendererGone() = _state.update { it.copy(ready = false, error = ReaderError.RENDERER_GONE) }

    /** Called when the screen builds a fresh WebView after a crash. */
    fun onReload() = _state.update { it.copy(ready = false, error = null) }

    fun setChromeVisible(visible: Boolean) = _state.update { it.copy(chromeVisible = visible) }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    override fun onCleared() {
        registration?.let(files::unregister)
    }
}
