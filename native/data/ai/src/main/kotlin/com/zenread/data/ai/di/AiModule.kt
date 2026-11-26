package com.zenread.data.ai.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zenread.data.ai.ChatClient
import com.zenread.data.ai.DefaultChatClient
import com.zenread.data.ai.ProviderAdmin
import com.zenread.data.ai.history.AiDatabase
import com.zenread.data.ai.history.ConversationDao
import com.zenread.data.ai.rag.AiIndexDatabase
import com.zenread.data.ai.rag.IndexDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AiBindings {
    @Binds
    abstract fun chatClient(impl: DefaultChatClient): ChatClient

    @Binds
    abstract fun providerAdmin(impl: DefaultChatClient): ProviderAdmin
}

@Module
@InstallIn(SingletonComponent::class)
internal object AiDatabaseModule {
    /** Conversations (`ai.db`); separate from the main library database. */
    @Provides
    @Singleton
    fun aiDatabase(@ApplicationContext context: Context): AiDatabase =
        Room.databaseBuilder(context, AiDatabase::class.java, "ai.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun conversationDao(db: AiDatabase): ConversationDao = db.conversations()

    /**
     * Book index (`ai_index.db`): chunks, vectors and an FTS5 table. Uses the bundled SQLite
     * because FTS5 is not compiled into the platform SQLite on all Android versions.
     * The index is a cache, so schema changes simply rebuild it.
     */
    @Provides
    @Singleton
    fun aiIndexDatabase(@ApplicationContext context: Context): AiIndexDatabase =
        Room.databaseBuilder(context, AiIndexDatabase::class.java, context.getDatabasePath("ai_index.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(AiIndexDatabase.FtsCallback)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun indexDao(db: AiIndexDatabase): IndexDao = db.index()
}
