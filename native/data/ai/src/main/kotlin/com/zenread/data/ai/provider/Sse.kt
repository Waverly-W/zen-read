package com.zenread.data.ai.provider

import okio.BufferedSource

/** One server-sent event. [event] is null when the server sent no `event:` field. */
data class SseEvent(val event: String?, val data: String)

/**
 * Minimal `text/event-stream` reader (WHATWG §9.2): `data:` lines are joined with `\n`,
 * an empty line dispatches, `:` lines are comments, `id`/`retry` are ignored.
 * A trailing event without its blank line is still dispatched at end of stream, since
 * some OpenAI-compatible servers close the connection right after the last `data:`.
 *
 * Returns normally at end of stream or when [onEvent] returns false.
 */
fun readSse(source: BufferedSource, onEvent: (SseEvent) -> Boolean) {
    var eventName: String? = null
    val data = StringBuilder()
    var hasData = false

    fun dispatch(): Boolean {
        if (!hasData) {
            eventName = null
            return true
        }
        val keepGoing = onEvent(SseEvent(eventName, data.toString()))
        eventName = null
        data.setLength(0)
        hasData = false
        return keepGoing
    }

    while (true) {
        val line = source.readUtf8Line() ?: break
        if (line.isEmpty()) {
            if (!dispatch()) return
            continue
        }
        if (line.startsWith(':')) continue
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(' ')) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
    }
    dispatch()
}
