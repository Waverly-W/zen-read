package com.zenread.data.sync.transport

import com.zenread.data.sync.testing.FakeS3Server
import com.zenread.data.sync.transport.s3.S3Config
import com.zenread.data.sync.transport.s3.S3Transport
import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class S3TransportTest {
    private val server = MockWebServer()
    private val s3 = FakeS3Server()

    @Before
    fun setUp() {
        server.dispatcher = s3
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun transport(secret: String = "SECRET", bucket: String = "books", prefix: String = "my lib/中文") = S3Transport(
        S3Config(
            endpoint = server.url("/").toString().trimEnd('/'),
            region = "us-east-1",
            bucket = bucket,
            prefix = prefix,
            accessKey = "AKID",
            secretKey = secret,
            pathStyle = true,
        ),
        OkHttpClient(),
    )

    @Test
    fun roundTrip_withSignedRequestsAndPagedListing() = runBlocking {
        val bucket = transport()
        for (name in listOf("a.json", "b +%.json", "c.json", "d.json", "e.json")) {
            bucket.write("ZenRead/v1/log/$name", name.toByteArray())
        }
        bucket.write("ZenRead/v1/log/sub/deeper.json", "x".toByteArray())
        assertTrue("prefix applied to keys", s3.objects.keys.all { it.startsWith("my lib/中文/ZenRead/v1/log/") })

        val listing = bucket.list("ZenRead/v1/log")
        assertEquals(setOf("a.json", "b +%.json", "c.json", "d.json", "e.json", "sub"), listing.map { it.name }.toSet())
        assertTrue(listing.single { it.name == "sub" }.isDirectory)
        assertEquals(1795773600000L, listing.single { it.name == "a.json" }.modifiedAt)
        assertTrue("listing was paged", s3.requests.count { it.contains("continuation-token=") } >= 2)

        assertArrayEquals("b +%.json".toByteArray(), bucket.read("ZenRead/v1/log/b +%.json"))
        assertEquals(9L, bucket.stat("ZenRead/v1/log/b +%.json")!!.size)
        bucket.delete("ZenRead/v1/log/b +%.json")
        assertNull(bucket.read("ZenRead/v1/log/b +%.json"))
        assertNull(bucket.stat("ZenRead/v1/log/b +%.json"))
        assertEquals(emptyList<RemoteEntry>(), bucket.list("nothing/here"))
    }

    @Test
    fun uploadAndDownloadFiles() = runBlocking {
        val bucket = transport(prefix = "")
        val source = File.createTempFile("book", ".epub").apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }
        bucket.upload("ZenRead/v1/books/x.epub", source, sha256Hex(source))
        assertTrue("ZenRead/v1/books/x.epub" in s3.objects)
        val target = File(source.parentFile, "downloaded-${System.nanoTime()}")
        assertTrue(bucket.download("ZenRead/v1/books/x.epub", target))
        assertArrayEquals(source.readBytes(), target.readBytes())
        assertFalse(bucket.download("ZenRead/v1/books/missing.epub", target))
    }

    @Test
    fun wrongSecret_isAnAuthErrorWithoutSecrets() = runBlocking {
        try {
            transport(secret = "WRONG-SECRET").list("")
            fail()
        } catch (e: SyncException.Unauthorized) {
            assertFalse(e.message.orEmpty().contains("WRONG-SECRET"))
        }
    }

    @Test
    fun missingBucket_isNotFound() = runBlocking {
        try {
            transport(bucket = "other-bucket").read("x")
            fail()
        } catch (e: SyncException.NotFound) {
            assertEquals(SyncErrorKind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun clockSkew_isCorrectedFromTheServerDate() = runBlocking {
        s3.serverClockOffsetMillis = 3 * 3600_000L
        val bucket = transport()
        bucket.write("ZenRead/v1/manifest.json", "{}".toByteArray())
        assertArrayEquals("{}".toByteArray(), bucket.read("ZenRead/v1/manifest.json"))
        assertEquals("one rejected request, then corrected", 1, s3.requests.size - 2)
    }
}
