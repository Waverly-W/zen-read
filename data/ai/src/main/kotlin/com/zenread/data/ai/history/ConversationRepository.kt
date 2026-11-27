package com.zenread.data.ai.history

import com.zenread.data.ai.chat.ChatMessage
import com.zenread.data.ai.chat.ChatRole
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A numbered source an answer may cite as `[n]`. */
@Serializable
data class Citation(
    val number: Int,
    val chapter: String,
    /** Location to jump to in the reader. */
    val cfi: String,
    val excerpt: String,
)

enum class MessageStatus(val dbValue: String) {
    STREAMING("streaming"),
    DONE("done"),
    STOPPED("stopped"),
    ERROR("error"),
    ;

    companion object {
        fun of(value: String) = entries.firstOrNull { it.dbValue == value } ?: DONE
    }
}

data class Conversation(val id: Long, val bookId: Long, val title: String, val updatedAt: Long)

data class StoredMessage(
    val id: Long,
    val role: ChatRole,
    val content: String,
    val quote: String?,
    val skillId: String?,
    val reasoning: String?,
    val citations: List<Citation>,
    val status: MessageStatus,
    val error: String?,
)

/** Per-book AI conversations, persisted in `ai.db`. */
@Singleton
class ConversationRepository @Inject constructor(private val dao: ConversationDao) {
    private val json = Json { ignoreUnknownKeys = true }
    private val citationList = ListSerializer(Citation.serializer())

    fun observeConversations(bookId: Long): Flow<List<Conversation>> =
        dao.observeConversations(bookId).map { list -> list.map { Conversation(it.id, it.bookId, it.title, it.updatedAt) } }

    suspend fun latestConversationId(bookId: Long): Long? = dao.latestConversation(bookId)?.id

    fun observeMessages(conversationId: Long): Flow<List<StoredMessage>> =
        dao.observeMessages(conversationId).map { list -> list.map { it.asModel() } }

    suspend fun messages(conversationId: Long): List<StoredMessage> = dao.messages(conversationId).map { it.asModel() }

    suspend fun createConversation(bookId: Long, title: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertConversation(ConversationEntity(bookId = bookId, title = title.take(80), createdAt = now, updatedAt = now))
    }

    suspend fun addUserMessage(conversationId: Long, text: String, quote: String?, skillId: String?): Long {
        val now = System.currentTimeMillis()
        dao.touch(conversationId, now)
        return dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = "user",
                content = text,
                quote = quote,
                skillId = skillId,
                status = MessageStatus.DONE.dbValue,
                createdAt = now,
            ),
        )
    }

    suspend fun startAssistantMessage(conversationId: Long, citations: List<Citation>): Long = dao.insertMessage(
        MessageEntity(
            conversationId = conversationId,
            role = "assistant",
            content = "",
            citations = encode(citations),
            status = MessageStatus.STREAMING.dbValue,
            createdAt = System.currentTimeMillis(),
        ),
    )

    suspend fun updateAssistantMessage(
        id: Long,
        content: String,
        reasoning: String?,
        citations: List<Citation>,
        status: MessageStatus,
        error: String? = null,
    ) = dao.updateMessage(id, content, reasoning?.ifEmpty { null }, encode(citations), status.dbValue, error)

    suspend fun deleteMessage(id: Long) = dao.deleteMessage(id)

    suspend fun deleteConversation(id: Long) = dao.deleteConversation(id)

    suspend fun markInterrupted() = dao.markInterrupted()

    private fun encode(citations: List<Citation>) =
        citations.takeIf { it.isNotEmpty() }?.let { json.encodeToString(citationList, it) }

    private fun MessageEntity.asModel() = StoredMessage(
        id = id,
        role = if (role == "user") ChatRole.USER else ChatRole.ASSISTANT,
        content = content,
        quote = quote,
        skillId = skillId,
        reasoning = reasoning,
        citations = citations?.let { runCatching { json.decodeFromString(citationList, it) }.getOrNull() }.orEmpty(),
        status = MessageStatus.of(status),
        error = error,
    )
}

/** History as chat messages: failed/empty answers are skipped so the model sees clean turns. */
fun List<StoredMessage>.toChatHistory(quoteLabel: String = "Quoted passage"): List<ChatMessage> = mapNotNull { message ->
    when {
        message.role == ChatRole.ASSISTANT && message.content.isBlank() -> null
        message.role == ChatRole.USER && message.quote != null ->
            ChatMessage.user("$quoteLabel:\n\"\"\"\n${message.quote}\n\"\"\"\n\n${message.content}")
        else -> ChatMessage(message.role, message.content)
    }
}
