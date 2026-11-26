package com.zenread.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.core.model.Annotation
import com.zenread.core.model.AnnotationType
import com.zenread.core.model.Book
import com.zenread.data.library.AnnotationMarkdown
import com.zenread.data.library.AnnotationRepository
import com.zenread.data.library.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookNotes(val book: Book, val annotations: List<Annotation>)

@HiltViewModel
class NotesViewModel @Inject constructor(
    books: BookRepository,
    private val annotations: AnnotationRepository,
) : ViewModel() {

    /** Books with at least one highlight or note, most recently read first; null while loading. */
    val groups: StateFlow<List<BookNotes>?> = combine(books.observeBooks(), annotations.observeAll()) { bookList, all ->
        val byBook = all.filter { it.type != AnnotationType.BOOKMARK }.groupBy { it.bookId }
        bookList.mapNotNull { book -> byBook[book.id]?.let { BookNotes(book, it) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _editing = MutableStateFlow<Annotation?>(null)
    val editing: StateFlow<Annotation?> = _editing.asStateFlow()

    fun edit(annotation: Annotation) {
        _editing.value = annotation
    }

    fun dismissEditor() {
        _editing.value = null
    }

    fun saveNote(annotation: Annotation, note: String) {
        viewModelScope.launch {
            annotations.setNote(annotation.id, note)
            _editing.value = null
        }
    }

    fun delete(annotation: Annotation) {
        viewModelScope.launch { annotations.delete(annotation.id) }
    }

    fun markdown(group: BookNotes): String = AnnotationMarkdown.export(group.book, group.annotations)

    fun fileName(group: BookNotes): String = AnnotationMarkdown.fileName(group.book)
}
