package com.zenread.feature.reader.translate

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.data.translate.messageRes
import com.zenread.data.translate.PageTranslationMode
import com.zenread.data.translate.PageTranslationSession
import com.zenread.data.translate.SelectionTranslation
import com.zenread.data.translate.TranslateException
import com.zenread.data.translate.TranslateRepository
import com.zenread.data.translate.TranslateSettingsRepository
import com.zenread.data.translate.TranslationColor
import com.zenread.reader.engine.ReaderEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

data class PageTranslationState(
    val active: Boolean = false,
    /** Waiting for credentials and the session before telling the page. */
    val starting: Boolean = false,
)

data class TranslationServiceOption(val id: String, @param:StringRes val label: Int)

data class SelectionTranslationState(
    val source: String,
    val serviceId: String,
    val loading: Boolean = true,
    val result: SelectionTranslation? = null,
    val error: TranslateException.Reason? = null,
)

/** Instructions for the page, applied through `ReaderController.setTranslation`. */
sealed interface TranslationCommand {
    data class Enable(
        val mode: PageTranslationMode,
        val generation: Long,
        val color: TranslationColor,
        val italic: Boolean,
        /** Drop translations from an earlier session with another service or language. */
        val clear: Boolean,
        val prefetchPages: Int,
    ) : TranslationCommand

    data class Disable(val generation: Long) : TranslationCommand
}

/** Something to tell the user in a snackbar. */
data class TranslationMessage(@param:StringRes val text: Int, val stopped: Boolean)

/**
 * Selection translation and bilingual page translation for one open book.
 *
 * Consent: nothing is sent to a service until the user taps Translate or turns page
 * translation on, and page translation is never restored when a book is reopened
 * (upstream `ReaderTranslationSession`: "Explicit, in-memory consent").
 */
@HiltViewModel
class ReaderTranslationViewModel @Inject constructor(
    private val repository: TranslateRepository,
    private val settingsRepository: TranslateSettingsRepository,
) : ViewModel() {

    val services: List<TranslationServiceOption> =
        repository.translators.map { TranslationServiceOption(it.descriptor.id, it.descriptor.label) }

    // --- Page (bilingual) translation ---------------------------------------------------------

    private val _page = MutableStateFlow(PageTranslationState())
    val page: StateFlow<PageTranslationState> = _page.asStateFlow()

    private val _commands = Channel<TranslationCommand>(Channel.UNLIMITED)
    val commands: Flow<TranslationCommand> = _commands.receiveAsFlow()

    private val _messages = Channel<TranslationMessage>(Channel.UNLIMITED)
    val messages: Flow<TranslationMessage> = _messages.receiveAsFlow()

    private var session: PageTranslationSession? = null

    /** Tags requests from the page; bumping it makes every older request answer null. */
    private var generation = 0L

    /** Service and target of the translations currently in the page. */
    private var pageIdentity: String? = null
    private var startJob: Job? = null

    fun togglePage() {
        if (_page.value.active || _page.value.starting) stopPage() else startPage()
    }

    private fun startPage() {
        _page.value = PageTranslationState(starting = true)
        startJob = viewModelScope.launch {
            val next = try {
                repository.startPageSession()
            } catch (e: TranslateException) {
                _page.value = PageTranslationState()
                _messages.send(TranslationMessage(e.reason.messageRes, stopped = false))
                return@launch
            }
            val settings = settingsRepository.current()
            session?.close()
            session = next
            generation++
            val identity = "${next.translator.descriptor.id}/${next.to.code}"
            val clear = pageIdentity != null && pageIdentity != identity
            pageIdentity = identity
            val enable = TranslationCommand.Enable(
                mode = settings.pageMode,
                generation = generation,
                color = settings.pageColor,
                italic = settings.pageItalic,
                clear = clear,
                prefetchPages = settings.prefetchPages,
            )
            lastEnable = enable
            _commands.send(enable)
            _page.value = PageTranslationState(active = true)
        }
    }

    private var lastEnable: TranslationCommand.Enable? = null

    /** A fresh page (e.g. after the WebView was rebuilt) loaded: re-enable translation in it. */
    fun onPageReady() {
        val enable = lastEnable ?: return
        if (session != null && _page.value.active) _commands.trySend(enable.copy(clear = false))
    }

    private fun stopPage() {
        startJob?.cancel()
        lastEnable = null
        session?.close()
        session = null
        generation++
        _commands.trySend(TranslationCommand.Disable(generation))
        _page.value = PageTranslationState()
    }

    /**
     * Answers foliate-js `translateText`: the translated paragraph, or null to leave it alone
     * (stale generation, nothing to translate, session stopped).
     */
    suspend fun answer(request: ReaderEvent.TranslateRequest): JsonElement {
        val current = session
        if (current == null || request.generation != generation) return JsonNull
        return try {
            current.translate(request.text)?.let(::JsonPrimitive) ?: JsonNull
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // Our own caller was cancelled: propagate.
            JsonNull // The session was closed under the request.
        } catch (e: TranslateException) {
            // Like upstream: a failure on a visible paragraph stops translation instead of
            // retrying (and paying for) every following paragraph. Prefetch failures are quiet.
            if (request.prefetchPages == 0 && session === current) {
                stopPage()
                _messages.send(TranslationMessage(e.reason.messageRes, stopped = true))
            }
            JsonNull
        }
    }

    // --- Selection translation -----------------------------------------------------------------

    private val _selection = MutableStateFlow<SelectionTranslationState?>(null)
    val selection: StateFlow<SelectionTranslationState?> = _selection.asStateFlow()
    private var selectionJob: Job? = null

    /** Translates [text] with [serviceId], or with the default service when null. */
    fun translateSelection(text: String, serviceId: String? = null) {
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            val id = serviceId ?: settingsRepository.current().serviceId
            _selection.value = SelectionTranslationState(source = text, serviceId = id)
            _selection.value = try {
                val result = repository.translateSelection(text, id)
                SelectionTranslationState(text, result.serviceId, loading = false, result = result)
            } catch (e: TranslateException) {
                SelectionTranslationState(text, id, loading = false, error = e.reason)
            }
        }
    }

    fun switchSelectionService(serviceId: String) {
        val current = _selection.value ?: return
        if (current.serviceId != serviceId || current.error != null) translateSelection(current.source, serviceId)
    }

    fun retrySelection() {
        val current = _selection.value ?: return
        translateSelection(current.source, current.serviceId)
    }

    fun dismissSelection() {
        selectionJob?.cancel()
        _selection.value = null
    }

    override fun onCleared() {
        session?.close()
    }
}