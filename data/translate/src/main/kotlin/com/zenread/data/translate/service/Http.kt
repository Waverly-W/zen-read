package com.zenread.data.translate.service

import com.zenread.data.translate.TranslateException
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Runs [request] and returns the parsed JSON body of a 2xx answer.
 * Errors become [TranslateException]s that name only the service and the HTTP status:
 * never the URL (it may carry a key), headers or bodies.
 */
internal suspend fun OkHttpClient.fetchJson(json: Json, service: String, request: Request): JsonElement {
    val body = fetchText(service, request)
    return try {
        json.parseToJsonElement(body)
    } catch (e: IllegalArgumentException) {
        throw TranslateException(TranslateException.Reason.BAD_RESPONSE, "$service returned invalid JSON", e)
    }
}

internal suspend fun OkHttpClient.fetchText(service: String, request: Request): String {
    val response = try {
        newCall(request).await()
    } catch (e: IOException) {
        throw TranslateException(TranslateException.Reason.UNAVAILABLE, "$service unreachable: ${e.javaClass.simpleName}", e)
    }
    return response.use {
        if (!it.isSuccessful) throw TranslateException.forStatus(service, it.code)
        try {
            it.body.string()
        } catch (e: IOException) {
            throw TranslateException(TranslateException.Reason.UNAVAILABLE, "$service connection dropped", e)
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}

internal fun badResponse(service: String): TranslateException =
    TranslateException(TranslateException.Reason.BAD_RESPONSE, "$service returned unexpected data")
