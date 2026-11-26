package com.zenread.data.sync.engine

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * One synced row, identified by [kind] + [id]. Rows are merged last-writer-wins on
 * [updatedAt] (epoch millis on the writing device), see [SyncMerge].
 *
 * Kinds this version understands are listed in [RecordKind]; records of other kinds (from a
 * newer app version) are kept and merged generically but never applied locally.
 */
@Serializable
data class SyncRecord(
    val kind: String,
    val id: String,
    val updatedAt: Long,
    /** Id of the device that wrote this version; breaks ties between equal clocks. */
    val device: String = "",
    val deleted: Boolean = false,
    /** Row content; absent on tombstones. */
    val data: JsonObject? = null,
) {
    val key: String get() = "$kind/$id"
}

object RecordKind {
    /** id = SHA-256 of the book file; data = [BookData]. */
    const val BOOK = "book"

    /** id = SHA-256 of the book file; data = [ProgressData]. Never a tombstone. */
    const val PROGRESS = "progress"

    /** id = annotation UUID; data = [AnnotationData]. Deletion always wins. */
    const val ANNOTATION = "annotation"

    val KNOWN = setOf(BOOK, PROGRESS, ANNOTATION)
}

@Serializable
data class BookData(
    val title: String,
    val author: String,
    /** `BookFormat` name, e.g. `EPUB`; the remote file is `books/<id>.<primary extension>`. */
    val format: String,
    val createdAt: Long,
)

@Serializable
data class ProgressData(
    /** Point CFI of the reading position. */
    val location: String,
    /** 0–1. */
    val progress: Float,
)

@Serializable
data class AnnotationData(
    /** Book id (file SHA-256). */
    val book: String,
    /** `AnnotationType` name. */
    val type: String,
    val cfi: String,
    val text: String,
    val chapter: String,
    /** RRGGBB. */
    val color: String,
    val note: String? = null,
    val position: Float,
    val createdAt: Long,
)

/** JSON settings for the remote format: stable, compact, tolerant of unknown fields. */
val SyncJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal fun <T> T.toJsonObject(serializer: KSerializer<T>): JsonObject =
    SyncJson.encodeToJsonElement(serializer, this).jsonObject

internal fun <T> JsonObject.decodeOrNull(serializer: KSerializer<T>): T? =
    runCatching { SyncJson.decodeFromJsonElement(serializer, this) }.getOrNull()

/** Deterministic JSON text with object keys sorted, for tie-breaks and content comparison. */
internal fun JsonElement.canonical(): String = when (this) {
    is JsonObject -> entries.sortedBy { it.key }
        .joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + it.value.canonical() }
    is JsonArray -> joinToString(",", "[", "]") { it.canonical() }
    else -> toString()
}

/**
 * Re-encodes the data of known kinds through their typed form, so equal content compares
 * equal no matter how another device formatted it. Unknown kinds pass through unchanged.
 */
internal fun SyncRecord.normalized(): SyncRecord {
    val data = data ?: return this
    val typed = when (kind) {
        RecordKind.BOOK -> data.decodeOrNull(BookData.serializer())?.toJsonObject(BookData.serializer())
        RecordKind.PROGRESS -> data.decodeOrNull(ProgressData.serializer())?.toJsonObject(ProgressData.serializer())
        RecordKind.ANNOTATION -> data.decodeOrNull(AnnotationData.serializer())?.toJsonObject(AnnotationData.serializer())
        else -> data
    }
    return if (typed == null || typed == data) this else copy(data = typed)
}
