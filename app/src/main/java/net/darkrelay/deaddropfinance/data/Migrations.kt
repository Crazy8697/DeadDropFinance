package net.darkrelay.deaddropfinance.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema change is a real migration with a test (MigrationTest). Nothing here ever drops or rewrites budget
 * data: version 2 adds a nullable column and the bank sync tables, version 3 two account columns with defaults,
 * version 5 the rule table and nullable or defaulted columns, version 6 the receipt tables, version 7 bill columns
 * with defaults and the subscription and notification app tables.
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

    /** 3 to 4 (0.7.3): the date a provider's balance was true. One nullable column on this phone's own table. */
    val V3_V4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `linked_account` ADD COLUMN `provider_balance_date` INTEGER")
        }
    }

    /**
     * 4 to 5 (0.8.0): auto-categorize rules, how each transaction was sorted, returns, everyday envelopes. A new
     * table and nullable or defaulted columns; no existing value changes, so every transaction stays as it was.
     */
    val V4_V5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `rule` (`id` TEXT NOT NULL, `match_text` TEXT NOT NULL, `account_id` TEXT, `direction` INTEGER NOT NULL, `min_amount` INTEGER, `max_amount` INTEGER, `action` TEXT NOT NULL, `splits` TEXT NOT NULL, `always_ask` INTEGER NOT NULL, `priority` INTEGER NOT NULL, `origin` TEXT NOT NULL, `acknowledged` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("ALTER TABLE `txn` ADD COLUMN `sorted_by` TEXT")
            db.execSQL("ALTER TABLE `txn` ADD COLUMN `sorted_at` INTEGER")
            db.execSQL("ALTER TABLE `txn` ADD COLUMN `rule_id` TEXT")
            db.execSQL("ALTER TABLE `txn` ADD COLUMN `return_of` TEXT")
            db.execSQL("ALTER TABLE `envelope` ADD COLUMN `everyday` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** 5 to 6 (0.9.0): receipts, their items, and items returned. Three new tables; nothing existing changes. */
    val V5_V6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `receipt` (`id` TEXT NOT NULL, `transaction_id` TEXT, `store` TEXT NOT NULL, `date` TEXT, `total` INTEGER, `tax` INTEGER, `kind` TEXT NOT NULL, `pages` INTEGER NOT NULL, `low_confidence` TEXT NOT NULL, `raw_text` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_transaction_id` ON `receipt` (`transaction_id`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `receipt_item` (`id` TEXT NOT NULL, `receipt_id` TEXT NOT NULL, `line` INTEGER NOT NULL, `text` TEXT NOT NULL, `name` TEXT NOT NULL, `quantity_milli` INTEGER NOT NULL, `unit_price` INTEGER, `amount` INTEGER NOT NULL, `envelope_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_item_receipt_id` ON `receipt_item` (`receipt_id`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `item_return` (`id` TEXT NOT NULL, `item_id` TEXT NOT NULL, `transaction_id` TEXT NOT NULL, `quantity_milli` INTEGER NOT NULL, `amount` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_return_item_id` ON `item_return` (`item_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_return_transaction_id` ON `item_return` (`transaction_id`)")
        }
    }

    /**
     * 6 to 7 (0.10.0): bills in full (reminder lead time, payment matching, changing amounts, which cycle a payment
     * counts toward and how it was paid), debt reminders, rules that count toward a bill, subscriptions, and the bank
     * apps whose notifications are read. New columns all have defaults or are nullable; nothing existing changes.
     */
    val V6_V7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `bill` ADD COLUMN `reminder_days` INTEGER NOT NULL DEFAULT 2")
            db.execSQL("ALTER TABLE `bill` ADD COLUMN `match_text` TEXT")
            db.execSQL("ALTER TABLE `bill` ADD COLUMN `any_amount` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `bill` ADD COLUMN `varies` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `bill_payment` ADD COLUMN `period_due` TEXT")
            db.execSQL("ALTER TABLE `bill_payment` ADD COLUMN `method` TEXT NOT NULL DEFAULT 'BANK'")
            db.execSQL("ALTER TABLE `envelope` ADD COLUMN `reminder` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `envelope` ADD COLUMN `reminder_days` INTEGER NOT NULL DEFAULT 2")
            db.execSQL("ALTER TABLE `rule` ADD COLUMN `bill_id` TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS `subscription` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `match_key` TEXT NOT NULL, `status` TEXT NOT NULL, `bill_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `notif_app` (`package_name` TEXT NOT NULL, `label` TEXT NOT NULL, `account_id` TEXT, `enabled` INTEGER NOT NULL, `templates` TEXT NOT NULL, `last_amount` INTEGER, `last_payee` TEXT, `last_deposit` INTEGER NOT NULL, `last_at` INTEGER, PRIMARY KEY(`package_name`))")
        }
    }

    /** 7 to 8 (0.11.0): parked buys. A new table only; nothing existing changes. */
    val V7_V8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `parked_buy` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `amount` INTEGER NOT NULL, `wait_days` INTEGER NOT NULL, `parked_at` INTEGER NOT NULL, `ready_at` INTEGER NOT NULL, `status` TEXT NOT NULL, `envelope_id` TEXT, `bought_on` TEXT, `transaction_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        }
    }

    val ALL = arrayOf(V1_V2, V2_V3, V3_V4, V4_V5, V5_V6, V6_V7, V7_V8)
}
