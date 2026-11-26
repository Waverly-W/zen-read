package com.zenread.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ZenReadDatabase::class.java)

    @Test
    fun migrate2To3_backfillsSyncIdentities() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO books (id, title, author, format, file_path, cover_path, file_hash, last_location, " +
                    "progress, created_at, last_read_at) VALUES " +
                    "(1, 'One', 'A', 'EPUB', '/books/a.epub', NULL, 'hash-a', 'epubcfi(/6/2)', 0.5, 1000, 2000), " +
                    "(2, 'Two', 'B', 'PDF', '/books/b.pdf', '/covers/b', 'hash-b', NULL, 0, 3000, NULL)",
            )
            for (i in 1..20) {
                db.execSQL(
                    "INSERT INTO annotations (book_id, type, cfi, text, chapter, color, note, position, " +
                        "created_at, updated_at) VALUES (1, 'HIGHLIGHT', 'cfi-$i', 'text', 'ch', 'F6C945', NULL, 0.1, $i, $i)",
                )
            }
        }

        helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT id, updated_at, created_at FROM books ORDER BY id").use { c ->
                assertEquals(2, c.count)
                while (c.moveToNext()) assertEquals(c.getLong(2), c.getLong(1))
            }
            val uuids = mutableListOf<String>()
            db.query("SELECT uuid FROM annotations").use { c ->
                while (c.moveToNext()) uuids += c.getString(0)
            }
            assertEquals(20, uuids.size)
            assertEquals("every annotation gets its own id", 20, uuids.toSet().size)
            assertTrue(uuids.all { UUID_V4.matches(it) })
            db.query("SELECT COUNT(*) FROM sync_tombstones").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun deletesRecordTombstones() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ZenReadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val bookId = db.bookDao().insert(
                BookEntity(
                    title = "T", author = "A", format = "EPUB", filePath = "/f", coverPath = null,
                    fileHash = "hash", lastLocation = null, progress = 0f, createdAt = 5, lastReadAt = null,
                ),
            )
            assertEquals(5L, db.bookDao().get(bookId)!!.updatedAt)
            val annotationId = db.annotationDao().insert(
                AnnotationEntity(
                    bookId = bookId, type = "HIGHLIGHT", cfi = "c", text = "t", chapter = "", color = "F6C945",
                    note = null, position = 0f, createdAt = 1, updatedAt = 1,
                ),
            )
            val uuid = db.annotationDao().get(annotationId)!!.uuid
            db.annotationDao().delete(annotationId)
            assertNull(db.annotationDao().get(annotationId))
            db.bookDao().delete(bookId)

            val tombstones = db.syncDao().tombstones().associateBy { it.kind to it.syncId }
            assertTrue((SyncTombstoneEntity.KIND_ANNOTATION to uuid) in tombstones)
            assertTrue((SyncTombstoneEntity.KIND_BOOK to "hash") in tombstones)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
        val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
