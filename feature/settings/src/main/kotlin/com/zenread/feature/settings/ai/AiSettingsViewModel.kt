package com.zenread.feature.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.data.ai.ProviderAdmin
import com.zenread.data.ai.chat.ChatException
import com.zenread.data.ai.chat.ModelRef
import com.zenread.data.ai.provider.ModelParams
import com.zenread.data.ai.provider.ProviderConfig
import com.zenread.data.ai.provider.ProviderPreset
import com.zenread.data.ai.rag.BookIndexManager
import com.zenread.data.ai.rag.IndexedBook
import com.zenread.data.ai.settings.AiSettings
import com.zenread.data.ai.settings.AiSettingsRepository
import com.zenread.data.ai.settings.IndexOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Result line under the provider form. */
sealed interface EditorMessage {
    data object TestOk : EditorMessage
    data class ModelsFetched(val chat: Int, val embedding: Int) : EditorMessage
    data class Failed(val kind: ChatException.Kind?, val detail: String) : EditorMessage
    data object NeedsModel : EditorMessage
}

data class ProviderEditorState(
    val draft: ProviderConfig,
    val isNew: Boolean,
    /** New key(s) typed by the user; blank keeps the saved key. Never pre-filled. */
    val keyInput: String = "",
    val hasSavedKey: Boolean = false,
    val clearKey: Boolean = false,
    val busy: Boolean = false,
    val message: EditorMessage? = null,
)

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val repository: AiSettingsRepository,
    private val admin: ProviderAdmin,
    private val indexManager: BookIndexManager,
) : ViewModel() {

    val settings: StateFlow<AiSettings?> = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val indexedBooks: StateFlow<List<IndexedBook>> =
        indexManager.observeIndexedBooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _editor = MutableStateFlow<ProviderEditorState?>(null)
    val editor: StateFlow<ProviderEditorState?> = _editor.asStateFlow()

    fun startAdd(preset: ProviderPreset) {
        _editor.value = ProviderEditorState(
            draft = ProviderConfig(
                id = UUID.randomUUID().toString(),
                name = preset.displayName,
                kind = preset.kind,
                baseUrl = preset.baseUrl,
                embeddingModels = listOfNotNull(preset.suggestedEmbeddingModel),
            ),
            isNew = true,
        )
    }

    fun startEdit(id: String) {
        val provider = settings.value?.provider(id) ?: return
        viewModelScope.launch {
            _editor.value = ProviderEditorState(
                draft = provider,
                isNew = false,
                hasSavedKey = !repository.apiKeyField(id).isNullOrBlank(),
            )
        }
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun updateDraft(transform: (ProviderConfig) -> ProviderConfig) =
        _editor.update { it?.copy(draft = transform(it.draft), message = null) }

    fun setKeyInput(value: String) = _editor.update {
        if (it == null || it.keyInput == value) it else it.copy(keyInput = value, clearKey = it.clearKey && value.isBlank(), message = null)
    }

    fun clearSavedKey() = _editor.update { it?.copy(keyInput = "", clearKey = true, message = null) }

    fun addModel(name: String, embedding: Boolean) {
        val model = name.trim().takeIf { it.isNotEmpty() } ?: return
        updateDraft { draft ->
            if (embedding) {
                draft.copy(embeddingModels = (draft.embeddingModels + model).distinct())
            } else {
                draft.copy(models = (draft.models + model).distinct())
            }
        }
    }

    fun removeModel(name: String, embedding: Boolean) = updateDraft { draft ->
        if (embedding) {
            draft.copy(embeddingModels = draft.embeddingModels - name)
        } else {
            draft.copy(models = draft.models - name, modelParams = draft.modelParams - name)
        }
    }

    fun setModelParams(model: String, params: ModelParams) = updateDraft { draft ->
        val cleaned = params.takeIf { it.temperature != null || it.maxTokens != null }
        draft.copy(modelParams = if (cleaned == null) draft.modelParams - model else draft.modelParams + (model to cleaned))
    }

    fun fetchModels() = runEditorTask { state ->
        val models = admin.listModels(state.draft, keyFor(state))
        updateDraft { draft ->
            draft.copy(
                models = (draft.models + models.chat).distinct(),
                embeddingModels = (draft.embeddingModels + models.embedding).distinct(),
            )
        }
        EditorMessage.ModelsFetched(models.chat.size, models.embedding.size)
    }

    fun testConnection() = runEditorTask { state ->
        val model = state.draft.models.firstOrNull() ?: return@runEditorTask EditorMessage.NeedsModel
        admin.test(state.draft, keyFor(state), model)
        EditorMessage.TestOk
    }

    fun saveEditor() {
        val state = _editor.value ?: return
        val draft = state.draft.copy(name = state.draft.name.trim().ifEmpty { "AI" }, baseUrl = state.draft.baseUrl.trim())
        viewModelScope.launch {
            val key = when {
                state.keyInput.isNotBlank() -> state.keyInput
                state.clearKey -> ""
                else -> null
            }
            repository.saveProvider(draft, key)
            _editor.value = null
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            repository.deleteProvider(id)
            _editor.value = null
        }
    }

    fun setChatModel(ref: ModelRef?) = viewModelScope.launch { repository.update { it.copy(chatModel = ref) } }

    fun setEmbeddingModel(ref: ModelRef?) = viewModelScope.launch { repository.update { it.copy(embeddingModel = ref) } }

    fun updateIndexOptions(transform: (IndexOptions) -> IndexOptions) =
        viewModelScope.launch { repository.update { it.copy(index = transform(it.index).sanitized()) } }

    fun deleteIndex(bookId: Long) = viewModelScope.launch { indexManager.delete(bookId) }

    private suspend fun keyFor(state: ProviderEditorState): String? = when {
        state.keyInput.isNotBlank() -> state.keyInput
        state.clearKey || state.isNew -> null
        else -> repository.apiKeyField(state.draft.id)
    }

    private fun runEditorTask(task: suspend (ProviderEditorState) -> EditorMessage) {
        val state = _editor.value ?: return
        if (state.busy) return
        _editor.update { it?.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = try {
                task(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatException) {
                EditorMessage.Failed(e.kind, e.message.orEmpty())
            } catch (e: Exception) {
                EditorMessage.Failed(null, e.message ?: e.javaClass.simpleName)
            }
            _editor.update { it?.copy(busy = false, message = message) }
        }
    }
}
