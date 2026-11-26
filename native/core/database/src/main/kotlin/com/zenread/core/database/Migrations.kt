package com.zenread.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v2 → v3: sync identities and tombstones.
 *
 * - `books.updated_at` (metadata clock), backfilled with `created_at`.
 * - `annotations.uuid`, a random UUID v4 for every existing row, unique.
 * - `sync_tombstones` for deletions that still have to reach other devices.
 *
 * Manual rather than an auto-migration: the unique uuid index can only be created after
 * the backfill, and an auto-migration would create it while every row still has the default.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_2_3_SQL.forEach(db::execSQL)
    }
}

/** SQLite expression for a random RFC 4122 version 4 UUID, evaluated once per row. */
private const val RANDOM_UUID_SQL =
    "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
        "substr(lower(hex(randomblob(2))), 2) || '-' || " +
        "substr('89ab', 1 + (abs(random()) % 4), 1) || substr(lower(hex(randomblob(2))), 2) || '-' || " +
        "lower(hex(randomblob(6)))"

internal val MIGRATION_2_3_SQL = listOf(
    "ALTER TABLE `books` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0",
    "UPDATE `books` SET `updated_at` = `created_at`",
    "ALTER TABLE `annotations` ADD COLUMN `uuid` TEXT NOT NULL DEFAULT ''",
    "UPDATE `annotations` SET `uuid` = $RANDOM_UUID_SQL",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_annotations_uuid` ON `annotations` (`uuid`)",
    "CREATE TABLE IF NOT EXISTS `sync_tombstones` (`kind` TEXT NOT NULL, `sync_id` TEXT NOT NULL, " +
        "`deleted_at` INTEGER NOT NULL, PRIMARY KEY(`kind`, `sync_id`))",
)
