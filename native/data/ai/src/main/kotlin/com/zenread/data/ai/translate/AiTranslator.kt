package com.zenread.data.ai.translate

import com.zenread.data.ai.ChatClient
import com.zenread.data.ai.R
import com.zenread.data.ai.chat.ChatException
import com.zenread.data.ai.chat.ChatMessage
import com.zenread.data.ai.chat.ChatOptions
import com.zenread.data.translate.Credentials
import com.zenread.data.translate.Language
import com.zenread.data.translate.TranslateException
import com.zenread.data.translate.Translation
import com.zenread.data.translate.Translator
import com.zenread.data.translate.TranslatorDescriptor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Translation through the default chat model configured in Settings › AI (upstream token
 * `ai`). Several paragraphs go in one prompt as a JSON array; if the model's answer does
 * not come back as an array of the same length, each paragraph is translated on its own.
 */
@Singleton
class AiTranslator @Inject constructor(
    private val chat: ChatClient,
) : Translator {
    override val descriptor = TranslatorDescriptor(
        id = "ai",
        label = R.string.ai_translator_label,
        order = 90,
        maxBatchItems = 8,
        maxBatchChars = 6_000,
    )

    override suspend fun translate(
        texts: List<String>,
        from: Language?,
        to: Language,
        credentials: Credentials,
    ): List<Translation> = try {
        if (texts.size == 1) {
            listOf(Translation(AiTranslatePrompt.cleanSingle(ask(AiTranslatePrompt.single(texts[0], from, to)))))
        } else {
            AiTranslatePrompt.parseBatch(ask(AiTranslatePrompt.batch(texts, from, to)), texts.size)
                ?.map { Translation(it) }
                ?: texts.map { Translation(AiTranslatePrompt.cleanSingle(ask(AiTranslatePrompt.single(it, from, to)))) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ChatException) {
        throw TranslateException(e.kind.toReason(), "AI ${e.kind}", e)
    }

    private suspend fun ask(messages: List<ChatMessage>): String =
        chat.complete(messages, ChatOptions(temperature = 0.2))

    private fun ChatException.Kind.toReason() = when (this) {
        ChatException.Kind.NOT_CONFIGURED -> TranslateException.Reason.MISSING_CREDENTIALS
        ChatException.Kind.AUTH -> TranslateException.Reason.UNAUTHORIZED
        ChatException.Kind.RATE_LIMIT -> TranslateException.Reason.QUOTA
        ChatException.Kind.BAD_REQUEST -> TranslateException.Reason.REJECTED
        ChatException.Kind.SERVER, ChatException.Kind.NETWORK -> TranslateException.Reason.UNAVAILABLE
        else -> TranslateException.Reason.BAD_RESPONSE
    }
}

/** Prompt building and answer parsing, kept pure for tests. */
internal object AiTranslatePrompt {
    private fun target(to: Language) = "${to.nativeName} (${to.code})"
    private fun source(from: Language?) = from?.let { "from ${it.nativeName} (${it.code}) " }.orEmpty()

    fun single(text: String, from: Language?, to: Language) = listOf(
        ChatMessage.system(
            "You are a literary translator. Translate the user's text ${source(from)}into ${target(to)}. " +
                "Keep the meaning, tone and paragraph breaks. Reply with the translation only: " +
                "no quotes, notes or explanations.",
        ),
        ChatMessage.user(text),
    )

    fun batch(texts: List<String>, from: Language?, to: Language) = listOf(
        ChatMessage.system(
            "You are a literary translator. The user sends a JSON array of ${texts.size} passages from a book. " +
                "Translate each passage ${source(from)}into ${target(to)}, keeping meaning and tone. " +
                "Reply with only a JSON array of exactly ${texts.size} strings, in the same order, " +
                "and nothing else.",
        ),
        ChatMessage.user(JsonArray(texts.map(::JsonPrimitive)).toString()),
    )

    /** The translated strings, or null when the answer is not a same-length array of strings. */
    fun parseBatch(answer: String, expected: Int): List<String>? {
        val body = answer.trim().removeFence()
        val start = body.indexOf('[')
        val end = body.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val array = runCatching { Json.parseToJsonElement(body.substring(start, end + 1)) as? JsonArray }.getOrNull()
            ?: return null
        val strings = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
        return strings.takeIf { it.size == expected }
    }

    /** Drops a Markdown code fence or wrapping quotes some models add around a single answer. */
    fun cleanSingle(answer: String): String {
        val text = answer.trim().removeFence().trim()
        val quotes = listOf('"' to '"', '“' to '”', '「' to '」')
        return quotes.firstOrNull { (open, close) -> text.length >= 2 && text.first() == open && text.last() == close }
            ?.let { text.substring(1, text.length - 1).trim() }
            ?: text
    }

    private fun String.removeFence(): String {
        if (!startsWith("```")) return this
        return substringAfter('\n', "").substringBeforeLast("```")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AiTranslatorModule {
    @Binds
    @IntoSet
    abstract fun aiTranslator(impl: AiTranslator): Translator
}
