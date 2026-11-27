package com.zenread.data.translate.service

import com.zenread.data.translate.Credentials
import com.zenread.data.translate.Language
import com.zenread.data.translate.RateLimiter
import com.zenread.data.translate.TextChunks
import com.zenread.data.translate.Translation
import com.zenread.data.translate.Translator

/**
 * Shared request shaping: blank texts are answered locally, over-long texts are split at
 * sentence ends, the rest is grouped into batches within the descriptor's limits, and request
 * starts are rate-limited per translator instance (translators are singletons).
 */
abstract class BatchTranslator : Translator {
    private val limiter by lazy { RateLimiter(descriptor.minRequestIntervalMillis) }

    final override suspend fun translate(
        texts: List<String>,
        from: Language?,
        to: Language,
        credentials: Credentials,
    ): List<Translation> {
        val d = descriptor
        // (index of the source text, piece of it)
        val pieces = texts.flatMapIndexed { index, text ->
            if (text.isBlank()) emptyList() else TextChunks.split(text, d.maxBatchChars).map { index to it }
        }
        val results = ArrayList<Translation>(pieces.size)
        for (batch in TextChunks.batch(pieces, d.maxBatchItems, d.maxBatchChars) { it.second.length }) {
            limiter.acquire()
            val translated = translateBatch(batch.map { it.second }, from, to, credentials)
            if (translated.size != batch.size) throw badResponse(d.id)
            results += translated
        }
        val parts = List(texts.size) { mutableListOf<Translation>() }
        pieces.forEachIndexed { k, (index, _) -> parts[index] += results[k] }
        return parts.mapIndexed { index, list ->
            if (list.isEmpty()) {
                Translation(texts[index])
            } else {
                Translation(list.joinToString("") { it.text }, list.firstNotNullOfOrNull { it.detected })
            }
        }
    }

    /** One request: [texts] fits the descriptor's item and character limits. */
    protected abstract suspend fun translateBatch(
        texts: List<String>,
        from: Language?,
        to: Language,
        credentials: Credentials,
    ): List<Translation>
}
