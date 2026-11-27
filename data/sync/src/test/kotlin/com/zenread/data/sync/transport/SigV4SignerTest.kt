package com.zenread.data.sync.transport

import com.zenread.data.sync.transport.s3.S3Config
import com.zenread.data.sync.transport.s3.S3Transport
import com.zenread.data.sync.transport.s3.SigV4Signer
import java.time.Instant
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class SigV4SignerTest {
    private val aws = SigV4Signer.Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
    private val awsTime = Instant.parse("2013-05-24T00:00:00Z")

    // Examples from the AWS S3 API reference, "Signature Calculations for the Authorization
    // Header: Transferring Payload in a Single Chunk".

    @Test
    fun awsExample_getObject() {
        val url = "https://examplebucket.s3.amazonaws.com/test.txt".toHttpUrl()
        val signed = SigV4Signer.sign(
            "GET", url, mapOf("Range" to "bytes=0-9"), SigV4Signer.EMPTY_PAYLOAD_SHA256, aws, "us-east-1", awsTime,
        )
        assertEquals(
            """
            GET
            /test.txt

            host:examplebucket.s3.amazonaws.com
            range:bytes=0-9
            x-amz-content-sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            x-amz-date:20130524T000000Z

            host;range;x-amz-content-sha256;x-amz-date
            e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            """.trimIndent(),
            SigV4Signer.canonicalRequest(
                "GET", url,
                mapOf(
                    "host" to "examplebucket.s3.amazonaws.com",
                    "range" to "bytes=0-9",
                    "x-amz-content-sha256" to SigV4Signer.EMPTY_PAYLOAD_SHA256,
                    "x-amz-date" to "20130524T000000Z",
                ),
                SigV4Signer.EMPTY_PAYLOAD_SHA256,
            ),
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            signed["authorization"],
        )
        assertEquals("20130524T000000Z", signed["x-amz-date"])
    }

    @Test
    fun awsExample_putObject() {
        val signed = SigV4Signer.sign(
            "PUT",
            "https://examplebucket.s3.amazonaws.com/test\$file.text".toHttpUrl(),
            mapOf("Date" to "Fri, 24 May 2013 00:00:00 GMT", "x-amz-storage-class" to "REDUCED_REDUNDANCY"),
            sha256Hex("Welcome to Amazon S3.".toByteArray()),
            aws, "us-east-1", awsTime,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class, " +
                "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
            signed["authorization"],
        )
    }

    @Test
    fun awsExample_getBucketLifecycle() {
        val signed = SigV4Signer.sign(
            "GET", "https://examplebucket.s3.amazonaws.com/?lifecycle".toHttpUrl(), emptyMap(),
            SigV4Signer.EMPTY_PAYLOAD_SHA256, aws, "us-east-1", awsTime,
        )
        assertEquals(
            "fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543",
            signed["authorization"]!!.substringAfter("Signature="),
        )
    }

    @Test
    fun awsExample_listObjects() {
        val signed = SigV4Signer.sign(
            "GET", "https://examplebucket.s3.amazonaws.com/?max-keys=2&prefix=J".toHttpUrl(), emptyMap(),
            SigV4Signer.EMPTY_PAYLOAD_SHA256, aws, "us-east-1", awsTime,
        )
        assertEquals(
            "34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
            signed["authorization"]!!.substringAfter("Signature="),
        )
    }

    /** Upstream `s3_client_test.dart`, generated with AWS `@smithy/signature-v4`. */
    @Test
    fun upstreamVectors_unicodeKeysQueryTokensAndSessionToken() {
        val credentials = SigV4Signer.Credentials("AKIDEXAMPLE", "test-secret", "test-token")
        val vectors = mapOf(
            "test.txt" to "60bc77b8755eff3b4eba934e73c080981dab0e63a99ba6359df961df04e6cd4c",
            "modu/中文 +%?.txt" to "92c3d61de9a91abaf8bcc4ff32d8eabc2a651bbd6be444da435633e7705b8df6",
        )
        for ((key, signature) in vectors) {
            val url = HttpUrl.Builder().scheme("https").host("examplebucket.s3.amazonaws.com").apply {
                key.split('/').forEach { addEncodedPathSegment(SigV4Signer.uriEncode(it)) }
                encodedQuery(
                    listOf("prefix" to "中文/+%", "continuation-token" to "a+b/=")
                        .map { SigV4Signer.uriEncode(it.first) to SigV4Signer.uriEncode(it.second) }
                        .sortedBy { it.first }
                        .joinToString("&") { "${it.first}=${it.second}" },
                )
            }.build()
            val signed = SigV4Signer.sign(
                "GET", url, emptyMap(), SigV4Signer.EMPTY_PAYLOAD_SHA256, credentials, "us-east-1",
                Instant.parse("2026-10-07T01:02:03Z"),
            )
            assertEquals(key, signature, signed["authorization"]!!.substringAfter("Signature="))
            assertEquals("test-token", signed["x-amz-security-token"])
        }
    }

    @Test
    fun uriEncode_keepsOnlyUnreservedCharacters() {
        assertEquals("A-z_0.9~", SigV4Signer.uriEncode("A-z_0.9~"))
        assertEquals("%20%2B%25%2F%3F%21%27%28%29%2A%E4%B8%AD", SigV4Signer.uriEncode(" +%/?!'()*中"))
    }

    @Test
    fun hostHeader_includesOnlyNonDefaultPorts() {
        assertEquals("s3.example.com", SigV4Signer.hostHeader("https://s3.example.com/".toHttpUrl()))
        assertEquals("minio.local:9000", SigV4Signer.hostHeader("http://minio.local:9000/".toHttpUrl()))
    }

    @Test
    fun objectUrls_virtualHostAndPathStyle() {
        val base = S3Config(
            endpoint = "https://s3.us-east-1.amazonaws.com", region = "us-east-1", bucket = "my-bucket",
            prefix = "/中文 dir/", accessKey = "a", secretKey = "s", pathStyle = false,
        )
        val virtual = S3Transport(base, OkHttpClient()).objectUrl("ZenRead/v1/books/x y.epub")
        assertEquals("my-bucket.s3.us-east-1.amazonaws.com", virtual.host)
        assertEquals("/%E4%B8%AD%E6%96%87%20dir/ZenRead/v1/books/x%20y.epub", virtual.encodedPath)

        val path = S3Transport(base.copy(endpoint = "http://10.0.0.2:9000", pathStyle = true), OkHttpClient())
            .objectUrl("ZenRead/v1/manifest.json")
        assertEquals("10.0.0.2", path.host)
        assertEquals(9000, path.port)
        assertEquals("/my-bucket/%E4%B8%AD%E6%96%87%20dir/ZenRead/v1/manifest.json", path.encodedPath)
    }

    @Test(expected = SyncException.Config::class)
    fun config_rejectsEndpointWithBucketPath() {
        S3Config("https://s3.amazonaws.com/bucket", "us-east-1", "bucket", "", "a", "s", true).validate()
    }

    @Test(expected = SyncException.Config::class)
    fun config_rejectsTraversalInPrefix() {
        S3Config("https://s3.amazonaws.com", "us-east-1", "bucket", "a/../b", "a", "s", true).validate()
    }
}
