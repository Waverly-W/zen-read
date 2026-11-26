package com.zenread.data.sync.transport

import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.buffer
import okio.sink

/** Executes the call without blocking a thread; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(if (e is SyncException) e else SyncException.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}

/** Reads the body, failing instead of buffering more than [maxBytes]. */
internal fun Response.bytesAtMost(maxBytes: Long): ByteArray {
    val declared = body.contentLength()
    if (declared > maxBytes) throw SyncException.Protocol("Response too large")
    val source = body.source()
    if (!source.request(maxBytes + 1)) return source.buffer.readByteArray()
    throw SyncException.Protocol("Response too large")
}

/** Streams the body into [target] through a temporary file next to it. */
internal fun Response.saveTo(target: File) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.part")
    try {
        temp.sink().buffer().use { sink -> sink.writeAll(body.source()) }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
        }
    } catch (e: IOException) {
        throw if (e is SyncException) e else SyncException.Network(e)
    } finally {
        temp.delete()
    }
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    forEachIndexed { i, b ->
        val v = b.toInt() and 0xff
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(chars)
}

private val HEX = "0123456789abcdef".toCharArray()

/** Logging that also works in plain JVM unit tests. Never pass URLs with credentials or headers. */
internal object SyncLog {
    private const val TAG = "ZenReadSync"

    fun i(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    fun w(message: String, error: Throwable? = null) {
        // Only the type and our own sanitized message: library exceptions may carry URLs.
        val suffix = error?.let { " (${it.javaClass.simpleName}${(it as? SyncException)?.message?.let { m -> ": $m" }.orEmpty()})" }
        runCatching { Log.w(TAG, message + suffix.orEmpty()) }
    }
}
