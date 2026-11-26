package com.zenread.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.core.common.ApplicationScope
import com.zenread.core.model.Book
import com.zenread.data.library.BookImporter
import com.zenread.data.library.BookRepository
import com.zenread.data.library.ImportOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ImportSummary(val imported: Int, val duplicates: Int, val unsupported: Int, val failed: List<String>)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: BookRepository,
    private val importer: BookImporter,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    /** null until the first database emission, so the UI can tell "loading" from "empty". */
    val books: StateFlow<List<Book>?> = repository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _summaries = Channel<ImportSummary>(Channel.BUFFERED)
    val summaries = _summaries.receiveAsFlow()

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // App scope: leaving the library must not cancel a half-finished import.
        appScope.launch {
            _importing.value = true
            try {
                val outcomes = importer.import(uris)
                _summaries.send(
                    ImportSummary(
                        imported = outcomes.count { it is ImportOutcome.Imported },
                        duplicates = outcomes.count { it is ImportOutcome.Duplicate },
                        unsupported = outcomes.count { it is ImportOutcome.Unsupported },
                        failed = outcomes.filterIsInstance<ImportOutcome.Failed>().map { it.fileName },
                    ),
                )
            } finally {
                _importing.value = false
            }
        }
    }

    fun delete(book: Book) {
        viewModelScope.launch { repository.delete(book) }
    }
}
