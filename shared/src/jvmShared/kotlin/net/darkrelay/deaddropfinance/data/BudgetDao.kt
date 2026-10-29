package net.darkrelay.deaddropfinance.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    // ---- reads (tombstones included only where a write path needs them) --------------------------------------
    @Query("SELECT * FROM account WHERE deleted = 0 ORDER BY archived, sort_order, name")
    fun accounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM envelope WHERE deleted = 0 ORDER BY archived, sort_order")
    fun envelopes(): Flow<List<EnvelopeEntity>>

    @Query("SELECT * FROM txn WHERE deleted = 0 ORDER BY date DESC, created_at DESC")
    fun transactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM txn_line WHERE deleted = 0")
    fun lines(): Flow<List<TransactionLineEntity>>

    @Query("SELECT * FROM envelope_move WHERE deleted = 0")
    fun moves(): Flow<List<MoveEntity>>

    @Query("SELECT * FROM bill WHERE deleted = 0")
    fun bills(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bill_payment WHERE deleted = 0")
    fun billPayments(): Flow<List<BillPaymentEntity>>

    @Query("SELECT * FROM category WHERE deleted = 0 ORDER BY sort_order, name")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE deleted = 0 ORDER BY sort_order, name")
    suspend fun categoriesNow(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun category(id: String): CategoryEntity?

    @Query("SELECT * FROM rule WHERE deleted = 0 ORDER BY priority, created_at")
    fun rules(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rule WHERE deleted = 0 ORDER BY priority, created_at")
    suspend fun rulesNow(): List<RuleEntity>

    @Query("SELECT * FROM rule WHERE id = :id")
    suspend fun rule(id: String): RuleEntity?

    @Query("SELECT * FROM receipt WHERE deleted = 0 ORDER BY created_at DESC")
    fun receipts(): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipt WHERE deleted = 0 ORDER BY created_at DESC")
    suspend fun receiptsNow(): List<ReceiptEntity>

    @Query("SELECT * FROM receipt WHERE id = :id")
    suspend fun receipt(id: String): ReceiptEntity?

    @Query("SELECT * FROM receipt_item WHERE deleted = 0 ORDER BY receipt_id, line")
    fun receiptItems(): Flow<List<ReceiptItemEntity>>

    @Query("SELECT * FROM receipt_item WHERE deleted = 0 ORDER BY receipt_id, line")
    suspend fun receiptItemsNow(): List<ReceiptItemEntity>

    @Query("SELECT * FROM receipt_item WHERE receipt_id = :receiptId AND deleted = 0 ORDER BY line")
    suspend fun itemsOf(receiptId: String): List<ReceiptItemEntity>

    @Query("SELECT * FROM item_return WHERE deleted = 0")
    fun itemReturns(): Flow<List<ItemReturnEntity>>

    @Query("SELECT * FROM item_return WHERE deleted = 0")
    suspend fun itemReturnsNow(): List<ItemReturnEntity>

    @Query("SELECT * FROM subscription WHERE deleted = 0")
    fun subscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscription WHERE deleted = 0")
    suspend fun subscriptionsNow(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscription WHERE id = :id")
    suspend fun subscription(id: String): SubscriptionEntity?

    @Query("SELECT * FROM parked_buy WHERE deleted = 0 ORDER BY parked_at")
    fun parkedBuys(): Flow<List<ParkedBuyEntity>>

    @Query("SELECT * FROM parked_buy WHERE deleted = 0 ORDER BY parked_at")
    suspend fun parkedBuysNow(): List<ParkedBuyEntity>

    @Query("SELECT * FROM parked_buy WHERE id = :id")
    suspend fun parkedBuy(id: String): ParkedBuyEntity?

    @Query("SELECT * FROM notif_app ORDER BY label")
    fun notifApps(): Flow<List<NotifAppEntity>>

    @Query("SELECT * FROM notif_app ORDER BY label")
    suspend fun notifAppsNow(): List<NotifAppEntity>

    @Query("SELECT * FROM notif_app WHERE package_name = :pkg")
    suspend fun notifApp(pkg: String): NotifAppEntity?

    @Upsert suspend fun upsertNotifApp(e: NotifAppEntity)

    @Query("DELETE FROM notif_app WHERE package_name = :pkg")
    suspend fun deleteNotifApp(pkg: String)

    @Query("SELECT * FROM account WHERE deleted = 0")
    suspend fun accountsNow(): List<AccountEntity>

    @Query("SELECT * FROM envelope WHERE deleted = 0")
    suspend fun envelopesNow(): List<EnvelopeEntity>

    @Query("SELECT * FROM txn WHERE deleted = 0")
    suspend fun transactionsNow(): List<TransactionEntity>

    @Query("SELECT * FROM txn_line WHERE deleted = 0")
    suspend fun linesNow(): List<TransactionLineEntity>

    @Query("SELECT * FROM envelope_move WHERE deleted = 0")
    suspend fun movesNow(): List<MoveEntity>

    @Query("SELECT * FROM bill WHERE deleted = 0")
    suspend fun billsNow(): List<BillEntity>

    @Query("SELECT * FROM bill_payment WHERE deleted = 0")
    suspend fun billPaymentsNow(): List<BillPaymentEntity>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun account(id: String): AccountEntity?

    @Query("SELECT * FROM envelope WHERE id = :id")
    suspend fun envelope(id: String): EnvelopeEntity?

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun transaction(id: String): TransactionEntity?

    @Query("SELECT * FROM txn_line WHERE id = :id")
    suspend fun line(id: String): TransactionLineEntity?

    @Query("SELECT * FROM txn_line WHERE transaction_id = :transactionId AND deleted = 0 ORDER BY sort_order")
    suspend fun linesOf(transactionId: String): List<TransactionLineEntity>

    @Query("SELECT * FROM envelope_move WHERE id = :id")
    suspend fun move(id: String): MoveEntity?

    @Query("SELECT * FROM envelope_move WHERE payday_transaction_id = :transactionId AND deleted = 0")
    suspend fun movesOfPayday(transactionId: String): List<MoveEntity>

    @Query("SELECT * FROM envelope_move WHERE linked_transaction_id = :transactionId AND deleted = 0")
    suspend fun movesLinkedTo(transactionId: String): List<MoveEntity>

    @Query("SELECT * FROM bill WHERE id = :id")
    suspend fun bill(id: String): BillEntity?

    @Query("SELECT * FROM bill_payment WHERE id = :id")
    suspend fun billPayment(id: String): BillPaymentEntity?

    // ---- writes (only BudgetStore calls these, so every one gets its change log row) ------------------------
    @Upsert suspend fun upsertAccount(e: AccountEntity)
    @Upsert suspend fun upsertEnvelope(e: EnvelopeEntity)
    @Upsert suspend fun upsertTransaction(e: TransactionEntity)
    @Upsert suspend fun upsertLine(e: TransactionLineEntity)
    @Upsert suspend fun upsertMove(e: MoveEntity)
    @Upsert suspend fun upsertBill(e: BillEntity)
    @Upsert suspend fun upsertBillPayment(e: BillPaymentEntity)
    @Upsert suspend fun upsertRule(e: RuleEntity)
    @Upsert suspend fun upsertCategory(e: CategoryEntity)
    @Upsert suspend fun upsertReceipt(e: ReceiptEntity)
    @Upsert suspend fun upsertReceiptItem(e: ReceiptItemEntity)
    @Upsert suspend fun upsertItemReturn(e: ItemReturnEntity)
    @Upsert suspend fun upsertSubscription(e: SubscriptionEntity)
    @Upsert suspend fun upsertParkedBuy(e: ParkedBuyEntity)

    // ---- change log and meta ----------------------------------------------------------------------------------
    @Insert suspend fun insertChange(e: ChangeLogEntity)

    @Query("SELECT COALESCE(MAX(seq), 0) FROM change_log")
    suspend fun lastSeq(): Long

    @Query("SELECT * FROM change_log ORDER BY seq")
    suspend fun changes(): List<ChangeLogEntity>

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Query("SELECT value FROM meta WHERE `key` = :key")
    fun metaFlow(key: String): Flow<String?>

    @Upsert suspend fun putMeta(e: MetaEntity)
}
