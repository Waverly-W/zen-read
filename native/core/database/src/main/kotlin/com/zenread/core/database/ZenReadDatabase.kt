package com.zenread.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(entities = [BookEntity::class], version = 1, exportSchema = true)
abstract class ZenReadDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ZenReadDatabase =
        Room.databaseBuilder(context, ZenReadDatabase::class.java, "zenread.db").build()

    @Provides
    fun bookDao(db: ZenReadDatabase): BookDao = db.bookDao()
}
