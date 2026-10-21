package net.darkrelay.deaddropfinance.data

import androidx.room.withTransaction
import java.time.LocalDate
import org.json.JSONObject

/**
 * The only way the app writes to the budget. Every write of a synced row also writes its change log row, in the
 * same database transaction: if anything in a write fails, neither the change nor its log row is kept. Domain
 * operations (BudgetActions) build on [write].
 */
class BudgetStore(
    val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val dao: BudgetDao = db.dao()
    val sync: SyncDao = db.syncDao()

    /** One atomic write: everything [block] puts is saved together with its change rows, or none of it is. */
    suspend fun <R> write(block: suspend Writer.() -> R): R = db.withTransaction {
        val w = Writer(deviceId(), clock())
        w.block()
    }

    inner class Writer(private val deviceId: String, val now: Long) {

        /** Saves [new] (an insert if [old] is null) and logs it: INSERT, UPDATE, or DELETE when it becomes a tombstone. */
        suspend fun <T : Synced> put(old: T?, new: T): T {
            when (new) {
                is AccountEntity -> dao.upsertAccount(new)
                is EnvelopeEntity -> dao.upsertEnvelope(new)
                is TransactionEntity -> dao.upsertTransaction(new)
                is TransactionLineEntity -> dao.upsertLine(new)
                is MoveEntity -> dao.upsertMove(new)
                is BillEntity -> dao.upsertBill(new)
                is BillPaymentEntity -> dao.upsertBillPayment(new)
                is RuleEntity -> dao.upsertRule(new)
                is ReceiptEntity -> dao.upsertReceipt(new)
                is ReceiptItemEntity -> dao.upsertReceiptItem(new)
                is ItemReturnEntity -> dao.upsertItemReturn(new)
                is SubscriptionEntity -> dao.upsertSubscription(new)
                else -> error("not a synced table: ${new::class.simpleName}")
            }
            val op = when {
                old == null -> ChangeOperation.INSERT
                new.deleted && !old.deleted -> ChangeOperation.DELETE
                else -> ChangeOperation.UPDATE
            }
            val before = old?.fields() ?: emptyMap()
            val changed = JSONObject()
            for ((k, v) in new.fields()) if (old == null || before[k] != v) changed.put(k, v ?: JSONObject.NULL)
            if (old != null && changed.length() == 0) return new      // nothing changed: nothing to log
            dao.insertChange(
                ChangeLogEntity(
                    entityType = entityType(new),
                    entityId = new.id,
                    operation = op,
                    changedFields = changed.toString(),
                    timestamp = now,
                    deviceId = deviceId,
                    seq = dao.lastSeq() + 1,
                ),
            )
            return new
        }
    }

    /** This install's random id, made on first use and kept in the encrypted database. */
    suspend fun deviceId(): String = dao.meta(META_DEVICE_ID) ?: newId().also { dao.putMeta(MetaEntity(META_DEVICE_ID, it)) }

    /** First launch: the Rollover envelope exists exactly once, first in the fill order. Nothing else is seeded. */
    suspend fun ensureSystemEnvelopes() {
        write {
            if (dao.envelopesNow().none { it.mode == EnvelopeMode.ROLLOVER }) {
                put(null, EnvelopeEntity(name = "Rollover", mode = EnvelopeMode.ROLLOVER, sortOrder = 0, createdAt = now, updatedAt = now))
            }
        }
    }

    companion object {
        const val META_DEVICE_ID = "device_id"

        fun entityType(e: Synced): String = when (e) {
            is AccountEntity -> "account"
            is EnvelopeEntity -> "envelope"
            is TransactionEntity -> "transaction"
            is TransactionLineEntity -> "transaction_line"
            is MoveEntity -> "envelope_move"
            is BillEntity -> "bill"
            is BillPaymentEntity -> "bill_payment"
            is RuleEntity -> "rule"
            is ReceiptEntity -> "receipt"
            is ReceiptItemEntity -> "receipt_item"
            is ItemReturnEntity -> "item_return"
            is SubscriptionEntity -> "subscription"
            else -> error("not a synced table")
        }

        fun today(): LocalDate = LocalDate.now()
    }
}
