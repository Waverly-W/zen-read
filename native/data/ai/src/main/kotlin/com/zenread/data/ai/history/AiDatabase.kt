package com.zenread.data.ai.history

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations", indices = [Index("bookId", "updatedAt")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** `user` or `assistant`. */
    val role: String,
    /** What the user typed / the model answered. Attached book context is not stored. */
    val content: String,
    /** Selected text the user asked about. */
    val quote: String? = null,
    /** Reading skill id that produced this turn, if any. */
    val skillId: String? = null,
    val reasoning: String? = null,
    /** JSON list of [Citation]. */
    val citations: String? = null,
    /** See [MessageStatus]. */
    val status: String,
    val error: String? = null,
    val createdAt: Long,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE bookId = :bookId ORDER BY updatedAt DESC")
    fun observeConversations(bookId: Long): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE bookId = :bookId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestConversation(bookId: Long): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id")
    suspend fun messages(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query(
        "UPDATE messages SET content = :content, reasoning = :reasoning, citations = :citations, " +
            "status = :status, error = :error WHERE id = :id",
    )
    suspend fun updateMessage(id: Long, content: String, reasoning: String?, citations: String?, status: String, error: String?)

    @Query("UPDATE conversations SET updatedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    /** Answers left mid-stream by a killed process. */
    @Query("UPDATE messages SET status = 'stopped' WHERE status = 'streaming'")
    suspend fun markInterrupted()
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class AiDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
}
