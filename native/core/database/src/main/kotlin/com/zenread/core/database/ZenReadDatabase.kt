package com.zenread.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        BookEntity::class,
        AnnotationEntity::class,
        SyncTombstoneEntity::class,
        ReadingSessionEntity::class,
        BookFinishEntity::class,
    ],
    version = 4,
    exportSchema = true,
    // 2 → 3 and 3 → 4 are the manual MIGRATION_2_3 and MIGRATION_3_4 (see Migrations.kt).
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class ZenReadDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun syncDao(): SyncDao
    abstract fun readingStatsDao(): ReadingStatsDao

    companion object {
        const val NAME = "zenread.db"

        /** Tables whose changes must reach other devices. Reading statistics are not synced yet. */
        val SYNCED_TABLES = arrayOf("books", "annotations", "sync_tombstones")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ZenReadDatabase =
        Room.databaseBuilder(context, ZenReadDatabase::class.java, ZenReadDatabase.NAME)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides
    fun bookDao(db: ZenReadDatabase): BookDao = db.bookDao()

    @Provides
    fun annotationDao(db: ZenReadDatabase): AnnotationDao = db.annotationDao()

    @Provides
    fun syncDao(db: ZenReadDatabase): SyncDao = db.syncDao()

    @Provides
    fun readingStatsDao(db: ZenReadDatabase): ReadingStatsDao = db.readingStatsDao()
}
