package com.zenread.data.sync.transport

import com.zenread.data.sync.testing.FakeWebDavServer
import com.zenread.data.sync.transport.webdav.WebDavConfig
import com.zenread.data.sync.transport.webdav.WebDavTransport
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
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

class WebDavTransportTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun transport(password: String = "secret", path: String = "/dav/") =
        WebDavTransport(WebDavConfig(server.url(path).toString(), "alice", password), client)

    @Test
    fun roundTrip_createsParentsAndWritesThroughTemporaryMove() = runBlocking {
        val dav = FakeWebDavServer()
        server.dispatcher = dav
        val webdav = transport()

        webdav.ensureDirectory("ZenRead/v1/log")
        assertTrue("/dav/ZenRead/v1/log" in dav.collections)
        webdav.write("ZenRead/v1/log/a b%.json", "hello".toByteArray())

        assertArrayEquals("hello".toByteArray(), dav.files["/dav/ZenRead/v1/log/a b%.json"])
        assertTrue("written under a temporary name, then moved", dav.requests.any { it.startsWith("PUT /dav/ZenRead/v1/log/.zenread-") })
        assertTrue(dav.requests.any { it.startsWith("MOVE ") })
        assertTrue("no temporary file left", dav.files.keys.none { it.contains(".zenread-") })

        val listing = webdav.list("ZenRead/v1/log")!!
        assertEquals(listOf("a b%.json"), listing.map { it.name })
        assertEquals(5L, listing.single().size)
        assertEquals(listOf("log"), webdav.list("ZenRead/v1")!!.filter { it.isDirectory }.map { it.name })
        assertArrayEquals("hello".toByteArray(), webdav.read("ZenRead/v1/log/a b%.json"))
        assertEquals(5L, webdav.stat("ZenRead/v1/log/a b%.json")!!.size)

        val target = Files.createTempDirectory("dav").toFile().resolve("out.bin")
        assertTrue(webdav.download("ZenRead/v1/log/a b%.json", target))
        assertEquals("hello", target.readText())

        webdav.delete("ZenRead/v1/log/a b%.json")
        webdav.delete("ZenRead/v1/log/a b%.json") // already gone: no error
        assertNull(webdav.read("ZenRead/v1/log/a b%.json"))
        assertNull(webdav.stat("ZenRead/v1/log/a b%.json"))
        assertNull(webdav.list("ZenRead/missing"))
        assertFalse(webdav.download("ZenRead/missing.bin", target))
    }

    @Test
    fun serverWithoutMove_fallsBackToDirectPut() = runBlocking {
        val dav = FakeWebDavServer().apply { moveSupported = false }
        server.dispatcher = dav
        val webdav = transport()
        val file = File.createTempFile("book", ".epub").apply { writeText("book") }
        webdav.upload("ZenRead/v1/books/x.epub", file)
        webdav.upload("ZenRead/v1/books/y.epub", file)
        assertEquals(setOf("/dav/ZenRead/v1/books/x.epub", "/dav/ZenRead/v1/books/y.epub"), dav.files.keys)
        assertEquals("MOVE is probed once", 1, dav.requests.count { it.startsWith("MOVE") })
    }

    @Test
    fun wrongPassword_isAnAuthError() = runBlocking {
        server.dispatcher = FakeWebDavServer()
        try {
            transport(password = "wrong").list("")
            fail()
        } catch (e: SyncException.Unauthorized) {
            assertFalse("no credentials in messages", e.message.orEmpty().contains("wrong"))
        }
    }

    @Test
    fun digestChallenge_isAnswered() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(401)
                .addHeader("WWW-Authenticate", "Digest realm=\"dav\", nonce=\"abc123\", qop=\"auth\", opaque=\"xyz\", algorithm=MD5")
                .build(),
        )
        server.enqueue(multistatus("<d:response><d:href>/dav/</d:href>${dirProps()}</d:response>"))
        assertEquals(emptyList<RemoteEntry>(), transport().list(""))

        assertTrue(server.takeRequest().headers["Authorization"]!!.startsWith("Basic "))
        val digest = server.takeRequest().headers["Authorization"]!!
        assertTrue(digest.startsWith("Digest username=\"alice\""))
        val params = Regex("""(\w+)="?([^",]*)"?""").findAll(digest.removePrefix("Digest ")).associate { it.groupValues[1] to it.groupValues[2] }
        fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).toHex()
        val ha1 = md5("alice:dav:secret")
        val ha2 = md5("PROPFIND:/dav/")
        assertEquals(md5("$ha1:abc123:${params["nc"]}:${params["cnonce"]}:auth:$ha2"), params["response"])
        assertEquals("xyz", params["opaque"])
    }

    @Test
    fun busyServer_triggersCooldownWithoutFurtherRequests() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "120").build())
        val webdav = transport(path = "/busy/")
        try {
            webdav.list("x")
            fail()
        } catch (e: SyncException.RateLimited) {
            assertTrue(e.retryAfterMillis in 60_000..120_000)
        }
        try {
            webdav.read("x/y")
            fail()
        } catch (e: SyncException.RateLimited) {
            assertTrue(e.retryable)
        }
        assertEquals("the second call never reached the server", 1, server.requestCount)
    }

    @Test
    fun crossOriginRedirect_isNotFollowed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://elsewhere.example/dav/").build())
        try {
            transport().list("ZenRead")
            fail()
        } catch (e: SyncException) {
            assertEquals(SyncErrorKind.SERVER, e.kind)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun trailingSlashRedirect_isFollowedForDirectories() = runBlocking {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", server.url("/dav/ZenRead").toString()).build())
        server.enqueue(multistatus("<d:response><d:href>/dav/ZenRead</d:href>${dirProps()}</d:response>"))
        assertEquals(emptyList<RemoteEntry>(), transport().list("ZenRead"))
        server.takeRequest()
        assertEquals("/dav/ZenRead", server.takeRequest().url.encodedPath)
    }

    @Test
    fun pagedListing_followsNextLinks() = runBlocking {
        server.enqueue(
            multistatus(
                "<d:response><d:href>/dav/log/</d:href>${dirProps()}</d:response>" + fileResponse("/dav/log/a.json"),
                link = "</dav/log/?marker=a.json>; rel=\"next\"",
            ),
        )
        server.enqueue(multistatus("<d:response><d:href>/dav/log/</d:href>${dirProps()}</d:response>" + fileResponse("/dav/log/b.json")))
        assertEquals(listOf("a.json", "b.json"), transport().list("log")!!.map { it.name })
        server.takeRequest()
        assertEquals("marker=a.json", server.takeRequest().url.encodedQuery)
    }

    @Test
    fun hrefsWithFullUrlsAndOtherEncodings_areMatched() = runBlocking {
        server.enqueue(
            multistatus(
                "<d:response><d:href>${server.url("/dav/%E4%B8%AD/")}</d:href>${dirProps()}</d:response>" +
                    fileResponse("/dav/%e4%b8%ad/a%2Bb.json"),
            ),
        )
        assertEquals(listOf("a+b.json"), transport().list("中")!!.map { it.name })
    }

    @Test(expected = SyncException.Config::class)
    fun credentialsInUrl_areRejected() {
        WebDavConfig("https://bob:pw@example.com/dav", "", "").baseUrl()
    }

    private fun dirProps() =
        "<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>"

    private fun fileResponse(href: String) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1</d:getcontentlength>" +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun multistatus(body: String, link: String? = null) = MockResponse.Builder().code(207)
        .body("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">$body</d:multistatus>")
        .apply { if (link != null) addHeader("Link", link) }
        .build()
}
