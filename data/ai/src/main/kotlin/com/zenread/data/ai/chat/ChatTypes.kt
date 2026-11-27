package com.zenread.data.ai.chat

import java.io.IOException
import kotlinx.serialization.Serializable

enum class ChatRole { SYSTEM, USER, ASSISTANT }

/** One message of a conversation. System messages may appear anywhere; providers hoist them. */
data class ChatMessage(val role: ChatRole, val content: String) {
    companion object {
        fun system(content: String) = ChatMessage(ChatRole.SYSTEM, content)
        fun user(content: String) = ChatMessage(ChatRole.USER, content)
        fun assistant(content: String) = ChatMessage(ChatRole.ASSISTANT, content)
    }
}

/** A model on one configured provider. */
@Serializable
data class ModelRef(val providerId: String, val model: String)

/**
 * Per-call options. Anything left null falls back to the model's saved parameters,
 * then to the provider's own default.
 */
data class ChatOptions(
    /** Null uses the default chat model chosen in settings. */
    val model: ModelRef? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

/** Streaming output. A successful stream always ends with exactly one [Done]. */
sealed interface ChatEvent {
    /** Next piece of the answer. */
    data class Delta(val text: String) : ChatEvent

    /** Next piece of visible reasoning (DeepSeek `reasoning_content`, Claude thinking, Gemini thoughts). */
    data class Reasoning(val text: String) : ChatEvent

    data class Done(val finishReason: FinishReason, val usage: Usage? = null) : ChatEvent
}

enum class FinishReason { STOP, LENGTH, FILTERED, OTHER }

data class Usage(val inputTokens: Int?, val outputTokens: Int?)

/**
 * Every failure surfaced by [com.zenread.data.ai.ChatClient]. [message] is safe to show:
 * it never contains API keys, headers or prompt text.
 */
class ChatException(
    val kind: Kind,
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Kind {
        /** No provider/model configured, or the configured one was removed. */
        NOT_CONFIGURED,
        AUTH,
        RATE_LIMIT,
        BAD_REQUEST,
        SERVER,
        NETWORK,
        /** The response could not be understood. */
        PROTOCOL,
        /** The provider cannot do this (e.g. embeddings on Anthropic). */
        UNSUPPORTED,
    }
}
