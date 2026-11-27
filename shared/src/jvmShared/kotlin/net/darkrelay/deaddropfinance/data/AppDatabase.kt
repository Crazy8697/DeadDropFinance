package net.darkrelay.deaddropfinance.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.LocalDate

/**
 * The budget database, schema version 16 (a bill's amount for one budget month). Every version's schema is exported to app/schemas. From 0.6.0 on the
 * app may hold real data, so a version bump always comes with a real Migration and a migration test; a
 * destructive fallback is never configured (docs/DATABASE.md, enforced by DatabaseRulesTest).
 */
@Database(
    entities = [
        AccountEntity::class, EnvelopeEntity::class, TransactionEntity::class, TransactionLineEntity::class,
        MoveEntity::class, BillEntity::class, BillPaymentEntity::class, ChangeLogEntity::class, MetaEntity::class,
        RuleEntity::class,
        ReceiptEntity::class, ReceiptItemEntity::class, ItemReturnEntity::class,
        SubscriptionEntity::class, NotifAppEntity::class, ParkedBuyEntity::class, CategoryEntity::class, BillMonthEntity::class,
    ],
    version = 16,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): BudgetDao

    companion object {
        const val NAME = "budget.db"
    }
}

/**
 * Schema changes the phone (app Migrations) and the program (DesktopMigrations) both run, so the two databases stay
 * the same schema. Each only adds: nothing existing changes.
 */
object SchemaChanges {
    /** 12 to 13 (phone 1.9.0, program 0.15.0): a Debt envelope's yearly interest rate, in hundredths of a percent. */
    val V12_V13 = listOf("ALTER TABLE `envelope` ADD COLUMN `interest_rate` INTEGER")

    /** 13 to 14 (phone 1.9.3, program 0.15.2): a receipt item's tax letter and its price with tax. */
    val V13_V14 = listOf(
        "ALTER TABLE `receipt_item` ADD COLUMN `tax_letter` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `receipt_item` ADD COLUMN `price_with_tax` INTEGER",
    )

    /** 14 to 15 (phone 1.9.7, program 0.15.10): a transaction's bank original description and the import that made it. */
    val V14_V15 = listOf(
        "ALTER TABLE `txn` ADD COLUMN `original_description` TEXT",
        "ALTER TABLE `txn` ADD COLUMN `import_id` TEXT",
    )

    /** 15 to 16 (phone 1.9.9, program 0.15.12): a bill's amount for one budget month (Bills, Coming up, This month). */
    val V15_V16 = listOf(
        "CREATE TABLE IF NOT EXISTS `bill_month` (`id` TEXT NOT NULL, `bill_id` TEXT NOT NULL, `month_start` TEXT NOT NULL, `amount` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_bill_month_bill_id` ON `bill_month` (`bill_id`)",
        // money assigned ahead to the next budget month (1.9.9)
        "ALTER TABLE `envelope_move` ADD COLUMN `for_month` TEXT",
    )
}

class Converters {
    @TypeConverter fun dateToText(d: LocalDate?): String? = d?.toString()
    @TypeConverter fun textToDate(s: String?): LocalDate? = s?.let { LocalDate.parse(it) }
}
