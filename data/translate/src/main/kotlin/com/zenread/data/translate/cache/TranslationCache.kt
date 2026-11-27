package com.zenread.data.translate.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.zenread.data.translate.Language
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import javax.inject.Singleton

/**
 * Translated paragraphs, so turning a page back or reopening a book does not send the same
 * text to the service again. Lives in its own `translate_cache.db` (the main database is
 * owned by the sync phase) and is safe to delete at any time.
 */
@Entity(tableName = "translation_cache")
data class TranslationCacheEntity(
    /** [TranslationCacheKey.of]. */
    @PrimaryKey val key: String,
    val translation: String,
    val createdAt: Long,
)

@Dao
interface TranslationCacheDao {
    @Query("SELECT translation FROM translation_cache WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entries: List<TranslationCacheEntity>)

    @Query("SELECT COUNT(*) FROM translation_cache")
    suspend fun count(): Int

    @Query("DELETE FROM translation_cache")
    suspend fun clear()

    /** Keeps the newest [keep] rows. */
    @Query(
        "DELETE FROM translation_cache WHERE `key` NOT IN " +
            "(SELECT `key` FROM translation_cache ORDER BY createdAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)
}

@Database(entities = [TranslationCacheEntity::class], version = 1, exportSchema = true)
abstract class TranslationCacheDatabase : RoomDatabase() {
    abstract fun dao(): TranslationCacheDao
}

object TranslationCacheKey {
    /**
     * SHA-256 over everything that changes the result: service, languages and the exact text.
     * Credentials are not part of it: a new key for the same service yields the same text.
     */
    fun of(serviceId: String, from: Language?, to: Language, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(serviceId, from?.code ?: "auto", to.code, text).forEach { part ->
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object TranslationCacheModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): TranslationCacheDatabase =
        Room.databaseBuilder(context, TranslationCacheDatabase::class.java, "translate_cache.db")
            // A cache: rebuilding it is always acceptable.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun dao(db: TranslationCacheDatabase): TranslationCacheDao = db.dao()
}
