package com.zenread.data.sync.transport.s3

import com.zenread.data.sync.transport.RemoteEntry
import com.zenread.data.sync.transport.SafeXml
import com.zenread.data.sync.transport.SyncException
import com.zenread.data.sync.transport.SyncTransport
import com.zenread.data.sync.transport.await
import com.zenread.data.sync.transport.bytesAtMost
import com.zenread.data.sync.transport.checkRelativePath
import com.zenread.data.sync.transport.child
import com.zenread.data.sync.transport.children
import com.zenread.data.sync.transport.saveTo
import com.zenread.data.sync.transport.sha256Hex
import com.zenread.data.sync.transport.text
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * S3-compatible object storage with our own SigV4 signer ([SigV4Signer]), ported from
 * upstream `s3_client.dart`.
 *
 * - Directories are key prefixes; listing uses ListObjectsV2 with `delimiter=/` and follows
 *   continuation tokens.
 * - PUT is atomic and the signed `x-amz-content-sha256` lets the server reject corrupted
 *   uploads.
 * - Redirects are never followed (a redirect means a wrong region or addressing style).
 * - `RequestTimeTooSkewed` is retried once with the server's clock.
 */
class S3Transport(
    private val config: S3Config,
    baseClient: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncTransport {
    init {
        config.validate()
    }

    private val endpoint = config.endpointUrl()
    private val client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val credentials = SigV4Signer.Credentials(config.accessKey.trim(), config.secretKey.trim(), config.sessionToken.trim())

    @Volatile private var clockOffsetMillis = 0L

    override val label: String = "S3 · ${config.bucket}"
    override val identity: String =
        "s3|${endpoint.scheme}://${endpoint.host}:${endpoint.port}|${config.bucket}|${config.normalizedPrefix}|${credentials.accessKey}"

    override suspend fun list(path: String): List<RemoteEntry> = withContext(io) {
        val base = path.trim('/')
        val prefix = if (base.isEmpty() && config.normalizedPrefix.isEmpty()) "" else config.key(base).trimEnd('/') + "/"
        val entries = LinkedHashMap<String, RemoteEntry>()
        var token: String? = null
        var pages = 0
        do {
            val query = buildList {
                add("list-type" to "2")
                add("prefix" to prefix)
                add("delimiter" to "/")
                add("max-keys" to "1000")
                token?.let { add("continuation-token" to it) }
            }
            val url = bucketUrl(query)
            val result = execute("GET", url, null, SigV4Signer.EMPTY_PAYLOAD_SHA256).use {
                if (it.code != 200) throw it.failure("list $path")
                parseListing(it.bytesAtMost(MAX_LISTING_BYTES))
            }
            for (item in result.objects) {
                val name = item.key.removePrefix(prefix)
                // Folder markers ("dir/") and anything deeper are not files of this directory.
                if (!item.key.startsWith(prefix) || name.isEmpty() || name.contains('/')) continue
                entries[name] = RemoteEntry(name, isDirectory = false, size = item.size, etag = item.etag, modifiedAt = item.lastModified)
            }
            for (dir in result.prefixes) {
                val name = dir.removePrefix(prefix).trimEnd('/')
                if (!dir.startsWith(prefix) || name.isEmpty() || name.contains('/')) continue
                entries[name] = RemoteEntry(name, isDirectory = true)
            }
            if (entries.size > MAX_ENTRIES) throw SyncException.Protocol("Directory too large")
            if (result.truncated && result.nextToken.isNullOrEmpty()) throw SyncException.Protocol("Truncated listing without a continuation token")
            token = result.nextToken.takeIf { result.truncated }
            if (++pages > MAX_PAGES) throw SyncException.Protocol("Too many listing pages")
        } while (token != null)
        entries.values.toList()
    }

    override suspend fun stat(path: String): RemoteEntry? = withContext(io) {
        checkRelativePath(path)
        execute("HEAD", objectUrl(path), null, SigV4Signer.EMPTY_PAYLOAD_SHA256).use {
            when (it.code) {
                200 -> RemoteEntry(
                    name = path.substringAfterLast('/'),
                    isDirectory = false,
                    size = it.header("Content-Length")?.toLongOrNull(),
                    etag = it.header("ETag"),
                    modifiedAt = it.header("Last-Modified")?.let { date ->
                        runCatching { ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    },
                )
                404 -> null
                else -> throw it.failure("stat $path")
            }
        }
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray? = withContext(io) {
        checkRelativePath(path)
        execute("GET", objectUrl(path), null, SigV4Signer.EMPTY_PAYLOAD_SHA256).use {
            when (it.code) {
                200 -> it.bytesAtMost(maxBytes)
                404 -> if (it.errorCode() == "NoSuchBucket") throw it.failure("read $path") else null
                else -> throw it.failure("read $path")
            }
        }
    }

    override suspend fun download(path: String, target: File): Boolean = withContext(io) {
        checkRelativePath(path)
        execute("GET", objectUrl(path), null, SigV4Signer.EMPTY_PAYLOAD_SHA256).use {
            when (it.code) {
                200 -> {
                    it.saveTo(target)
                    true
                }
                404 -> if (it.errorCode() == "NoSuchBucket") throw it.failure("download $path") else false
                else -> throw it.failure("download $path")
            }
        }
    }

    override suspend fun write(path: String, bytes: ByteArray, contentType: String) = withContext(io) {
        put(path, bytes.toRequestBody(contentType.toMediaType()), sha256Hex(bytes))
    }

    override suspend fun upload(path: String, source: File, sha256Hex: String?, contentType: String) = withContext(io) {
        put(path, source.asRequestBody(contentType.toMediaType()), sha256Hex ?: sha256Hex(source))
    }

    override suspend fun delete(path: String) = withContext(io) {
        checkRelativePath(path)
        execute("DELETE", objectUrl(path), null, SigV4Signer.EMPTY_PAYLOAD_SHA256).use {
            if (it.code !in setOf(200, 204, 404)) throw it.failure("delete $path")
        }
    }

    /** S3 has no directories. */
    override suspend fun ensureDirectory(path: String) = Unit

    private suspend fun put(path: String, body: RequestBody, payloadHash: String) {
        checkRelativePath(path)
        execute("PUT", objectUrl(path), body, payloadHash).use {
            if (it.code != 200) throw it.failure("upload $path")
        }
    }

    private suspend fun execute(method: String, url: HttpUrl, body: RequestBody?, payloadHash: String, retrySkew: Boolean = true): Response {
        val now = Instant.ofEpochMilli(clock() + clockOffsetMillis)
        val signed = SigV4Signer.sign(method, url, emptyMap(), payloadHash, credentials, config.region, now)
        val request = Request.Builder().url(url).method(method, body).apply {
            signed.forEach { (name, value) -> header(name, value) }
        }.build()
        val response = client.newCall(request).await()
        if (retrySkew && response.code == 403 && method != "HEAD" && response.errorCode() == "RequestTimeTooSkewed") {
            val serverTime = response.headers.getInstant("Date")?.toEpochMilli()
            response.close()
            if (serverTime == null) throw SyncException.ClockSkew()
            clockOffsetMillis = serverTime - clock()
            return execute(method, url, body, payloadHash, retrySkew = false)
        }
        return response
    }

    internal fun objectUrl(path: String): HttpUrl {
        val builder = baseUrlBuilder()
        if (config.pathStyle) builder.addEncodedPathSegment(SigV4Signer.uriEncode(config.bucket))
        config.key(path).split('/').forEach { builder.addEncodedPathSegment(SigV4Signer.uriEncode(it)) }
        return builder.build()
    }

    internal fun bucketUrl(query: List<Pair<String, String>>): HttpUrl {
        val builder = baseUrlBuilder()
        if (config.pathStyle) builder.addEncodedPathSegment(SigV4Signer.uriEncode(config.bucket))
        val encoded = query
            .map { (name, value) -> SigV4Signer.uriEncode(name) to SigV4Signer.uriEncode(value) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        if (encoded.isNotEmpty()) builder.encodedQuery(encoded)
        return builder.build()
    }

    private fun baseUrlBuilder(): HttpUrl.Builder = HttpUrl.Builder()
        .scheme(endpoint.scheme)
        .host(if (config.pathStyle) endpoint.host else "${config.bucket}.${endpoint.host}")
        .port(endpoint.port)

    private fun Response.errorCode(): String? = runCatching {
        val bytes = peekBody(64 * 1024).bytes()
        if (bytes.isEmpty()) null else SafeXml.parse(bytes).documentElement.child("Code")?.text()
    }.getOrNull()

    private fun Response.failure(operation: String): SyncException {
        val error = errorCode()
        val detail = "$operation: HTTP $code${error?.let { " $it" }.orEmpty()}"
        return when {
            error == "RequestTimeTooSkewed" -> SyncException.ClockSkew()
            error == "NoSuchBucket" -> SyncException.NotFound("Bucket not found")
            error == "AuthorizationHeaderMalformed" || error == "PermanentRedirect" || code in 300..399 ->
                SyncException.Config("Check the region, endpoint and path-style setting ($detail)")
            code == 401 || error == "InvalidAccessKeyId" || error == "SignatureDoesNotMatch" ->
                SyncException.Unauthorized("Access key or secret key rejected")
            code == 403 -> SyncException.Forbidden(detail)
            code == 404 -> SyncException.NotFound(detail)
            code == 429 || code == 503 -> SyncException.RateLimited(
                (header("Retry-After")?.toLongOrNull()?.times(1000) ?: 30_000L).coerceIn(1_000L, 30 * 60_000L),
            )
            else -> SyncException.Http(code, detail)
        }
    }

    private data class Listing(
        val objects: List<ListedObject>,
        val prefixes: List<String>,
        val truncated: Boolean,
        val nextToken: String?,
    )

    private data class ListedObject(val key: String, val size: Long?, val etag: String?, val lastModified: Long?)

    private fun parseListing(xml: ByteArray): Listing {
        val root = SafeXml.parse(xml).documentElement
        if (root.localName != "ListBucketResult" && root.tagName != "ListBucketResult") {
            throw SyncException.Protocol("Unexpected S3 listing response")
        }
        val truncatedText = root.child("IsTruncated")?.text()
            ?: throw SyncException.Protocol("S3 listing without IsTruncated")
        return Listing(
            objects = root.children("Contents").map { item ->
                ListedObject(
                    key = item.child("Key")?.text() ?: throw SyncException.Protocol("S3 object without key"),
                    size = item.child("Size")?.text()?.toLongOrNull(),
                    etag = item.child("ETag")?.text(),
                    lastModified = item.child("LastModified")?.text()?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                )
            },
            prefixes = root.children("CommonPrefixes").mapNotNull { it.child("Prefix")?.text() },
            truncated = truncatedText.equals("true", ignoreCase = true),
            nextToken = root.child("NextContinuationToken")?.text(),
        )
    }

    private companion object {
        const val MAX_LISTING_BYTES = 16L * 1024 * 1024
        const val MAX_ENTRIES = 100_000
        const val MAX_PAGES = 1000
    }
}
