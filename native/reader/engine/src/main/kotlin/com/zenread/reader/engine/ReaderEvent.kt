package com.zenread.reader.engine

import com.zenread.core.model.TocItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/** Events foliate-js sends through `callHandler`. Unhandled names map to null. */
sealed interface ReaderEvent {
    data object LoadEnd : ReaderEvent
    data class LoadError(val name: String) : ReaderEvent

    data class Relocated(
        val cfi: String,
        val fraction: Float,
        val chapterTitle: String?,
        val chapterHref: String?,
        val chapterPage: Int,
        val chapterPages: Int,
        /** 1-based section (spine item) index; for PDF and CBZ, a section is one page. */
        val section: Int,
        val sections: Int,
        /** True for real moves (page turns, jumps, user scrolling); false for re-layouts. */
        val userAction: Boolean,
    ) : ReaderEvent

    data class TocLoaded(val items: List<TocItem>) : ReaderEvent

    /** Tap in page coordinates normalized to 0..1. */
    data class Tap(val x: Float, val y: Float) : ReaderEvent

    companion object {
        fun parse(name: String, args: JsonArray): ReaderEvent? {
            val first = args.firstOrNull()
            return when (name) {
                "onLoadEnd" -> LoadEnd
                "onReaderLoadError" -> LoadError((first as? JsonObject)?.string("name") ?: "Unknown")
                "onRelocated" -> (first as? JsonObject)?.toRelocated()
                "onSetToc" -> TocLoaded((first as? JsonArray)?.mapNotNull { (it as? JsonObject)?.toTocItem() }.orEmpty())
                "onClick" -> (first as? JsonObject)?.let { Tap(it.float("x") ?: 0.5f, it.float("y") ?: 0.5f) }
                else -> null
            }
        }

        private fun JsonObject.toRelocated(): Relocated? {
            val cfi = string("cfi") ?: return null
            return Relocated(
                cfi = cfi,
                fraction = (float("percentage") ?: 0f).coerceIn(0f, 1f),
                chapterTitle = string("chapterTitle"),
                chapterHref = string("chapterHref"),
                chapterPage = int("chapterCurrentPage") ?: 0,
                chapterPages = int("chapterTotalPages") ?: 0,
                section = int("currentChapter") ?: 0,
                sections = int("totalChapters") ?: 0,
                userAction = (this["readingAction"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }

        private fun JsonObject.toTocItem(): TocItem? {
            val href = string("href") ?: return null
            return TocItem(
                label = string("label")?.trim().orEmpty(),
                href = href,
                level = int("level") ?: 1,
                startPercentage = float("startPercentage") ?: 0f,
                children = (this["subitems"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.toTocItem() }.orEmpty(),
            )
        }
    }
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/**
 * foliate-js metadata values may be a plain string, a language map
 * (`{"en": "..."}`), a contributor (`{"name": ...}`) or a list of those.
 */
internal fun JsonElement?.displayText(): String? = when (this) {
    is JsonPrimitive -> content.takeIf { isString && content.isNotBlank() }?.trim()
    is JsonObject -> (this["name"] ?: values.firstOrNull()).displayText()
    is JsonArray -> mapNotNull { it.displayText() }.distinct().joinToString(", ").ifBlank { null }
    else -> null
}
