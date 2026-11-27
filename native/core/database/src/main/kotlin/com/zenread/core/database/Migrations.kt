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

/**
 * v3 → v4: reading statistics.
 *
 * - `reading_sessions`: stretches of reading or listening, one local day each.
 * - `book_finishes`: the first time each book was read to the end.
 *
 * Both reference `books` with `ON DELETE CASCADE`. Only new tables, so nothing is backfilled;
 * written by hand to keep every migration after v2 explicit and tested.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_3_4_SQL.forEach(db::execSQL)
    }
}

internal val MIGRATION_3_4_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `reading_sessions` (`uuid` TEXT NOT NULL, `book_id` INTEGER NOT NULL, " +
        "`mode` TEXT NOT NULL, `started_at` INTEGER NOT NULL, `ended_at` INTEGER NOT NULL, " +
        "`local_day` INTEGER NOT NULL, `utc_offset_minutes` INTEGER NOT NULL, `pages` INTEGER NOT NULL, " +
        "PRIMARY KEY(`uuid`), FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_reading_sessions_book_id` ON `reading_sessions` (`book_id`)",
    "CREATE INDEX IF NOT EXISTS `index_reading_sessions_local_day` ON `reading_sessions` (`local_day`)",
    "CREATE TABLE IF NOT EXISTS `book_finishes` (`book_id` INTEGER NOT NULL, `uuid` TEXT NOT NULL, " +
        "`finished_at` INTEGER NOT NULL, `local_day` INTEGER NOT NULL, PRIMARY KEY(`book_id`), " +
        "FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_book_finishes_uuid` ON `book_finishes` (`uuid`)",
)
