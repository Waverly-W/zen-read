package com.zenread.data.translate

import com.zenread.data.translate.service.GoogleFreeTranslator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every [Translator] contributed through Hilt multibinding, ordered for pickers.
 *
 * AI (LLM) translation is not built here: `data:ai` contributes `AiTranslator` (id `ai`) by
 *  binding it into the set:
 *  ```
 *  @Binds @IntoSet abstract fun aiTranslator(impl: AiTranslator): Translator
 *  ```
 *  with a descriptor id of `"ai"` (the upstream token), `maxBatchItems > 1` if it can
 *  translate several paragraphs per prompt, and no `credentialFields` (the LLM provider owns
 *  its keys). Nothing in this module, the settings page or the reader needs to change.
 */
@Singleton
class TranslatorRegistry @Inject constructor(
    translators: Set<@JvmSuppressWildcards Translator>,
) {
    val all: List<Translator> = translators.sortedWith(compareBy({ it.descriptor.order }, { it.descriptor.id }))

    operator fun get(id: String): Translator? = all.firstOrNull { it.descriptor.id == id }

    /** [id] when registered, otherwise the default free service. */
    fun resolve(id: String?): Translator = id?.let(::get) ?: get(DEFAULT_ID) ?: all.first()

    companion object {
        const val DEFAULT_ID = GoogleFreeTranslator.ID
    }
}
