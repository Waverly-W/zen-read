package com.zenread.data.sync.transport.webdav

import com.zenread.data.sync.transport.SafeXml
import com.zenread.data.sync.transport.child
import com.zenread.data.sync.transport.children
import com.zenread.data.sync.transport.descendants
import com.zenread.data.sync.transport.text
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One `<d:response>` of a PROPFIND multistatus, with properties from its 200 propstats only. */
internal data class DavResource(
    /** The raw (percent-encoded) href. */
    val href: String,
    /** Response-level status code, if the server sent one (e.g. 404 for a missing resource). */
    val status: Int?,
    val isCollection: Boolean,
    val contentLength: Long?,
    val etag: String?,
    /** Epoch millis from `getlastmodified`. */
    val lastModified: Long?,
    /** False when no propstat had status 200. */
    val hasProperties: Boolean,
)

internal object WebDavXml {
    private const val DAV = "DAV:"

    /** Requests only what the sync engine needs; less work for large directories. */
    const val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:resourcetype/><d:getcontentlength/><d:getetag/><d:getlastmodified/>" +
            "</d:prop></d:propfind>"

    fun parseMultistatus(xml: ByteArray): List<DavResource> {
        val root = SafeXml.parse(xml).documentElement
        if (root.localName != "multistatus" && root.tagName.substringAfter(':') != "multistatus") {
            throw com.zenread.data.sync.transport.SyncException.Protocol("Not a WebDAV multistatus response")
        }
        return root.children("response", DAV).map { response ->
            val href = response.child("href", DAV)?.text()
                ?: throw com.zenread.data.sync.transport.SyncException.Protocol("WebDAV response without href")
            val props = response.children("propstat", DAV)
                .filter { statusCode(it.child("status", DAV)?.text()) == 200 }
                .mapNotNull { it.child("prop", DAV) }
            fun prop(name: String) = props.firstNotNullOfOrNull { it.child(name, DAV) }?.text()
            DavResource(
                href = href,
                status = response.child("status", DAV)?.text()?.let(::statusCode),
                isCollection = props.any { prop -> prop.child("resourcetype", DAV)?.descendants("collection", DAV)?.isNotEmpty() == true },
                contentLength = prop("getcontentlength")?.toLongOrNull(),
                etag = prop("getetag")?.takeIf { it.isNotEmpty() },
                lastModified = prop("getlastmodified")?.let(::parseHttpDate),
                hasProperties = props.isNotEmpty(),
            )
        }
    }

    /** `HTTP/1.1 200 OK` → 200. */
    fun statusCode(line: String?): Int? =
        line?.let { Regex("""\b(\d{3})\b""").find(it)?.groupValues?.get(1)?.toInt() }

    private fun parseHttpDate(value: String): Long? =
        runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()

    /** The `rel="next"` target of an RFC 8288 Link header (Jianguoyun paginates listings). */
    fun nextLink(header: String?): String? {
        if (header.isNullOrBlank()) return null
        return Regex("""<([^>]+)>\s*;[^,]*\brel\s*=\s*"?next"?""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
    }
}
