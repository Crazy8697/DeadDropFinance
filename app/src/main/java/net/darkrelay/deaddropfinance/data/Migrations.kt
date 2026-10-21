package net.darkrelay.deaddropfinance.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema change is a real migration with a test (MigrationTest). Nothing here ever drops or rewrites budget
 * data: version 2 adds a nullable column and the bank sync tables, version 3 two account columns with defaults.
 */
object Migrations {

    /** 1 to 2 (0.7.0): bank sync. The SQL is the schema Room exports for version 2 (app/schemas/.../2.json). */
    val V1_V2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `txn` ADD COLUMN `external_id` TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_external_id` ON `txn` (`external_id`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `connection` (`id` TEXT NOT NULL, `provider` TEXT NOT NULL, `name` TEXT NOT NULL, `enrollment_id` TEXT, `status` TEXT NOT NULL, `fix_code` TEXT, `last_sync_at` INTEGER, `last_result` TEXT, `provider_messages` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `linked_account` (`id` TEXT NOT NULL, `connection_id` TEXT NOT NULL, `provider_account_id` TEXT NOT NULL, `provider_name` TEXT NOT NULL, `mask` TEXT NOT NULL, `account_id` TEXT, `provider_balance` INTEGER, `provider_balance_at` INTEGER, `history_from` TEXT, `last_synced_date` TEXT, `set_start_balance` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_linked_account_connection_id` ON `linked_account` (`connection_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_linked_account_account_id` ON `linked_account` (`account_id`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `sync_log` (`id` TEXT NOT NULL, `connection_id` TEXT NOT NULL, `at` INTEGER NOT NULL, `ok` INTEGER NOT NULL, `added` INTEGER NOT NULL, `updated` INTEGER NOT NULL, `removed` INTEGER NOT NULL, `message` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_log_connection_id` ON `sync_log` (`connection_id`)")
        }
    }

    /** 2 to 3 (0.7.2): track-only accounts and held amounts. Two columns with defaults, nothing else changes. */
    val V2_V3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `account` ADD COLUMN `track_only` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `account` ADD COLUMN `held_amount` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL = arrayOf(V1_V2, V2_V3)
}
