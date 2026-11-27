package com.zenread.data.translate

import com.zenread.data.translate.cache.TranslationCacheDao
import com.zenread.data.translate.cache.TranslationCacheEntity
import com.zenread.data.translate.cache.TranslationCacheKey
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Result of translating a selection. */
data class SelectionTranslation(
    val serviceId: String,
    val text: String,
    /** Detected (or guessed from the script) source language; null when unknown. */
    val from: Language?,
    val to: Language,
)

/**
 * Entry point for the reader and settings. Every call here sends text to a third-party
 * service, so callers must only invoke it on an explicit user action.
 */
@Singleton
class TranslateRepository @Inject constructor(
    private val registry: TranslatorRegistry,
    private val settingsRepository: TranslateSettingsRepository,
    private val credentials: TranslateCredentials,
    private val cache: TranslationCacheDao,
) {
    val translators: List<Translator> get() = registry.all

    /**
     * Translates a selection into the target language; text already in the target language
     * goes to the fallback language instead (see [SelectionTargets]).
     *
     * @param serviceId a translator to use instead of the default one.
     * @throws TranslateException
     */
    suspend fun translateSelection(text: String, serviceId: String? = null): SelectionTranslation {
        val settings = settingsRepository.current()
        val translator = registry.resolve(serviceId ?: settings.serviceId)
        val creds = credentialsFor(translator)
        val system = Language.fromLocale(Locale.getDefault())
        val target = settings.resolvedTarget()
        val guess = ScriptDetector.guess(text)
        var to = SelectionTargets.initial(target, system, guess)
        var result = translator.translate(listOf(text), null, to, creds).single()
        val detected = result.detected ?: guess
        SelectionTargets.retarget(target, system, to, detected)?.let { retry ->
            to = retry
            result = translator.translate(listOf(text), null, to, creds).single()
        }
        return SelectionTranslation(translator.descriptor.id, result.text, result.detected ?: guess, to)
    }

    /**
     * Starts paragraph translation for the reader with the default service and target.
     * Close the session when translation is turned off or the reader closes.
     *
     * @throws TranslateException with [TranslateException.Reason.MISSING_CREDENTIALS] when the
     *   default service needs a key that is not set.
     */
    suspend fun startPageSession(): PageTranslationSession {
        val settings = settingsRepository.current()
        val translator = registry.resolve(settings.serviceId)
        val creds = credentialsFor(translator)
        cache.trim(CACHE_ROWS)
        return PageTranslationSession(translator, creds, settings.resolvedTarget(), cache)
    }

    suspend fun cachedCount(): Int = cache.count()

    suspend fun clearCache() = cache.clear()

    private suspend fun credentialsFor(translator: Translator): Credentials {
        val creds = credentials.load(translator.descriptor)
        if (!creds.isComplete(translator.descriptor.credentialFields)) {
            throw TranslateException(TranslateException.Reason.MISSING_CREDENTIALS, "${translator.descriptor.id} needs a key")
        }
        return creds
    }

    private companion object {
        const val CACHE_ROWS = 20_000
    }
}

/** Target choice for selection translation, kept pure for tests. */
object SelectionTargets {
    /** Where text already in [target] goes: English, or the system language when the target is English. */
    fun fallback(target: Language, system: Language?): Language? = when {
        !target.sameFamily(Language.ENGLISH) -> Language.ENGLISH
        system != null && !system.sameFamily(Language.ENGLISH) -> system
        else -> null
    }

    /** The first request's target, using the script guess when there is one. */
    fun initial(target: Language, system: Language?, guess: Language?): Language =
        if (target.sameFamily(guess)) fallback(target, system) ?: target else target

    /** A second target when the service reports the text was already in [target], else null. */
    fun retarget(target: Language, system: Language?, used: Language, detected: Language?): Language? {
        if (used != target || !target.sameFamily(detected)) return null
        return fallback(target, system)
    }
}

/**
 * One run of paragraph translation: fixed service, credentials and target language.
 * Paragraphs come from foliate-js one by one; they are answered from the cache or batched.
 */
class PageTranslationSession internal constructor(
    val translator: Translator,
    private val credentials: Credentials,
    val to: Language,
    private val cache: TranslationCacheDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serviceId = translator.descriptor.id

    private val batcher = TranslationBatcher(
        scope = scope,
        maxItems = translator.descriptor.maxBatchItems,
        maxChars = translator.descriptor.maxBatchChars,
    ) { texts ->
        val results = translator.translate(texts, null, to, credentials)
        val now = System.currentTimeMillis()
        cache.put(texts.zip(results) { text, result -> TranslationCacheEntity(key(text), result.text, now) })
        results.map { it.text }
    }

    /**
     * The translation of one paragraph, or null when there is nothing to show (no letters, or
     * the service returned the text unchanged because it already is in the target language).
     *
     * @throws TranslateException when the service fails.
     * @throws kotlinx.coroutines.CancellationException after [close].
     */
    suspend fun translate(text: String): String? {
        val source = text.trim()
        if (!TextChunks.isTranslatable(source)) return null
        val translated = cache.get(key(source)) ?: batcher.translate(source)
        return translated.takeUnless { it.trim() == source || it.isBlank() }
    }

    val isClosed: Boolean get() = scope.coroutineContext[Job]?.isActive != true

    fun close() = scope.cancel()

    private fun key(text: String) = TranslationCacheKey.of(serviceId, null, to, text)
}
