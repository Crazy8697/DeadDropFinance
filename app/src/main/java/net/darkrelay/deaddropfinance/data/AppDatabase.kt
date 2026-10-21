package net.darkrelay.deaddropfinance.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.LocalDate

/**
 * The budget database, schema version 2 (bank sync). Every version's schema is exported to app/schemas. From 0.6.0 on the
 * app may hold real data, so a version bump always comes with a real Migration and a migration test; a
 * destructive fallback is never configured (docs/DATABASE.md, enforced by DatabaseRulesTest).
 */
@Database(
    entities = [
        AccountEntity::class, EnvelopeEntity::class, TransactionEntity::class, TransactionLineEntity::class,
        MoveEntity::class, BillEntity::class, BillPaymentEntity::class, ChangeLogEntity::class, MetaEntity::class,
        ConnectionEntity::class, LinkedAccountEntity::class, SyncLogEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): BudgetDao
    abstract fun syncDao(): SyncDao

    companion object {
        const val NAME = "budget.db"
    }
}

class Converters {
    @TypeConverter fun dateToText(d: LocalDate?): String? = d?.toString()
    @TypeConverter fun textToDate(s: String?): LocalDate? = s?.let { LocalDate.parse(it) }
}
