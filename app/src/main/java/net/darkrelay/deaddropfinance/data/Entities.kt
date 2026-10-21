package net.darkrelay.deaddropfinance.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.util.UUID

/*
 * The budget's tables. Rules that hold for every synced table:
 *  - ids are random UUIDs as text, never auto-increment, so two phones can merge later without clashes
 *  - money is a Long count of cents, never a Float or Double
 *  - a transaction's date is a local date (yyyy-MM-dd); createdAt and updatedAt are UTC instants in epoch millis
 *  - nothing is ever removed: a delete sets deleted = true and keeps the row as a tombstone
 *  - every write also writes a change_log row in the same database transaction (BudgetStore)
 */

fun newId(): String = UUID.randomUUID().toString()

/** A row that takes part in sync: it has an id, a tombstone flag and a field map for the change log. */
interface Synced {
    val id: String
    val deleted: Boolean
    val createdAt: Long
    val updatedAt: Long
    /** The entity's fields by column name, for the change log's changed-fields JSON. */
    fun fields(): Map<String, Any?>
}

enum class AccountType { BANK, PAYMENT_APP, CASH }

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey override val id: String = newId(),
    val name: String,
    val type: AccountType,
    @ColumnInfo(name = "starting_balance") val startingBalance: Long = 0,
    @ColumnInfo(name = "start_date") val startDate: LocalDate,
    /** Archived: hidden, and out of the budget (its balance doesn't count in Ready to Assign). */
    val archived: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    /** Track only, not in the budget: the balance shows and syncs, but never counts and nothing lands in To sort. */
    @ColumnInfo(name = "track_only", defaultValue = "0") val trackOnly: Boolean = false,
    /** A minimum that stays put: Ready to Assign counts the balance minus this (never more than the balance). */
    @ColumnInfo(name = "held_amount", defaultValue = "0") val heldAmount: Long = 0,
    @ColumnInfo(name = "created_at") override val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    override val deleted: Boolean = false,
) : Synced {
    /** The account's money is part of the budget. */
    val onBudget get() = !archived && !trackOnly

    override fun fields() = mapOf(
        "name" to name, "type" to type.name, "starting_balance" to startingBalance, "start_date" to startDate.toString(),
        "archived" to archived, "sort_order" to sortOrder, "track_only" to trackOnly, "held_amount" to heldAmount, "deleted" to deleted,
    )
}

/**
 * How an envelope fills on payday (FEATURES.md, Envelopes). ROLLOVER and CASH are the two system roles: they
 * never auto-fill.
 */
enum class EnvelopeMode { FILL_TO_TARGET, ADD_EVERY_PAYDAY, GOAL, SEASONAL, DEBT, CASH, ROLLOVER }

@Entity(tableName = "envelope")
data class EnvelopeEntity(
    @PrimaryKey override val id: String = newId(),
    val name: String,
    val mode: EnvelopeMode,
    /** Fill to target: what each payday tops the envelope up to. Null = no target yet. */
    val target: Long? = null,
    /** Add every payday, Seasonal (until linked bills give an average) and Debt: the amount added each payday. */
    @ColumnInfo(name = "per_payday") val perPayday: Long? = null,
    /** Goal: the amount to reach and the date to reach it by. */
    @ColumnInfo(name = "goal_amount") val goalAmount: Long? = null,
    @ColumnInfo(name = "goal_date") val goalDate: LocalDate? = null,
    /** Debt: the balance owed (optional until known) as of [debtOwedSince]; payments since then reduce it. */
    @ColumnInfo(name = "debt_owed") val debtOwed: Long? = null,
    @ColumnInfo(name = "debt_owed_since") val debtOwedSince: LocalDate? = null,
    /** Debt: an optional required payment and its due date (reminders come with bills in Stage 10). */
    @ColumnInfo(name = "required_payment") val requiredPayment: Long? = null,
    @ColumnInfo(name = "due_date") val dueDate: LocalDate? = null,
    val archived: Boolean = false,
    /** The fill order: payday auto-fill and the Assign screen go through envelopes in this order. */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    /** Everyday spending (groceries, gas): counts in Safe to Spend. */
    @ColumnInfo(name = "everyday", defaultValue = "0") val everyday: Boolean = false,
    @ColumnInfo(name = "created_at") override val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    override val deleted: Boolean = false,
) : Synced {
    val isSystem get() = mode == EnvelopeMode.ROLLOVER || mode == EnvelopeMode.CASH

    override fun fields() = mapOf(
        "name" to name, "mode" to mode.name, "target" to target, "per_payday" to perPayday,
        "goal_amount" to goalAmount, "goal_date" to goalDate?.toString(), "debt_owed" to debtOwed,
        "debt_owed_since" to debtOwedSince?.toString(), "required_payment" to requiredPayment,
        "due_date" to dueDate?.toString(), "archived" to archived, "sort_order" to sortOrder, "everyday" to everyday, "deleted" to deleted,
    )
}

enum class TransactionKind { NORMAL, TRANSFER }

enum class TransactionSource { MANUAL, CSV, SYNC }

/**
 * How a transaction's envelopes were set. HAND: picked or edited by the user. APPROVED: the user approved a
 * suggestion. AUTO: a rule sorted it as it arrived (auto-approve on). RETURN: confirmed as a return of a purchase.
 */
enum class SortedBy { HAND, APPROVED, AUTO, RETURN }

/**
 * One money movement on one account. [amount] is signed: negative is money out, positive is money in. A TRANSFER
 * moves [amount] (positive) from [accountId] to [transferAccountId] and touches no envelope. A NORMAL transaction's
 * lines (TransactionLineEntity) say where its money went and always add up to [amount].
 */
@Entity(tableName = "txn", indices = [Index("account_id"), Index("date"), Index("external_id")])
data class TransactionEntity(
    @PrimaryKey override val id: String = newId(),
    @ColumnInfo(name = "account_id") val accountId: String,
    val kind: TransactionKind = TransactionKind.NORMAL,
    @ColumnInfo(name = "transfer_account_id") val transferAccountId: String? = null,
    val date: LocalDate,
    val payee: String = "",
    val memo: String = "",
    val amount: Long,
    /** Entered before the bank posted it; a matching posted import takes its place instead of doubling it. */
    val pending: Boolean = false,
    /** Pass-through money: both halves of a wash share this id and count as neither income nor spending. */
    @ColumnInfo(name = "wash_group_id") val washGroupId: String? = null,
    /** The user marked this deposit as their payday: it closes the budget month and starts the next. */
    @ColumnInfo(name = "is_payday") val isPayday: Boolean = false,
    /** An ATM fee, tagged automatically; a fee rebate offsets fees. */
    val fee: Boolean = false,
    @ColumnInfo(name = "fee_rebate") val feeRebate: Boolean = false,
    val source: TransactionSource = TransactionSource.MANUAL,
    /** The bank's own description from the import that brought or posted this, for duplicate checks. */
    @ColumnInfo(name = "import_description") val importDescription: String? = null,
    /** The provider's id for this transaction (bank sync), "provider:account:id". Null for hand entry and CSV. */
    @ColumnInfo(name = "external_id") val externalId: String? = null,
    /** How its envelopes were set; null while it waits in To sort (and for rows sorted before 0.8.0). */
    @ColumnInfo(name = "sorted_by") val sortedBy: SortedBy? = null,
    @ColumnInfo(name = "sorted_at") val sortedAt: Long? = null,
    /** The rule that sorted it or whose suggestion was approved. */
    @ColumnInfo(name = "rule_id") val ruleId: String? = null,
    /** A return: the purchase it gives money back to. */
    @ColumnInfo(name = "return_of") val returnOf: String? = null,
    @ColumnInfo(name = "created_at") override val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    override val deleted: Boolean = false,
) : Synced {
    val isWash get() = washGroupId != null

    override fun fields() = mapOf(
        "account_id" to accountId, "kind" to kind.name, "transfer_account_id" to transferAccountId,
        "date" to date.toString(), "payee" to payee, "memo" to memo, "amount" to amount, "pending" to pending,
        "wash_group_id" to washGroupId, "is_payday" to isPayday, "fee" to fee, "fee_rebate" to feeRebate,
        "source" to source.name, "import_description" to importDescription, "external_id" to externalId,
        "sorted_by" to sortedBy?.name, "sorted_at" to sortedAt, "rule_id" to ruleId, "return_of" to returnOf, "deleted" to deleted,
    )
}

/**
 * Where a line's money sits: an envelope, Ready to Assign (income only), not sorted yet (To sort), or OFF_BUDGET:
 * recorded as already handled in an account outside the budget (it joins Ready to Assign if the account joins).
 * TRANSFER: money between the user's own accounts, neither spending nor income; it goes out of or into Ready to
 * Assign, so the two sides of a transfer between budget accounts cancel out.
 */
enum class LineBucket { ENVELOPE, READY_TO_ASSIGN, UNSORTED, OFF_BUDGET, TRANSFER }

@Entity(tableName = "txn_line", indices = [Index("transaction_id"), Index("envelope_id")])
data class TransactionLineEntity(
    @PrimaryKey override val id: String = newId(),
    @ColumnInfo(name = "transaction_id") val transactionId: String,
    val bucket: LineBucket,
    @ColumnInfo(name = "envelope_id") val envelopeId: String? = null,
    /** Signed like the transaction's amount. */
    val amount: Long,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") override val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    override val deleted: Boolean = false,
) : Synced {
    override fun fields() = mapOf(
        "transaction_id" to transactionId, "bucket" to bucket.name, "envelope_id" to envelopeId, "amount" to amount,
        "sort_order" to sortOrder, "deleted" to deleted,
    )
}

/** Why money moved between envelopes or Ready to Assign. */
enum class MoveKind { ASSIGN, MOVE, SWEEP, AUTOFILL, COVER, ATM }

/**
 * Money moving between envelopes, or between Ready to Assign and an envelope. A null [fromEnvelopeId] means from
 * Ready to Assign; a null [toEnvelopeId] means back to Ready to Assign. [amount] is always positive.
 */
@Entity(tableName = "envelope_move", indices = [Index("from_envelope_id"), Index("to_envelope_id")])
data class MoveEntity(
    @PrimaryKey override val id: String = newId(),
    @ColumnInfo(name = "from_envelope_id") val fromEnvelopeId: String?,
    @ColumnInfo(name = "to_envelope_id") val toEnvelopeId: String?,
    val amount: Long,
    val date: LocalDate,
    val kind: MoveKind,
    /** Set on the sweep and auto-fill moves a payday made, so unmarking the payday can undo them. */
    @ColumnInfo(name = "payday_transaction_id") val paydayTransactionId: String? = null,
    /** Set on the move that goes with an ATM withdrawal (the transfer to the cash account). */
    @ColumnInfo(name = "linked_transaction_id") val linkedTransactionId: String? = null,
    @ColumnInfo(name = "created_at") override val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    override val deleted: Boolean = false,
) : Synced {
    override fun fields() = mapOf(
        "from_envelope_id" to fromEnvelopeId, "to_envelope_id" to toEnvelopeId, "amount" to amount,
        "date" to date.toString(), "kind" to kind.name, "payday_transaction_id" to paydayTransactionId,
        "linked_transaction_id" to linkedTransactionId, "deleted" to deleted,
    )
}

enum class BillRecurrence { NONE, MONTHLY, YEARLY, IRREGULAR }

/** Bills (FEATURES.md: Stage 6 data model; screens and reminders come in Stage 10). Many bills can feed one envelope. */
@Entity(tableName = "bill", indices = [Index("envelope_id")])
data class BillEntity(
    @PrimaryKey override val id: String = newId(),
    val name: String,
    @ColumnInfo(name = "envelope_id") val envelopeId: String?,
    @ColumnInfo(name = "amount_due") val amountDue: Long,
    @ColumnInfo(name = "due_date") val dueDate: LocalDate?,
    val recurrence: BillRecurrence = BillRecurrence.MONTHLY,
    val autopay: Boolean = false,
    /** Reminders are off by default and turned on per bill. */
    val reminder: Boolean = false,
    @ColumnInfo(name = "created_at") override val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    override val deleted: Boolean = false,
) : Synced {
    override fun fields() = mapOf(
        "name" to name, "envelope_id" to envelopeId, "amount_due" to amountDue, "due_date" to dueDate?.toString(),
        "recurrence" to recurrence.name, "autopay" to autopay, "reminder" to reminder, "deleted" to deleted,
    )
}

/** One payment toward a bill; a bill counts as paid only when its payments reach the amount due. */
@Entity(tableName = "bill_payment", indices = [Index("bill_id")])
data class BillPaymentEntity(
    @PrimaryKey override val id: String = newId(),
    @ColumnInfo(name = "bill_id") val billId: String,
    @ColumnInfo(name = "transaction_id") val transactionId: String?,
    val amount: Long,
    val date: LocalDate,
    @ColumnInfo(name = "created_at") override val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    override val deleted: Boolean = false,
) : Synced {
    override fun fields() = mapOf(
        "bill_id" to billId, "transaction_id" to transactionId, "amount" to amount, "date" to date.toString(),
        "deleted" to deleted,
    )
}

/** What a rule does with a match: put it in envelopes (one, or a split), or mark it a transfer between own accounts. */
enum class RuleAction { ENVELOPE, TRANSFER }

/** Made by the user, or learned from repeated identical choices (shown once so it can be undone). */
enum class RuleOrigin { USER, LEARNED }

/**
 * An auto-categorize rule. It matches a transaction's cleaned payee (Payees.clean) and, optionally, its account, its
 * direction and an amount range. Rules are tried in [priority] order, lowest first; the first match wins. A rule only
 * ever suggests, unless auto-approve is on; it never touches a transaction already sorted.
 */
@Entity(tableName = "rule")
data class RuleEntity(
    @PrimaryKey override val id: String = newId(),
    /** Cleaned words the payee must contain, in order (Payees.clean of what the user typed). */
    @ColumnInfo(name = "match_text") val matchText: String,
    @ColumnInfo(name = "account_id") val accountId: String? = null,
    /** -1 money out, 1 money in, 0 either. */
    val direction: Int = -1,
    /** An optional range for the amount, in positive cents, both ends included. */
    @ColumnInfo(name = "min_amount") val minAmount: Long? = null,
    @ColumnInfo(name = "max_amount") val maxAmount: Long? = null,
    val action: RuleAction = RuleAction.ENVELOPE,
    /**
     * ENVELOPE: where the money goes, as "envelopeId:weight" pairs joined by ";" ("rta" for Ready to Assign). One pair
     * is one envelope; several split the amount in proportion to the weights (cents of the example amount).
     */
    val splits: String = "",
    /** Never auto-approved, even with auto-approve on (stores that sell everything). */
    @ColumnInfo(name = "always_ask") val alwaysAsk: Boolean = false,
    val priority: Int = 0,
    val origin: RuleOrigin = RuleOrigin.USER,
    /** A learned rule's notice ("made a rule from your choices") was seen. */
    val acknowledged: Boolean = true,
    @ColumnInfo(name = "created_at") override val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    override val deleted: Boolean = false,
) : Synced {
    override fun fields() = mapOf(
        "match_text" to matchText, "account_id" to accountId, "direction" to direction, "min_amount" to minAmount,
        "max_amount" to maxAmount, "action" to action.name, "splits" to splits, "always_ask" to alwaysAsk,
        "priority" to priority, "origin" to origin.name, "acknowledged" to acknowledged, "deleted" to deleted,
    )
}

enum class ChangeOperation { INSERT, UPDATE, DELETE }

/**
 * The change log for future sync: one row per insert, update or delete of a synced row, written in the same
 * database transaction as the change. Nothing reads it yet except tests.
 */
@Entity(tableName = "change_log", indices = [Index(value = ["seq"], unique = true)])
data class ChangeLogEntity(
    @PrimaryKey @ColumnInfo(name = "change_id") val changeId: String = newId(),
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val operation: ChangeOperation,
    /** JSON object of the fields that changed, with their new values. */
    @ColumnInfo(name = "changed_fields") val changedFields: String,
    /** UTC instant, epoch millis. */
    val timestamp: Long,
    /** This install's random id (never a hardware or account id). */
    @ColumnInfo(name = "device_id") val deviceId: String,
    /** Local order of changes on this install: 1, 2, 3 ... */
    val seq: Long,
)

/** Small key/value settings that live inside the encrypted database (the install's device id). */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
