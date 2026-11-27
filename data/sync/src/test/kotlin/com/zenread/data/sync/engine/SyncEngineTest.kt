package com.zenread.data.sync.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zenread.core.database.AnnotationEntity
import com.zenread.core.database.BookEntity
import com.zenread.core.database.ZenReadDatabase
import com.zenread.data.sync.testing.FakeS3Server
import com.zenread.data.sync.testing.FakeWebDavServer
import com.zenread.data.sync.testing.MemoryTransport
import com.zenread.data.sync.transport.SyncException
import com.zenread.data.sync.transport.SyncTransport
import com.zenread.data.sync.transport.s3.S3Config
import com.zenread.data.sync.transport.s3.S3Transport
import com.zenread.data.sync.transport.sha256Hex
import com.zenread.data.sync.transport.webdav.WebDavConfig
import com.zenread.data.sync.transport.webdav.WebDavTransport
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two or more devices, each with a real (in-memory) Room database, syncing through one cloud. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root: File = Files.createTempDirectory("zenread-sync").toFile()
    private val cloud = MemoryTransport()
    private val devices = mutableListOf<Device>()

    /** Before any tombstone the DAOs write with the real clock. */
    private val base = System.currentTimeMillis() - 3_600_000

    inner class Device(val name: String, val transport: SyncTransport = cloud) {
        val db: ZenReadDatabase = Room.inMemoryDatabaseBuilder(context, ZenReadDatabase::class.java).build()
        val files = File(root, name)
        var now = base
        val store = RoomSyncLocalStore(db, files) { now }
        val engine = SyncEngine(transport, store, File(files, "cache"), name) { now }

        init {
            devices += this
        }

        suspend fun addBook(content: String, cover: ByteArray? = null): BookEntity {
            val hash = sha256Hex(content.toByteArray())
            val file = store.bookFile(hash, "epub").apply { parentFile!!.mkdirs(); writeText(content) }
            val coverFile = cover?.let { bytes -> store.coverFile(hash).apply { parentFile!!.mkdirs(); writeBytes(bytes) } }
            val id = db.bookDao().insert(
                BookEntity(
                    title = "Title $content", author = "Author", format = "EPUB", filePath = file.absolutePath,
                    coverPath = coverFile?.absolutePath, fileHash = hash, lastLocation = null, progress = 0f,
                    createdAt = tick(), lastReadAt = null,
                ),
            )
            return db.bookDao().get(id)!!
        }

        suspend fun highlight(book: BookEntity, cfi: String, note: String? = null): AnnotationEntity {
            val at = tick()
            val id = db.annotationDao().insert(
                AnnotationEntity(
                    bookId = book.id, type = "HIGHLIGHT", cfi = cfi, text = "text $cfi", chapter = "Ch",
                    color = "F6C945", note = note, position = 0.25f, createdAt = at, updatedAt = at,
                ),
            )
            return db.annotationDao().get(id)!!
        }

        suspend fun read(hash: String, location: String, progress: Float) {
            val book = db.syncDao().bookByHash(hash)!!
            db.bookDao().updateProgress(book.id, location, progress, tick())
        }

        suspend fun book(hash: String) = db.syncDao().bookByHash(hash)
        suspend fun annotations() = db.syncDao().annotations().sortedBy { it.cfi }
        suspend fun sync(options: SyncOptions = SyncOptions()) = engine.sync(options)

        fun tick(): Long {
            now += 1000
            return now
        }
    }

    @After
    fun tearDown() {
        devices.forEach { it.db.close() }
        root.deleteRecursively()
    }

    @Test
    fun libraryProgressAndAnnotations_reachTheOtherDevice() = runBlocking {
        val a = Device("a")
        val b = Device("b")
        val book = a.addBook("moby", cover = byteArrayOf(1, 2, 3))
        a.read(book.fileHash, "epubcfi(/6/4!/4/2)", 0.42f)
        val mark = a.highlight(book, "epubcfi(/6/4!/4/2,/1:0,/1:5)", note = "whale")

        val first = a.sync()
        assertEquals(3, first.pushed)
        assertEquals(1, first.uploadedBooks)
        assertEquals(1, first.uploadedCovers)
        assertNotNull(cloud.files[RemoteLayout.MANIFEST])
        assertNotNull(cloud.files[RemoteLayout.book(book.fileHash, "epub")])
        assertEquals(1, cloud.paths("${RemoteLayout.LOG}/").size)

        val pulled = b.sync()
        assertEquals(3, pulled.pulled)
        assertEquals(1, pulled.downloadedCovers)
        val synced = b.book(book.fileHash)!!
        assertEquals("Title moby", synced.title)
        assertEquals("epubcfi(/6/4!/4/2)", synced.lastLocation)
        assertEquals(0.42f, synced.progress)
        assertEquals(a.book(book.fileHash)!!.lastReadAt, synced.lastReadAt)
        assertTrue(File(synced.coverPath!!).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse("book files download on demand", File(synced.filePath).exists())
        val copy = b.annotations().single()
        assertEquals(mark.uuid, copy.uuid)
        assertEquals("whale", copy.note)
        assertEquals(synced.id, copy.bookId)

        assertTrue(b.engine.downloadBook(book.fileHash, "epub", File(synced.filePath)))
        assertEquals("moby", File(synced.filePath).readText())

        // Nothing changed: no new segment on either side.
        assertEquals(0, a.sync().pushed)
        assertEquals(0, b.sync().pushed)
        assertEquals(1, cloud.paths("${RemoteLayout.LOG}/").size)
    }

    @Test
    fun readingProgress_lastWriterWins() = runBlocking {
        val a = Device("a")
        val b = Device("b")
        val hash = a.addBook("dune").fileHash
        a.sync()
        b.sync()
        a.read(hash, "a-location", 0.3f)
        b.now = a.now + 5000
        b.read(hash, "b-location", 0.6f)

        a.sync()
        b.sync()
        a.sync()
        for (device in listOf(a, b)) {
            val book = device.book(hash)!!
            assertEquals("b-location", book.lastLocation)
            assertEquals(0.6f, book.progress)
        }
    }

    @Test
    fun deletedAnnotation_staysDeletedDespiteOfflineEdit() = runBlocking {
        val a = Device("a")
        val b = Device("b")
        val book = a.addBook("emma")
        val mark = a.highlight(book, "cfi-1")
        a.sync()
        b.sync()

        a.db.annotationDao().delete(mark.id)
        // B edits the note later, offline.
        val onB = b.annotations().single()
        b.db.annotationDao().updateNote(onB.id, "late edit", System.currentTimeMillis() + 60_000)

        a.sync()
        b.sync()
        a.sync()
        assertTrue(a.annotations().isEmpty())
        assertTrue(b.annotations().isEmpty())
    }

    @Test
    fun deletedBook_isRemovedWithItsFilesElsewhere() = runBlocking {
        val a = Device("a")
        val b = Device("b")
        val book = a.addBook("ivanhoe", cover = byteArrayOf(9))
        a.highlight(book, "cfi")
        a.sync()
        b.sync()
        val onB = b.book(book.fileHash)!!
        b.engine.downloadBook(book.fileHash, "epub", File(onB.filePath))
        assertTrue(File(onB.filePath).exists())

        a.db.bookDao().delete(book.id)
        a.sync()
        b.sync()
        assertNull(b.book(book.fileHash))
        assertFalse(File(onB.filePath).exists())
        assertFalse(File(onB.coverPath!!).exists())
        assertTrue(b.annotations().isEmpty())

        // Importing the same file again brings it back, newer than the deletion.
        val c = Device("c")
        c.now = System.currentTimeMillis() + 60_000
        c.addBook("ivanhoe")
        c.sync()
        b.sync()
        assertNotNull(b.book(book.fileHash))
    }

    @Test
    fun sameRangeMarkedOnTwoDevices_convergesToOneAnnotation() = runBlocking {
        val a = Device("a")
        val b = Device("b")
        val book = a.addBook("persuasion")
        a.sync()
        b.sync()
        a.highlight(book, "same-cfi", note = "from a")
        b.now = a.now + 10_000
        b.highlight(b.book(book.fileHash)!!, "same-cfi", note = "from b")

        a.sync()
        b.sync()
        a.sync()
        b.sync()
        val onA = a.annotations().single()
        val onB = b.annotations().single()
        assertEquals(onA.uuid, onB.uuid)
        assertEquals("from b", onA.note)
    }

    @Test
    fun disabledKinds_areNeitherPublishedNorApplied() = runBlocking {
        val a = Device("a")
        val b = Device("b")
        val book = a.addBook("walden")
        a.highlight(book, "cfi")
        a.read(book.fileHash, "loc", 0.5f)
        a.sync(SyncOptions(progress = false, annotations = false, uploadBooks = false))
        assertNull(cloud.files[RemoteLayout.book(book.fileHash, "epub")])

        b.sync()
        assertNotNull(b.book(book.fileHash))
        assertNull(b.book(book.fileHash)!!.lastLocation)
        assertTrue(b.annotations().isEmpty())

        a.sync()
        b.sync(SyncOptions(annotations = false))
        assertEquals("loc", b.book(book.fileHash)!!.lastLocation)
        assertTrue(b.annotations().isEmpty())
    }

    @Test
    fun compaction_keepsEverythingIncludingTombstones() = runBlocking {
        val a = Device("a")
        val book = a.addBook("ulysses")
        val doomed = a.highlight(book, "doomed")
        a.sync()
        a.db.annotationDao().delete(doomed.id)
        repeat(SegmentLog.COMPACT_THRESHOLD + 1) { i ->
            a.read(book.fileHash, "loc-$i", i / 100f)
            a.sync()
        }
        assertTrue("log was compacted", cloud.paths("${RemoteLayout.LOG}/").size <= 2)

        // A device that missed everything still sees the deletion and the latest position.
        val b = Device("b")
        val c = Device("c")
        c.addBook("ulysses")
        c.highlight(c.book(book.fileHash)!!, "kept")
        c.sync()
        b.sync()
        assertEquals("loc-${SegmentLog.COMPACT_THRESHOLD}", b.book(book.fileHash)!!.lastLocation)
        assertEquals(listOf("kept"), b.annotations().map { it.cfi })
    }

    @Test
    fun newerCloudFormat_isRefusedWithoutWriting() = runBlocking {
        cloud.files[RemoteLayout.MANIFEST] = """{"format":"zenread-sync","version":2}""".toByteArray()
        val a = Device("a")
        a.addBook("future")
        try {
            a.sync()
            fail()
        } catch (e: SyncException.Incompatible) {
            // expected
        }
        assertEquals(setOf(RemoteLayout.MANIFEST), cloud.files.keys)
    }

    @Test
    fun interruptedPublish_isRepeatedByTheNextSync() = runBlocking {
        val a = Device("a")
        a.addBook("odyssey")
        a.sync()
        val hash = a.db.syncDao().books().single().fileHash
        a.read(hash, "half", 0.5f)
        cloud.failWrites = { it.startsWith("${RemoteLayout.LOG}/") }
        try {
            a.sync()
            fail()
        } catch (e: SyncException) {
            // expected
        }
        cloud.failWrites = { false }
        a.sync()

        val b = Device("b")
        b.sync()
        assertEquals("half", b.book(hash)!!.lastLocation)
    }

    @Test
    fun overWebDav_twoDevicesConverge() = runBlocking {
        MockWebServer().use { server ->
            val dav = FakeWebDavServer()
            server.dispatcher = dav
            server.start()
            fun transport() = WebDavTransport(WebDavConfig(server.url("/dav/").toString(), "alice", "secret"), OkHttpClient())
            assertConverges(Device("a", transport()), Device("b", transport()))
            assertTrue(dav.files.keys.any { it.startsWith("/dav/ZenRead/v1/books/") })
            assertTrue(dav.files.keys.none { it.contains(".zenread-") })
        }
    }

    @Test
    fun overS3_twoDevicesConverge() = runBlocking {
        MockWebServer().use { server ->
            val s3 = FakeS3Server()
            server.dispatcher = s3
            server.start()
            fun transport() = S3Transport(
                S3Config(server.url("/").toString(), "us-east-1", "books", "sync", "AKID", "SECRET", pathStyle = true),
                OkHttpClient(),
            )
            assertConverges(Device("a", transport()), Device("b", transport()))
            assertTrue(s3.objects.keys.any { it.startsWith("sync/ZenRead/v1/books/") })
        }
    }

    @Test
    fun connectionTest_cleansUpItsProbe() = runBlocking {
        val a = Device("a")
        a.engine.testConnection()
        assertTrue(cloud.files.isEmpty())
    }

    private suspend fun assertConverges(a: Device, b: Device) {
        val book = a.addBook("http-book", cover = byteArrayOf(7))
        a.highlight(book, "cfi-a")
        a.read(book.fileHash, "a-loc", 0.1f)
        a.sync()
        b.sync()
        val onB = b.book(book.fileHash)!!
        b.highlight(onB, "cfi-b")
        b.now = a.now + 10_000
        b.read(book.fileHash, "b-loc", 0.9f)
        b.sync()
        a.sync()
        assertEquals(listOf("cfi-a", "cfi-b"), a.annotations().map { it.cfi })
        assertEquals(a.annotations().map { it.uuid }, b.annotations().map { it.uuid })
        assertEquals("b-loc", a.book(book.fileHash)!!.lastLocation)
        assertTrue(b.engine.downloadBook(book.fileHash, "epub", File(onB.filePath)))
        assertEquals("http-book", File(onB.filePath).readText())
        assertNotNull(b.book(book.fileHash)!!.coverPath)
    }
}
