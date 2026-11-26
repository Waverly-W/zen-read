package com.zenread.data.sync.testing

import com.zenread.data.sync.transport.s3.SigV4Signer
import com.zenread.data.sync.transport.sha256Hex
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString

/**
 * A small in-memory WebDAV server: PROPFIND (depth 0/1), MKCOL, PUT, MOVE, GET, DELETE,
 * Basic auth. Collections must exist before PUT (409 otherwise), like Jianguoyun.
 */
class FakeWebDavServer(
    private val user: String = "alice",
    private val password: String = "secret",
    val root: String = "/dav/",
) : Dispatcher() {
    val files = TreeMap<String, ByteArray>()
    val collections = sortedSetOf(root.trimEnd('/'))
    val requests = CopyOnWriteArrayList<String>()
    var moveSupported = true

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.pathSegments.joinToString("/", prefix = "/").trimEnd('/').ifEmpty { "/" }
        requests += "${request.method} $path"
        val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        if (request.headers["Authorization"] != expected) {
            return MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=\"dav\"").build()
        }
        return when (request.method) {
            "PROPFIND" -> propfind(path, request.headers["Depth"] ?: "1", request.url.encodedPath)
            "MKCOL" -> when {
                path in collections || path in files -> code(405)
                parent(path) !in collections -> code(409)
                else -> {
                    collections += path
                    code(201)
                }
            }
            "PUT" -> when {
                parent(path) !in collections -> code(409)
                else -> {
                    files[path] = request.body?.toByteArray() ?: ByteArray(0)
                    code(201)
                }
            }
            "MOVE" -> {
                if (!moveSupported) return code(405)
                val destination = request.headers["Destination"]?.toHttpUrlOrNull()
                    ?: return code(400)
                val target = destination.pathSegments.joinToString("/", prefix = "/").trimEnd('/')
                val bytes = files.remove(path) ?: return code(404)
                if (parent(target) !in collections) return code(409)
                val existed = target in files
                files[target] = bytes
                code(if (existed) 204 else 201)
            }
            "GET" -> files[path]?.let { MockResponse.Builder().code(200).body(okio.Buffer().write(it)).build() } ?: code(404)
            "DELETE" -> when {
                files.remove(path) != null -> code(204)
                collections.remove(path) -> {
                    files.keys.removeIf { it.startsWith("$path/") }
                    code(204)
                }
                else -> code(404)
            }
            else -> code(405)
        }
    }

    private fun propfind(path: String, depth: String, encodedPath: String): MockResponse {
        val responses = StringBuilder()
        fun entry(p: String, isDir: Boolean) {
            val href = p.split('/').joinToString("/") { okhttp3.HttpUrl.Builder().scheme("http").host("h").addPathSegment(it).build().encodedPath.removePrefix("/") } +
                if (isDir) "/" else ""
            responses.append("<d:response><d:href>").append(href).append("</d:href><d:propstat><d:prop>")
            if (isDir) {
                responses.append("<d:resourcetype><d:collection/></d:resourcetype>")
            } else {
                responses.append("<d:resourcetype/><d:getcontentlength>").append(files[p]!!.size).append("</d:getcontentlength>")
                responses.append("<d:getlastmodified>Fri, 27 Nov 2026 10:00:00 GMT</d:getlastmodified>")
            }
            responses.append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        }
        when {
            path in collections -> {
                entry(path, true)
                if (depth == "1") {
                    collections.filter { parent(it) == path }.forEach { entry(it, true) }
                    files.keys.filter { parent(it) == path }.forEach { entry(it, false) }
                }
            }
            path in files && !encodedPath.endsWith("/") -> entry(path, false)
            else -> return code(404)
        }
        return MockResponse.Builder().code(207)
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .body("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">$responses</d:multistatus>")
            .build()
    }

    private fun parent(path: String) = path.substringBeforeLast('/').ifEmpty { "/" }

    private fun code(code: Int) = MockResponse.Builder().code(code).build()
}

/**
 * A small in-memory S3 (path-style): ListObjectsV2 with delimiter and pagination, GET, HEAD,
 * PUT, DELETE. It checks every request's SigV4 signature against what was actually sent and
 * the payload hash of every PUT.
 */
class FakeS3Server(
    private val bucket: String = "books",
    private val credentials: SigV4Signer.Credentials = SigV4Signer.Credentials("AKID", "SECRET"),
    private val region: String = "us-east-1",
    private val pageSize: Int = 2,
) : Dispatcher() {
    val objects = TreeMap<String, ByteArray>()
    val requests = CopyOnWriteArrayList<String>()

    /** When set, the server's clock differs from the client's by this much. */
    var serverClockOffsetMillis = 0L

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += "${request.method} ${request.url.encodedPath}?${request.url.encodedQuery.orEmpty()}"
        val amzDate = request.headers["x-amz-date"] ?: return error(403, "AccessDenied")
        val time = Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).parse(amzDate))
        val serverNow = Instant.now().plusMillis(serverClockOffsetMillis)
        if (kotlin.math.abs(serverNow.toEpochMilli() - time.toEpochMilli()) > 15 * 60_000L) {
            return MockResponse.Builder().code(403)
                .addHeader("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(serverNow.atZone(ZoneOffset.UTC)))
                .body("<Error><Code>RequestTimeTooSkewed</Code></Error>").build()
        }
        val payloadHash = request.headers["x-amz-content-sha256"] ?: return error(400, "MissingHash")
        val expected = SigV4Signer.sign(request.method, request.url, emptyMap(), payloadHash, credentials, region, time)
        if (request.headers["Authorization"] != expected["authorization"]) return error(403, "SignatureDoesNotMatch")
        if (request.headers["Host"] != SigV4Signer.hostHeader(request.url)) return error(400, "HostMismatch")

        val segments = request.url.pathSegments
        if (segments.firstOrNull() != bucket) return error(404, "NoSuchBucket")
        val key = segments.drop(1).joinToString("/")
        return when (request.method) {
            "GET" -> if (key.isEmpty()) list(request) else objects[key]?.let { ok(it) } ?: error(404, "NoSuchKey")
            "HEAD" -> objects[key]?.let { MockResponse.Builder().code(200).addHeader("Content-Length", it.size).addHeader("ETag", "\"x\"").build() }
                ?: MockResponse.Builder().code(404).build()
            "PUT" -> {
                val body = request.body ?: ByteString.EMPTY
                if (sha256Hex(body.toByteArray()) != payloadHash) return error(400, "XAmzContentSHA256Mismatch")
                objects[key] = body.toByteArray()
                MockResponse.Builder().code(200).addHeader("ETag", "\"x\"").build()
            }
            "DELETE" -> {
                objects.remove(key)
                MockResponse.Builder().code(204).build()
            }
            else -> error(405, "MethodNotAllowed")
        }
    }

    private fun list(request: RecordedRequest): MockResponse {
        val url = request.url
        if (url.queryParameter("list-type") != "2") return error(400, "InvalidArgument")
        val prefix = url.queryParameter("prefix").orEmpty()
        val delimiter = url.queryParameter("delimiter")
        val start = url.queryParameter("continuation-token")?.toInt() ?: 0
        val items = sortedSetOf<String>()
        for (key in objects.keys.filter { it.startsWith(prefix) }) {
            val rest = key.removePrefix(prefix)
            val slash = if (delimiter == null) -1 else rest.indexOf(delimiter)
            items += if (slash >= 0) prefix + rest.substring(0, slash + 1) else key
        }
        val all = items.toList()
        val page = all.drop(start).take(pageSize)
        val truncated = start + page.size < all.size
        val xml = StringBuilder("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
        xml.append("<Prefix>").append(escape(prefix)).append("</Prefix><IsTruncated>").append(truncated).append("</IsTruncated>")
        for (item in page) {
            if (item.endsWith("/") && item !in objects) {
                xml.append("<CommonPrefixes><Prefix>").append(escape(item)).append("</Prefix></CommonPrefixes>")
            } else {
                xml.append("<Contents><Key>").append(escape(item)).append("</Key><Size>").append(objects[item]!!.size)
                    .append("</Size><ETag>\"x\"</ETag><LastModified>2026-11-27T10:00:00.000Z</LastModified></Contents>")
            }
        }
        if (truncated) xml.append("<NextContinuationToken>").append(start + page.size).append("</NextContinuationToken>")
        xml.append("</ListBucketResult>")
        return MockResponse.Builder().code(200).body(xml.toString()).build()
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun ok(bytes: ByteArray) = MockResponse.Builder().code(200).body(okio.Buffer().write(bytes)).build()

    private fun error(code: Int, s3Code: String) =
        MockResponse.Builder().code(code).body("<?xml version=\"1.0\"?><Error><Code>$s3Code</Code><Message>m</Message></Error>").build()
}
