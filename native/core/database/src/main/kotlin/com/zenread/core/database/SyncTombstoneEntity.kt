package com.zenread.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * A row deleted on this device, kept so the deletion can be synced to other devices.
 * [syncId] is the global id: the file hash for books, the annotation uuid for annotations.
 */
@Entity(tableName = "sync_tombstones", primaryKeys = ["kind", "sync_id"])
data class SyncTombstoneEntity(
    val kind: String,
    @ColumnInfo(name = "sync_id") val syncId: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
) {
    companion object {
        const val KIND_BOOK = "book"
        const val KIND_ANNOTATION = "annotation"
    }
}
