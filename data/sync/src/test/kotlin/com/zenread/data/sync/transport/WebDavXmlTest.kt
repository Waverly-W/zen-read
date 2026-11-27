package com.zenread.data.sync.transport

import com.zenread.data.sync.transport.webdav.WebDavXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavXmlTest {
    @Test
    fun nextcloudStyle_usesOnly200Propstats() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
             <d:response>
              <d:href>/remote.php/dav/files/alice/ZenRead/v1/log/</d:href>
              <d:propstat>
               <d:prop>
                <d:resourcetype><d:collection/></d:resourcetype>
                <d:getlastmodified>Fri, 27 Nov 2026 10:00:00 GMT</d:getlastmodified>
                <d:getetag>"5f0a"</d:getetag>
               </d:prop>
               <d:status>HTTP/1.1 200 OK</d:status>
              </d:propstat>
              <d:propstat>
               <d:prop><d:getcontentlength/></d:prop>
               <d:status>HTTP/1.1 404 Not Found</d:status>
              </d:propstat>
             </d:response>
             <d:response>
              <d:href>/remote.php/dav/files/alice/ZenRead/v1/log/a%20b.json</d:href>
              <d:propstat>
               <d:prop><d:resourcetype/><d:getcontentlength>123</d:getcontentlength><d:getetag>&quot;e1&quot;</d:getetag></d:prop>
               <d:status>HTTP/1.1 200 OK</d:status>
              </d:propstat>
             </d:response>
            </d:multistatus>
        """.trimIndent()
        val resources = WebDavXml.parseMultistatus(xml.toByteArray())
        assertEquals(2, resources.size)
        val dir = resources[0]
        assertTrue(dir.isCollection)
        assertNull("404 propstat ignored", dir.contentLength)
        assertEquals("\"5f0a\"", dir.etag)
        assertEquals(1795773600000L, dir.lastModified)
        val file = resources[1]
        assertFalse(file.isCollection)
        assertEquals("/remote.php/dav/files/alice/ZenRead/v1/log/a%20b.json", file.href)
        assertEquals(123L, file.contentLength)
        assertEquals("\"e1\"", file.etag)
    }

    @Test
    fun jianguoyunStyle_upperCasePrefixAndEncodedUnicode() {
        val xml = """<D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/%E6%88%91%E7%9A%84/</D:href>""" +
            """<D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>""" +
            """<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>""" +
            """<D:response><D:href>/dav/%E6%88%91%E7%9A%84/book.epub</D:href><D:propstat><D:prop>""" +
            """<D:getcontentlength>5</D:getcontentlength><D:resourcetype/></D:prop>""" +
            """<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"""
        val resources = WebDavXml.parseMultistatus(xml.toByteArray())
        assertEquals(listOf(true, false), resources.map { it.isCollection })
        assertEquals(5L, resources[1].contentLength)
    }

    @Test
    fun defaultNamespace_andResponseLevelStatus() {
        val xml = """<multistatus xmlns="DAV:"><response><href>http://host/dav/missing</href>""" +
            """<status>HTTP/1.1 404 Not Found</status></response></multistatus>"""
        val resource = WebDavXml.parseMultistatus(xml.toByteArray()).single()
        assertEquals(404, resource.status)
        assertFalse(resource.hasProperties)
    }

    @Test(expected = SyncException.Protocol::class)
    fun doctypeAndExternalEntitiesAreRejected() {
        val xml = """<?xml version="1.0"?><!DOCTYPE m [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" +
            """<d:multistatus xmlns:d="DAV:"><d:response><d:href>&x;</d:href></d:response></d:multistatus>"""
        WebDavXml.parseMultistatus(xml.toByteArray())
    }

    @Test(expected = SyncException.Protocol::class)
    fun htmlErrorPageIsNotAListing() {
        WebDavXml.parseMultistatus("<html><body>Login</body></html>".toByteArray())
    }

    @Test
    fun linkHeader_nextPage() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/x/?marker=abc",
            WebDavXml.nextLink("<https://dav.jianguoyun.com/dav/x/?marker=abc>; rel=\"next\""),
        )
        assertEquals("/p2", WebDavXml.nextLink("</p0>; rel=\"prev\", </p2>; rel=next"))
        assertNull(WebDavXml.nextLink("</p0>; rel=\"prev\""))
        assertNull(WebDavXml.nextLink(null))
    }
}
