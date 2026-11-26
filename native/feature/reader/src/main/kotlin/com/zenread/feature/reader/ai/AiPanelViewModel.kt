package com.zenread.feature.reader.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.data.ai.ChatClient
import com.zenread.data.ai.chat.ChatEvent
import com.zenread.data.ai.chat.ChatException
import com.zenread.data.ai.chat.ChatMessage
import com.zenread.data.ai.chat.ChatRole
import com.zenread.data.ai.chat.FinishReason
import com.zenread.data.ai.history.Citation
import com.zenread.data.ai.history.Conversation
import com.zenread.data.ai.history.ConversationRepository
import com.zenread.data.ai.history.MessageStatus
import com.zenread.data.ai.history.StoredMessage
import com.zenread.data.ai.history.toChatHistory
import com.zenread.data.ai.prompt.BookInfo
import com.zenread.data.ai.prompt.PromptAssembler
import com.zenread.data.ai.prompt.ReadingContext
import com.zenread.data.ai.rag.BookIndexManager
import com.zenread.data.ai.rag.BookIndexState
import com.zenread.data.ai.rag.BookRetriever
import com.zenread.data.ai.rag.RetrievedPassage
import com.zenread.data.ai.settings.AiSettingsRepository
import com.zenread.data.ai.skills.ReadingSkill
import com.zenread.data.ai.skills.ReadingSkills
import com.zenread.data.ai.skills.SkillContext
import com.zenread.data.library.BookRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The chapter on screen, read from the page when a request needs it. */
data class ChapterSnapshot(val title: String?, val text: String?)

fun interface ChapterSource {
    suspend fun load(): ChapterSnapshot
}

/** The answer being streamed, shown live before it is persisted. */
data class LiveAnswer(val messageId: Long, val text: String, val reasoning: String)

data class AiPanelState(
    val configured: Boolean = true,
    val modelName: String? = null,
    val conversations: List<Conversation> = emptyList(),
    val conversationId: Long? = null,
    val messages: List<StoredMessage> = emptyList(),
    val live: LiveAnswer? = null,
    val generating: Boolean = false,
    val index: BookIndexState = BookIndexState.NONE,
    val embeddingConfigured: Boolean = false,
    /** Selection attached to the next message. */
    val quote: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = AiPanelViewModel.Factory::class)
class AiPanelViewModel @AssistedInject constructor(
    @Assisted private val bookId: Long,
    @param:ApplicationContext private val context: Context,
    private val books: BookRepository,
    private val chat: ChatClient,
    private val conversations: ConversationRepository,
    private val retriever: BookRetriever,
    private val indexManager: BookIndexManager,
    private val settings: AiSettingsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(bookId: Long): AiPanelViewModel
    }

    private val conversationId = MutableStateFlow<Long?>(null)
    private val live = MutableStateFlow<LiveAnswer?>(null)
    private val quote = MutableStateFlow<String?>(null)
    private var job: Job? = null

    /** Set by the panel while it is shown. */
    var chapterSource: ChapterSource? = null

    private val messages = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else conversations.observeMessages(id)
    }

    private val chrome = combine(
        settings.settings,
        conversations.observeConversations(bookId),
        indexManager.observe(bookId),
    ) { s, list, index -> Triple(s, list, index) }

    val state: StateFlow<AiPanelState> = combine(chrome, conversationId, messages, live, quote) { (s, list, index), id, msgs, liveAnswer, q ->
        AiPanelState(
            configured = s.resolvedChatModel != null,
            modelName = s.resolvedChatModel?.model,
            conversations = list,
            conversationId = id,
            messages = msgs,
            live = liveAnswer,
            generating = liveAnswer != null,
            index = index,
            embeddingConfigured = s.resolvedEmbeddingModel != null,
            quote = q,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiPanelState())

    init {
        viewModelScope.launch {
            conversations.markInterrupted()
            if (conversationId.value == null) conversationId.value = conversations.latestConversationId(bookId)
        }
    }

    /** Attaches [text] (the reader selection) to the next request. */
    fun attachQuote(text: String?) {
        quote.value = text?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_QUOTE)
    }

    fun newConversation() {
        if (live.value != null) return
        conversationId.value = null
    }

    fun openConversation(id: Long) {
        if (live.value != null) return
        conversationId.value = id
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            if (conversationId.value == id) {
                stop()
                conversationId.value = null
            }
            conversations.deleteConversation(id)
        }
    }

    /** A free-form question, with the attached quote if any. */
    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty()) return
        start(display = question, request = question, skill = null, quote = quote.value)
    }

    fun runSkill(skill: ReadingSkill) {
        val selection = quote.value
        start(
            display = context.getString(skill.name),
            request = context.getString(skill.prompt),
            skill = skill,
            quote = selection,
        )
    }

    fun stop() {
        job?.cancel()
    }

    /** Replaces the last answer with a new one for the same request. */
    fun regenerate() {
        if (live.value != null) return
        val id = conversationId.value ?: return
        live.value = PENDING
        viewModelScope.launch {
            val all = conversations.messages(id)
            val lastUser = all.lastOrNull { it.role == ChatRole.USER } ?: run {
                live.value = null
                return@launch
            }
            all.filter { it.id > lastUser.id }.forEach { conversations.deleteMessage(it.id) }
            val skill = lastUser.skillId?.let(ReadingSkills::byId)
            val request = skill?.let { context.getString(it.prompt) } ?: lastUser.content
            launchAnswer(id, lastUser.id, request, skill, lastUser.quote)
        }
    }

    fun buildIndex(rebuild: Boolean = false) {
        viewModelScope.launch {
            val book = books.getBook(bookId) ?: return@launch
            indexManager.build(book, rebuild)
        }
    }

    fun cancelIndex() = indexManager.cancel(bookId)

    private fun start(display: String, request: String, skill: ReadingSkill?, quote: String?) {
        if (live.value != null) return
        // Claim the slot synchronously so a double tap cannot start two answers.
        live.value = PENDING
        this.quote.value = null
        viewModelScope.launch {
            try {
                val id = conversationId.value ?: conversations.createConversation(bookId, display).also { conversationId.value = it }
                val userId = conversations.addUserMessage(id, display, quote, skill?.id)
                launchAnswer(id, userId, request, skill, quote)
            } catch (e: Exception) {
                if (live.value === PENDING) live.value = null
                if (e is CancellationException) throw e
            }
        }
    }

    private fun launchAnswer(conversationId: Long, userMessageId: Long, request: String, skill: ReadingSkill?, quote: String?) {
        job = viewModelScope.launch {
            val history: List<ChatMessage>
            val assistantId: Long
            try {
                history = conversations.messages(conversationId).filter { it.id < userMessageId }.toChatHistory()
                assistantId = conversations.startAssistantMessage(conversationId, emptyList())
            } catch (e: Exception) {
                live.value = null
                if (e is CancellationException) throw e
                return@launch
            }
            live.value = LiveAnswer(assistantId, "", "")
            val text = StringBuilder()
            val reasoning = StringBuilder()
            var citations: List<Citation> = emptyList()
            var status = MessageStatus.DONE
            var error: String? = null
            try {
                val context = buildContext(request, skill, quote)
                citations = context.passages
                val book = books.getBook(bookId)
                val messages = PromptAssembler.messages(
                    book = BookInfo(book?.title.orEmpty(), book?.author.orEmpty()),
                    language = Locale.getDefault().getDisplayName(Locale.ENGLISH),
                    history = history,
                    request = request,
                    context = context,
                )
                var lastFlush = System.currentTimeMillis()
                chat.stream(messages).collect { event ->
                    when (event) {
                        is ChatEvent.Delta -> text.append(event.text)
                        is ChatEvent.Reasoning -> reasoning.append(event.text)
                        is ChatEvent.Done -> if (event.finishReason == FinishReason.FILTERED && text.isEmpty()) {
                            status = MessageStatus.ERROR
                            error = "FILTERED\n"
                        }
                    }
                    live.value = LiveAnswer(assistantId, text.toString(), reasoning.toString())
                    // Periodic saves keep most of a long answer if the process dies.
                    val now = System.currentTimeMillis()
                    if (now - lastFlush > FLUSH_INTERVAL_MS) {
                        lastFlush = now
                        conversations.updateAssistantMessage(assistantId, text.toString(), reasoning.toString(), citations, MessageStatus.STREAMING)
                    }
                }
            } catch (e: CancellationException) {
                status = MessageStatus.STOPPED
                throw e
            } catch (e: ChatException) {
                status = MessageStatus.ERROR
                error = "${e.kind.name}\n${e.message.orEmpty()}"
            } catch (e: Exception) {
                status = MessageStatus.ERROR
                error = "OTHER\n${e.message ?: e.javaClass.simpleName}"
            } finally {
                withContext(NonCancellable) {
                    // Keep only sources the answer actually cites, renumbering nothing.
                    val cited = PromptAssembler.citedNumbers(text.toString()).toSet()
                    val kept = citations.filter { it.number in cited }
                    conversations.updateAssistantMessage(assistantId, text.toString(), reasoning.toString(), kept, status, error)
                    live.value = null
                }
            }
        }
    }

    private suspend fun buildContext(request: String, skill: ReadingSkill?, quote: String?): ReadingContext {
        val options = settings.current().index
        val index = indexManager.observe(bookId).first()
        val needsChapter = skill?.context == SkillContext.CHAPTER || (skill == null && quote == null) ||
            (skill?.context == SkillContext.BOOK && !index.searchable)
        val chapter = if (needsChapter) chapterSource?.load() else null
        return when (skill?.context) {
            SkillContext.SELECTION -> ReadingContext(chapterTitle = chapter?.title, selection = quote)
            SkillContext.CHAPTER -> ReadingContext(chapterTitle = chapter?.title, chapterText = chapter?.text, selection = quote)
            SkillContext.BOOK -> if (index.searchable) {
                ReadingContext(
                    passages = number(retriever.bookOutline(bookId, maxChars = OUTLINE_BUDGET)),
                    passagesAreOutline = true,
                )
            } else {
                ReadingContext(chapterTitle = chapter?.title, chapterText = chapter?.text)
            }
            null -> {
                val passages = if (options.useForQuestions && index.searchable) {
                    retriever.search(bookId, listOfNotNull(quote, request).joinToString("\n"), options.topK)
                } else {
                    emptyList()
                }
                ReadingContext(
                    chapterTitle = chapter?.title,
                    chapterText = chapter?.text?.take(
                        if (passages.isEmpty()) PromptAssembler.CHAPTER_BUDGET else PromptAssembler.QUESTION_CHAPTER_BUDGET,
                    ),
                    selection = quote,
                    passages = number(passages),
                )
            }
        }
    }

    private fun number(passages: List<RetrievedPassage>) =
        passages.mapIndexed { i, p -> Citation(i + 1, p.chapter, p.cfi, p.text) }

    private companion object {
        const val MAX_QUOTE = 4_000
        const val OUTLINE_BUDGET = 20_000
        const val FLUSH_INTERVAL_MS = 1_500L

        /** Placeholder while the request is prepared; matches no message. */
        val PENDING = LiveAnswer(messageId = -1, text = "", reasoning = "")
    }
}
