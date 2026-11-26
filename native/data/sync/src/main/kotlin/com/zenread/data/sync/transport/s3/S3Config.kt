package com.zenread.data.sync.transport.s3

import com.zenread.data.sync.transport.SyncException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * An S3-compatible bucket (AWS, Cloudflare R2, MinIO, Alibaba OSS, Tencent COS, Backblaze B2…),
 * ported from upstream `s3_config.dart`.
 *
 * [pathStyle] addresses `https://endpoint/bucket/key` (MinIO, R2, most self-hosted servers);
 * otherwise virtual-hosted `https://bucket.endpoint/key` (AWS, OSS, COS).
 */
data class S3Config(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val prefix: String,
    val accessKey: String,
    val secretKey: String,
    val pathStyle: Boolean,
    val sessionToken: String = "",
) {
    /** Validated endpoint (scheme + host + port, no path). */
    fun endpointUrl(): HttpUrl {
        val url = endpoint.trim().toHttpUrlOrNull()
            ?: throw SyncException.Config("Enter the endpoint as an https:// URL")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            (url.encodedPath != "/" && url.encodedPath.isNotEmpty())
        ) {
            throw SyncException.Config("The endpoint must be a plain URL without bucket, path or credentials")
        }
        return url
    }

    fun validate() {
        endpointUrl()
        if (!BUCKET.matches(bucket) || bucket.contains("..")) throw SyncException.Config("Invalid bucket name")
        if (!REGION.matches(region)) throw SyncException.Config("Invalid region")
        if (accessKey.isBlank() || secretKey.isBlank()) throw SyncException.Config("Access key and secret key are required")
        if (listOf(accessKey, secretKey, sessionToken).any { value -> value.any { it.code < 0x20 || it.code == 0x7f } }) {
            throw SyncException.Config("Invalid characters in the credentials")
        }
        val prefix = normalizedPrefix
        if (prefix.isNotEmpty() && prefix.split('/').any { it.isEmpty() || it == "." || it == ".." || it.contains('\\') }) {
            throw SyncException.Config("Invalid prefix")
        }
    }

    /** The prefix without leading or trailing slashes; empty for the bucket root. */
    val normalizedPrefix: String get() = prefix.trim().trim('/')

    /** Object key for a path relative to the sync root. */
    fun key(path: String): String = if (normalizedPrefix.isEmpty()) path else "$normalizedPrefix/$path"

    override fun toString() =
        "S3Config(endpoint=$endpoint, region=$region, bucket=$bucket, prefix=$prefix, pathStyle=$pathStyle, credentials=<redacted>)"

    private companion object {
        val BUCKET = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
        val REGION = Regex("^[a-zA-Z0-9-]+$")
    }
}
