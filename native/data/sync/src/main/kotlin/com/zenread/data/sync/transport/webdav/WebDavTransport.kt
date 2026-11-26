package com.zenread.data.sync.transport.webdav

import com.zenread.data.sync.transport.RemoteEntry
import com.zenread.data.sync.transport.SyncException
import com.zenread.data.sync.transport.SyncLog
import com.zenread.data.sync.transport.SyncTransport
import com.zenread.data.sync.transport.await
import com.zenread.data.sync.transport.bytesAtMost
import com.zenread.data.sync.transport.checkRelativePath
import com.zenread.data.sync.transport.remotePath
import com.zenread.data.sync.transport.saveTo
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class WebDavConfig(val url: String, val username: String, val password: String) {
    /** The base URL with a trailing slash; throws [SyncException.Config] when unusable. */
    fun baseUrl(): HttpUrl {
        val parsed = url.trim().toHttpUrlOrNull()
            ?: throw SyncException.Config("Enter an http:// or https:// WebDAV URL")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw SyncException.Config("Put the user name and password in their own fields, not in the URL")
        }
        if (parsed.query != null || parsed.fragment != null) {
            throw SyncException.Config("The WebDAV URL must not contain ? or #")
        }
        return if (parsed.encodedPath.endsWith("/")) parsed else parsed.newBuilder().addPathSegment("").build()
    }

    override fun toString() = "WebDavConfig(url=<redacted>, username=<redacted>, password=<redacted>)"
}

/**
 * WebDAV over OkHttp, ported from upstream `webdav_client.dart` / `webdav_sync_support.dart`.
 *
 * Server quirks handled:
 * - Credentials are sent preemptively as Basic (saves one round trip per request, which
 *   matters for Jianguoyun's quota); a `Digest` challenge switches to Digest.
 * - Redirects are never followed automatically, so credentials cannot leak to another
 *   origin. PROPFIND follows only same-origin redirects that add or remove a trailing slash.
 * - Listings follow `Link: <…>; rel="next"` pages (Jianguoyun pages at 750 entries).
 * - Parent collections are created before PUT (Jianguoyun answers 409 otherwise); 405 on
 *   MKCOL means the collection already exists.
 * - Writes go to a hidden `.zenread-<uuid>.part` and are MOVEd into place; servers
 *   without MOVE fall back to a direct PUT.
 * - HTTP 429/503 trip a per-account cooldown ([WebDavRequestPolicy]).
 */
class WebDavTransport(
    private val config: WebDavConfig,
    baseClient: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SyncTransport {
    private val root: HttpUrl = config.baseUrl()
    private val policy = WebDavRequestPolicy.forAccount(root, config.username)
    private val client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val jianguoyun = isJianguoyun(root)

    @Volatile private var digest: DigestAuth? = null

    @Volatile private var moveSupported: Boolean? = null
    private val knownDirectories = ConcurrentHashMap.newKeySet<String>()

    override val label: String = "WebDAV · ${root.host}"
    override val identity: String = "webdav|${root.scheme}://${root.host}:${root.port}${root.encodedPath}|${config.username}"
    override val minAutoSyncIntervalMillis: Long = if (jianguoyun) 10 * 60_000L else 0L

    override suspend fun list(path: String): List<RemoteEntry>? = withContext(io) {
        val base = url(path, directory = true)
        var target = base
        val entries = LinkedHashMap<String, RemoteEntry>()
        var redirects = 0
        var pages = 0
        while (true) {
            val response = send(propfind(target, depth = 1))
            val next = response.use {
                when (it.code) {
                    207 -> Unit
                    404 -> if (pages == 0 && redirects == 0) return@withContext null else throw SyncException.NotFound("Directory vanished: $path")
                    301, 302, 307, 308 -> {
                        if (++redirects > 3) throw SyncException.Protocol("Too many redirects")
                        target = directoryRedirect(it, target)
                        return@use target
                    }
                    else -> throw it.failure("list $path")
                }
                val resources = WebDavXml.parseMultistatus(it.bytesAtMost(MAX_LISTING_BYTES))
                if (resources.isEmpty()) throw SyncException.Protocol("Empty WebDAV listing")
                for (resource in resources) {
                    val name = childName(target, base, resource) ?: continue
                    if (resource.status != null && resource.status != 200) continue
                    entries[name] = RemoteEntry(
                        name = name,
                        isDirectory = resource.isCollection,
                        size = resource.contentLength,
                        etag = resource.etag,
                        modifiedAt = resource.lastModified,
                    )
                    if (entries.size > MAX_ENTRIES) throw SyncException.Protocol("Directory too large")
                }
                val link = WebDavXml.nextLink(it.header("Link"))
                if (link == null) {
                    // A full Jianguoyun page without a continuation link may be truncated.
                    if (jianguoyun && resources.size >= JIANGUOYUN_PAGE) {
                        throw SyncException.Protocol("Listing may be truncated")
                    }
                    null
                } else {
                    val resolved = target.resolve(link)
                    if (resolved == null || resolved.host != base.host || resolved.port != base.port ||
                        resolved.scheme != base.scheme || resolved.encodedPath != base.encodedPath
                    ) {
                        throw SyncException.Protocol("Invalid listing page link")
                    }
                    if (++pages > MAX_PAGES) throw SyncException.Protocol("Too many listing pages")
                    resolved
                }
            } ?: break
            target = next
        }
        entries.values.toList()
    }

    override suspend fun stat(path: String): RemoteEntry? = withContext(io) {
        checkRelativePath(path)
        send(propfind(url(path), depth = 0)).use {
            when (it.code) {
                207 -> Unit
                404 -> return@withContext null
                else -> throw it.failure("stat $path")
            }
            val resource = WebDavXml.parseMultistatus(it.bytesAtMost(MAX_LISTING_BYTES)).firstOrNull()
                ?: throw SyncException.Protocol("Empty WebDAV response")
            if (resource.status == 404) return@withContext null
            if (!resource.hasProperties) throw SyncException.Protocol("WebDAV properties unreadable")
            RemoteEntry(
                name = path.substringAfterLast('/'),
                isDirectory = resource.isCollection,
                size = resource.contentLength,
                etag = resource.etag,
                modifiedAt = resource.lastModified,
            )
        }
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray? = withContext(io) {
        checkRelativePath(path)
        send(Request.Builder().url(url(path)).get().build()).use {
            when (it.code) {
                200 -> it.bytesAtMost(maxBytes)
                404 -> null
                else -> throw it.failure("read $path")
            }
        }
    }

    override suspend fun download(path: String, target: File): Boolean = withContext(io) {
        checkRelativePath(path)
        send(Request.Builder().url(url(path)).get().build()).use {
            when (it.code) {
                200 -> {
                    it.saveTo(target)
                    true
                }
                404 -> false
                else -> throw it.failure("download $path")
            }
        }
    }

    override suspend fun write(path: String, bytes: ByteArray, contentType: String) = withContext(io) {
        putAtomically(path, bytes.toRequestBody(contentType.toMediaType()))
    }

    override suspend fun upload(path: String, source: File, sha256Hex: String?, contentType: String) = withContext(io) {
        putAtomically(path, source.asRequestBody(contentType.toMediaType()))
    }

    override suspend fun delete(path: String) = withContext(io) {
        checkRelativePath(path)
        send(Request.Builder().url(url(path)).delete().build()).use {
            if (it.code !in setOf(200, 202, 204, 404)) throw it.failure("delete $path")
        }
    }

    override suspend fun ensureDirectory(path: String) = withContext(io) { mkcol(path.trim('/')) }

    // --- Internals -------------------------------------------------------------------------

    private suspend fun mkcol(path: String) {
        if (path.isEmpty() || path in knownDirectories) return
        checkRelativePath(path)
        var code = send(Request.Builder().url(url(path, directory = true)).method("MKCOL", null).build()).use { it.code }
        if (code == 409) {
            // Parent missing: create the chain top-down, then retry.
            mkcol(path.substringBeforeLast('/', ""))
            code = send(Request.Builder().url(url(path, directory = true)).method("MKCOL", null).build()).use { it.code }
        }
        when (code) {
            200, 201, 204, 405 -> Unit
            401 -> throw SyncException.Unauthorized()
            else -> {
                // Some servers answer MKCOL on an existing collection with 403/409/301.
                if (stat(path)?.isDirectory != true) {
                    throw if (code == 403) SyncException.Forbidden("Cannot create $path") else SyncException.Http(code, "MKCOL $path: HTTP $code")
                }
            }
        }
        var current = path
        while (current.isNotEmpty()) {
            knownDirectories += current
            current = current.substringBeforeLast('/', "")
        }
    }

    private suspend fun putAtomically(path: String, body: RequestBody) {
        checkRelativePath(path)
        val parent = path.substringBeforeLast('/', "")
        mkcol(parent)
        if (moveSupported != false) {
            val temp = remotePath(parent, ".zenread-${UUID.randomUUID()}.part")
            put(temp, body)
            val moved = try {
                move(temp, path)
            } catch (e: Exception) {
                runCatching { delete(temp) }
                throw e
            }
            if (moved) {
                moveSupported = true
                return
            }
            SyncLog.i("WebDAV MOVE unsupported; using direct PUT")
            moveSupported = false
            runCatching { delete(temp) }
        }
        put(path, body)
    }

    private suspend fun put(path: String, body: RequestBody) {
        var code = send(Request.Builder().url(url(path)).put(body).build()).use { it.code }
        if (code == 409) {
            knownDirectories.remove(path.substringBeforeLast('/', ""))
            mkcol(path.substringBeforeLast('/', ""))
            code = send(Request.Builder().url(url(path)).put(body).build()).use { it.code }
        }
        if (code !in setOf(200, 201, 204)) throw statusFailure(code, "upload $path")
    }

    /** True when moved; false when the server does not support MOVE. */
    private suspend fun move(from: String, to: String): Boolean {
        val request = Request.Builder()
            .url(url(from))
            .method("MOVE", null)
            .header("Destination", url(to).toString())
            .header("Overwrite", "T")
            .build()
        val code = send(request).use { it.code }
        return when (code) {
            200, 201, 204 -> true
            400, 405, 501, 502 -> false
            else -> throw statusFailure(code, "move to $to")
        }
    }

    /** Sends with credentials and the request policy; retries once on a Digest challenge. */
    private suspend fun send(request: Request): Response {
        policy.beforeRequest()
        val usedDigest = digest
        var response = client.newCall(authorize(request)).await()
        policy.observe(response.code, response.header("Retry-After"))
        if (response.code == 401) {
            val challenge = response.challenges().firstOrNull { it.scheme.equals("Digest", ignoreCase = true) }
            val auth = challenge?.let { DigestAuth(config.username, config.password, it) }
            val stale = challenge?.authParams?.get("stale").equals("true", ignoreCase = true)
            if (auth != null && auth.supported && (usedDigest == null || stale || usedDigest.nonce != auth.nonce)) {
                response.close()
                digest = auth
                policy.beforeRequest()
                response = client.newCall(authorize(request)).await()
                policy.observe(response.code, response.header("Retry-After"))
            }
        }
        if (response.code == 429 || response.code == 503) {
            response.close()
            throw SyncException.RateLimited(policy.cooldownRemaining())
        }
        return response
    }

    private fun authorize(request: Request): Request {
        if (config.username.isEmpty() && config.password.isEmpty()) return request
        val header = digest?.authorization(request) ?: Credentials.basic(config.username, config.password, Charsets.UTF_8)
        return request.newBuilder().header("Authorization", header).build()
    }

    private fun propfind(url: HttpUrl, depth: Int) = Request.Builder()
        .url(url)
        .method("PROPFIND", WebDavXml.PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
        .header("Depth", depth.toString())
        .header("Accept", "application/xml, text/xml")
        .build()

    /** Only same-origin redirects that change nothing but a trailing slash. */
    private fun directoryRedirect(response: Response, current: HttpUrl): HttpUrl {
        val next = response.header("Location")?.let(current::resolve)
            ?: throw SyncException.Http(response.code, "Redirect without location")
        if (next.scheme != current.scheme || next.host != current.host || next.port != current.port ||
            next.username.isNotEmpty() || next.query != current.query ||
            next.encodedPath.trimEnd('/') != current.encodedPath.trimEnd('/') || next == current
        ) {
            throw SyncException.Http(response.code, "Server redirected the sync folder elsewhere (HTTP ${response.code})")
        }
        return next
    }

    /**
     * The entry's name if it is a direct child of [base], null for the directory itself.
     * Paths are compared decoded, since servers re-encode hrefs differently.
     */
    private fun childName(requested: HttpUrl, base: HttpUrl, resource: DavResource): String? {
        val resolved = requested.resolve(resource.href) ?: return null
        val baseSegments = base.pathSegments.filter { it.isNotEmpty() }
        val segments = resolved.pathSegments.filter { it.isNotEmpty() }
        if (segments == baseSegments) return null
        if (segments.size == baseSegments.size + 1 && segments.subList(0, baseSegments.size) == baseSegments) {
            val name = segments.last()
            if (name == "." || name == ".." || name.contains('\\')) throw SyncException.Protocol("Invalid entry name")
            return name
        }
        // Self entry with a differently normalized parent path.
        if (resource.isCollection && segments.lastOrNull() == baseSegments.lastOrNull() && segments.size == baseSegments.size) {
            return null
        }
        SyncLog.w("Ignoring WebDAV entry outside the listed directory")
        return null
    }

    private fun url(path: String, directory: Boolean = false): HttpUrl {
        val clean = path.trim('/')
        if (clean.isEmpty()) return root
        return root.newBuilder().addPathSegments(if (directory) "$clean/" else clean).build()
    }

    private fun Response.failure(operation: String): SyncException = statusFailure(code, "$operation: HTTP $code")

    private fun statusFailure(code: Int, detail: String): SyncException = when (code) {
        401 -> SyncException.Unauthorized()
        403 -> SyncException.Forbidden(detail)
        404 -> SyncException.NotFound(detail)
        429, 503 -> SyncException.RateLimited(policy.cooldownRemaining())
        else -> SyncException.Http(code, detail)
    }

    private companion object {
        const val MAX_LISTING_BYTES = 16L * 1024 * 1024
        const val MAX_ENTRIES = 50_000
        const val MAX_PAGES = 1000
        const val JIANGUOYUN_PAGE = 750
    }
}
