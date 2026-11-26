package com.zenread.reader.engine

import com.zenread.core.model.AnnotationType
import com.zenread.core.model.TocItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

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
        /** CFI of the bookmark on this page, or null when the page has none. */
        val bookmarkCfi: String? = null,
    ) : ReaderEvent

    data class TocLoaded(val items: List<TocItem>) : ReaderEvent

    /** Tap in page coordinates normalized to 0..1. */
    data class Tap(val x: Float, val y: Float) : ReaderEvent

    /** The user finished selecting text. [annotationIds] are marks the selection overlaps. */
    data class SelectionEnd(
        val cfi: String,
        val text: String,
        val rect: PageRect,
        val annotationIds: List<Long>,
    ) : ReaderEvent

    data object SelectionCleared : ReaderEvent

    /** The user tapped an existing highlight or underline. */
    data class AnnotationTap(val id: Long, val cfi: String, val rect: PageRect) : ReaderEvent

    /**
     * foliate-js asks the host to add or remove a bookmark (after `addBookmarkHere()`,
     * the pull-down gesture, or removing the page's bookmark).
     */
    data class BookmarkRequest(
        val remove: Boolean,
        val cfi: String,
        val excerpt: String,
        val fraction: Float,
    ) : ReaderEvent

    /** The page is ready for annotations; reply with `replaceAnnotations`. */
    data object AnnotationsWanted : ReaderEvent

    /** Bookmark on the current page after marks were (re)loaded; null when there is none. */
    data class BookmarkState(val cfi: String?) : ReaderEvent

    /** Read aloud (phase 3): the speech cursor entered the chapter [chapterTitle]. */
    data class TtsChapter(val chapterTitle: String) : ReaderEvent

    /**
     * Read aloud (phase 3): position of the sentence being spoken. Sent even while the page is
     * hidden, so progress can be saved when the screen is off.
     */
    data class TtsProgress(val cfi: String, val fraction: Float) : ReaderEvent

    companion object {
        fun parse(name: String, args: JsonArray): ReaderEvent? {
            val first = args.firstOrNull()
            return when (name) {
                "onLoadEnd" -> LoadEnd
                "onReaderLoadError" -> LoadError((first as? JsonObject)?.string("name") ?: "Unknown")
                "onRelocated" -> (first as? JsonObject)?.toRelocated()
                "onSetToc" -> TocLoaded((first as? JsonArray)?.mapNotNull { (it as? JsonObject)?.toTocItem() }.orEmpty())
                "onClick" -> (first as? JsonObject)?.let { Tap(it.float("x") ?: 0.5f, it.float("y") ?: 0.5f) }
                "onSelectionEnd" -> (first as? JsonObject)?.toSelection()
                "onSelectionCleared" -> SelectionCleared
                "onAnnotationClick" -> (first as? JsonObject)?.toAnnotationTap()
                "handleBookmark" -> (first as? JsonObject)?.toBookmarkRequest()
                "renderAnnotations" -> AnnotationsWanted
                ReaderController.BOOKMARK_STATE -> BookmarkState((first as? JsonObject)?.string("cfi"))
                "onTtsChapter" -> (first as? JsonObject)?.string("chapterTitle")?.let(::TtsChapter)
                "onTtsProgress" -> (first as? JsonObject)?.let { progress ->
                    val cfi = progress.string("cfi") ?: return@let null
                    val fraction = progress.float("percentage")?.takeIf { it.isFinite() } ?: return@let null
                    TtsProgress(cfi, fraction.coerceIn(0f, 1f))
                }
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
                bookmarkCfi = (this["bookmark"] as? JsonObject)
                    ?.takeIf { (it["exists"] as? JsonPrimitive)?.booleanOrNull == true }
                    ?.string("cfi"),
            )
        }

        private fun JsonObject.toSelection(): SelectionEnd? {
            // Footnote popups are separate documents; their CFIs do not address the book.
            if ((this["footnote"] as? JsonPrimitive)?.booleanOrNull == true) return null
            val cfi = string("cfi") ?: return null
            val text = string("text")?.takeIf { it.isNotBlank() } ?: return null
            return SelectionEnd(
                cfi = cfi,
                text = text,
                rect = (this["pos"] as? JsonObject)?.toRect() ?: PageRect.CENTER,
                annotationIds = (this["annotationIds"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty(),
            )
        }

        private fun JsonObject.toAnnotationTap(): AnnotationTap? {
            val annotation = this["annotation"] as? JsonObject ?: return null
            val id = (annotation["id"] as? JsonPrimitive)?.longOrNull ?: return null
            if (AnnotationType.fromJs(annotation.string("type")) == AnnotationType.BOOKMARK) return null
            return AnnotationTap(
                id = id,
                cfi = annotation.string("value") ?: return null,
                rect = (this["pos"] as? JsonObject)?.toRect() ?: PageRect.CENTER,
            )
        }

        private fun JsonObject.toBookmarkRequest(): BookmarkRequest? {
            val detail = this["detail"] as? JsonObject ?: return null
            return BookmarkRequest(
                remove = (this["remove"] as? JsonPrimitive)?.booleanOrNull ?: false,
                cfi = detail.string("cfi") ?: return null,
                excerpt = detail.string("content").orEmpty(),
                fraction = (detail.float("percentage") ?: 0f).coerceIn(0f, 1f),
            )
        }

        private fun JsonObject.toRect() = PageRect(
            left = float("left") ?: 0f,
            top = float("top") ?: 0f,
            right = float("right") ?: 0f,
            bottom = float("bottom") ?: 0f,
        )

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
