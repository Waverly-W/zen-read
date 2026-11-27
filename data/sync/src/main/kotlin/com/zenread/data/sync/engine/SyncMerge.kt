package com.zenread.data.sync.engine

/**
 * Conflict resolution, inspired by upstream `sync_merge.dart`: last writer wins per row,
 * with a total, deterministic order so every device picks the same winner.
 *
 * Order (first difference decides):
 * 1. Annotations: a tombstone beats any live version. Re-creating a deleted highlight makes
 *    a new row with a fresh UUID, so an offline edit can never resurrect a deleted one.
 * 2. Larger [SyncRecord.updatedAt].
 * 3. Tombstone over live (same clock).
 * 4. Identical content → equivalent (no winner, nothing to transfer).
 * 5. Larger device id, then larger canonical content.
 */
object SyncMerge {
    /** Positive if [a] wins, negative if [b] wins, 0 if equivalent. */
    fun compare(a: SyncRecord, b: SyncRecord): Int {
        require(a.key == b.key) { "Comparing different rows" }
        if (a.kind == RecordKind.ANNOTATION && a.deleted != b.deleted) return if (a.deleted) 1 else -1
        a.updatedAt.compareTo(b.updatedAt).let { if (it != 0) return it }
        if (a.deleted != b.deleted) return if (a.deleted) 1 else -1
        if (a.deleted || a.data == b.data) return 0
        a.device.compareTo(b.device).let { if (it != 0) return it.sign() }
        return (a.data?.canonical().orEmpty()).compareTo(b.data?.canonical().orEmpty()).sign()
    }

    /** Union of [records], keeping the winner per key. Commutative and idempotent. */
    fun merge(records: Iterable<SyncRecord>, into: MutableMap<String, SyncRecord> = LinkedHashMap()): MutableMap<String, SyncRecord> {
        for (record in records) {
            val previous = into[record.key]
            if (previous == null || compare(record, previous) > 0) into[record.key] = record
        }
        return into
    }

    /**
     * What to do with the local and remote states of every row:
     * [SyncPlan.toApply] are remote versions that beat the local one (or have no local
     * counterpart); [SyncPlan.toPublish] are local versions the cloud has not seen.
     */
    fun plan(local: Map<String, SyncRecord>, remote: Map<String, SyncRecord>): SyncPlan {
        val toApply = mutableListOf<SyncRecord>()
        val toPublish = mutableListOf<SyncRecord>()
        for (key in local.keys + remote.keys) {
            val l = local[key]
            val r = remote[key]
            when {
                r == null -> toPublish += l!!
                l == null -> if (!r.deleted) toApply += r
                else -> {
                    val order = compare(l, r)
                    if (order > 0) toPublish += l else if (order < 0) toApply += r
                }
            }
        }
        return SyncPlan(toApply, toPublish)
    }

    private fun Int.sign() = if (this > 0) 1 else if (this < 0) -1 else 0
}

data class SyncPlan(val toApply: List<SyncRecord>, val toPublish: List<SyncRecord>)
