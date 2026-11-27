package com.zenread.data.sync.engine

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private fun progress(at: Long, location: String, device: String = "a") = SyncRecord(
        RecordKind.PROGRESS, HASH, at, device,
        data = ProgressData(location, 0.5f).toJsonObject(ProgressData.serializer()),
    )

    private fun note(at: Long, text: String?, device: String = "a", deleted: Boolean = false) = SyncRecord(
        RecordKind.ANNOTATION, "uuid-1", at, device, deleted,
        data = if (deleted) null else AnnotationData(HASH, "HIGHLIGHT", "cfi", "t", "", "F6C945", text, 0.1f, 1)
            .toJsonObject(AnnotationData.serializer()),
    )

    private fun book(at: Long, title: String = "T", device: String = "a", deleted: Boolean = false) = SyncRecord(
        RecordKind.BOOK, HASH, at, device, deleted,
        data = if (deleted) null else BookData(title, "", "EPUB", 1).toJsonObject(BookData.serializer()),
    )

    @Test
    fun newerClockWins() {
        assertTrue(SyncMerge.compare(progress(2, "x"), progress(1, "y")) > 0)
        assertTrue(SyncMerge.compare(progress(1, "x"), progress(2, "y")) < 0)
    }

    @Test
    fun sameClockSameContentIsEquivalentAcrossDevices() {
        assertEquals(0, SyncMerge.compare(progress(5, "x", "a"), progress(5, "x", "b")))
    }

    @Test
    fun sameClockDifferentContent_isDecidedTheSameWayEverywhere() {
        val a = progress(5, "x", "device-a")
        val b = progress(5, "y", "device-b")
        assertTrue(SyncMerge.compare(b, a) > 0)
        assertTrue(SyncMerge.compare(a, b) < 0)
        assertEquals(SyncMerge.merge(listOf(a, b)), SyncMerge.merge(listOf(b, a)))
    }

    @Test
    fun annotationTombstoneBeatsNewerEdit() {
        val deleted = note(1, null, deleted = true)
        val edited = note(99, "offline edit")
        assertTrue(SyncMerge.compare(deleted, edited) > 0)
        assertEquals(deleted, SyncMerge.merge(listOf(edited, deleted)).values.single())
    }

    @Test
    fun bookTombstoneIsLastWriterWins() {
        // Re-importing a deleted book (newer clock) brings it back.
        assertTrue(SyncMerge.compare(book(10), book(5, deleted = true)) > 0)
        assertTrue(SyncMerge.compare(book(5, deleted = true), book(4)) > 0)
        // Same clock: deletion wins.
        assertTrue(SyncMerge.compare(book(5, deleted = true), book(5)) > 0)
    }

    @Test
    fun mergeIsCommutativeAndIdempotent() {
        val records = listOf(book(1), book(3, "B", "z"), progress(2, "p"), note(4, "n"), note(1, null, deleted = true), book(3, "A", "y"))
        val forward = SyncMerge.merge(records)
        val backward = SyncMerge.merge(records.reversed())
        assertEquals(forward, backward)
        assertEquals(forward, SyncMerge.merge(forward.values + records))
        assertEquals("B", forward["book/$HASH"]!!.data!!["title"]!!.let { (it as JsonPrimitive).content })
    }

    @Test
    fun plan_appliesRemoteWinnersAndPublishesLocalWinners() {
        val local = listOf(book(5), progress(10, "local"), note(3, "n")).associateBy { it.key }
        val remote = listOf(book(5, device = "b"), progress(20, "remote", "b")).associateBy { it.key }
        val plan = SyncMerge.plan(local, remote)
        assertEquals(listOf(progress(20, "remote", "b")), plan.toApply)
        assertEquals(listOf(note(3, "n")), plan.toPublish)
    }

    @Test
    fun plan_ignoresRemoteTombstonesWithoutLocalRow() {
        val plan = SyncMerge.plan(emptyMap(), mapOf("book/$HASH" to book(5, deleted = true)))
        assertTrue(plan.toApply.isEmpty())
        assertTrue(plan.toPublish.isEmpty())
    }

    @Test
    fun normalized_makesEquivalentJsonCompareEqual() {
        val typed = progress(5, "x")
        val reordered = typed.copy(data = buildJsonObject { put("progress", 0.5f); put("location", "x"); put("extra", 1) })
        assertEquals(0, SyncMerge.compare(typed, reordered.normalized()))
    }

    @Test
    fun segmentSerialization_roundTripsAndKeepsUnknownKinds() {
        val unknown = SyncRecord("collection", "c1", 7, "z", data = buildJsonObject { put("name", "Fav") })
        val segment = Segment(device = "a", createdAt = 9, records = listOf(book(1), note(2, "n"), note(3, null, deleted = true), unknown))
        val text = SyncJson.encodeToString(Segment.serializer(), segment)
        assertEquals(segment, SyncJson.decodeFromString(Segment.serializer(), text))
        assertTrue("tombstones carry no data", !text.contains("\"deleted\":true,\"data\""))
        assertTrue(text.startsWith("{\"format\":\"zenread-sync-segment\",\"version\":1,"))
        // Records of a kind this version does not know survive a merge (and so a compaction).
        assertEquals(unknown, SyncMerge.merge(listOf(unknown.normalized())).values.single())
    }

    @Test
    fun manifestSerialization() {
        val manifest = RemoteManifest(RemoteLayout.FORMAT, RemoteLayout.VERSION, 123)
        val text = SyncJson.encodeToString(RemoteManifest.serializer(), manifest)
        assertEquals("""{"format":"zenread-sync","version":1,"createdAt":123}""", text)
        assertEquals(manifest, SyncJson.decodeFromString(RemoteManifest.serializer(), """{"version":1,"format":"zenread-sync","createdAt":123,"future":true}"""))
    }

    @Test
    fun layout_validatesIds() {
        assertEquals("ZenRead/v1/books/$HASH.epub", RemoteLayout.book(HASH, "epub"))
        assertEquals(HASH, RemoteLayout.segmentDigest("$HASH.json"))
        assertEquals(null, RemoteLayout.segmentDigest(".zenread-1.part"))
        runCatching { RemoteLayout.book("../../etc/passwd", "epub") }.let { assertTrue(it.isFailure) }
        runCatching { RemoteLayout.cover("ABC") }.let { assertTrue(it.isFailure) }
    }

    private companion object {
        const val HASH = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    }
}
