package net.darkrelay.deaddropfinance.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.LocalDate

/**
 * The budget database, schema version 13 (a Debt envelope's interest rate). Every version's schema is exported to app/schemas. From 0.6.0 on the
 * app may hold real data, so a version bump always comes with a real Migration and a migration test; a
 * destructive fallback is never configured (docs/DATABASE.md, enforced by DatabaseRulesTest).
 */
@Database(
    entities = [
        AccountEntity::class, EnvelopeEntity::class, TransactionEntity::class, TransactionLineEntity::class,
        MoveEntity::class, BillEntity::class, BillPaymentEntity::class, ChangeLogEntity::class, MetaEntity::class,
        RuleEntity::class,
        ReceiptEntity::class, ReceiptItemEntity::class, ItemReturnEntity::class,
        SubscriptionEntity::class, NotifAppEntity::class, ParkedBuyEntity::class, CategoryEntity::class,
    ],
    version = 13,
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
}

class Converters {
    @TypeConverter fun dateToText(d: LocalDate?): String? = d?.toString()
    @TypeConverter fun textToDate(s: String?): LocalDate? = s?.let { LocalDate.parse(it) }
}
