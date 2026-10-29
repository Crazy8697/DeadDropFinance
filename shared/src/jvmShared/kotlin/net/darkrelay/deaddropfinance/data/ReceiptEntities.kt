package net.darkrelay.deaddropfinance.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/*
 * Receipts (schema version 6). Budget data like the rest: synced rows with tombstones and change log rows. The photos
 * themselves are files, encrypted on this phone (ReceiptImages), never in shared storage or the gallery; a receipt
 * row only says how many pages it has.
 */

/** A purchase receipt, or the receipt a store gives for a return. */
enum class ReceiptKind { PURCHASE, RETURN }

@Entity(tableName = "receipt", indices = [Index("transaction_id")])
data class ReceiptEntity(
    @PrimaryKey override val id: String = newId(),
    /** The bank transaction it belongs to; null while it waits for one (the unmatched list). */
    @ColumnInfo(name = "transaction_id") val transactionId: String? = null,
    val store: String = "",
    val date: LocalDate? = null,
    /** What was paid, in positive cents (a return receipt's refund is positive too). */
    val total: Long? = null,
    val tax: Long? = null,
    val kind: ReceiptKind = ReceiptKind.PURCHASE,
    /** How many photos (pages) it has: files named after the receipt id. */
    val pages: Int = 0,
    /** Fields text recognition wasn't sure of, comma separated (store, date, total, tax, items). */
    @ColumnInfo(name = "low_confidence") val lowConfidence: String = "",
    /** The recognized text, kept so the receipt can be read again later. */
    @ColumnInfo(name = "raw_text") val rawText: String = "",
    @ColumnInfo(name = "created_at") override val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    override val deleted: Boolean = false,
) : Synced {
    override fun fields() = mapOf(
        "transaction_id" to transactionId, "store" to store, "date" to date?.toString(), "total" to total, "tax" to tax,
        "kind" to kind.name, "pages" to pages, "low_confidence" to lowConfidence, "raw_text" to rawText, "deleted" to deleted,
    )
}

/**
 * One line of a receipt. Kept as structured data (with the receipt's store and date) so a later price history can
 * use it. [amount] is the line total in cents; a discount line is negative. [quantityMilli] is the quantity in
 * thousandths (1000 = one; 1250 = 1.25 lb).
 */
@Entity(tableName = "receipt_item", indices = [Index("receipt_id")])
data class ReceiptItemEntity(
    @PrimaryKey override val id: String = newId(),
    @ColumnInfo(name = "receipt_id") val receiptId: String,
    val line: Int = 0,
    /** The text as read from the receipt. */
    val text: String = "",
    /** Cleaned: lower case words, no codes or numbers. */
    val name: String = "",
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long = 1000,
    @ColumnInfo(name = "unit_price") val unitPrice: Long? = null,
    val amount: Long,
    /** Split by items: the envelope this item went to. */
    @ColumnInfo(name = "envelope_id") val envelopeId: String? = null,
    @ColumnInfo(name = "created_at") override val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    override val deleted: Boolean = false,
) : Synced {
    override fun fields() = mapOf(
        "receipt_id" to receiptId, "line" to line, "text" to text, "name" to name, "quantity_milli" to quantityMilli,
        "unit_price" to unitPrice, "amount" to amount, "envelope_id" to envelopeId, "deleted" to deleted,
    )
}

/** Part of a receipt item that came back: [quantityMilli] of it, refunded by return transaction [transactionId]. */
@Entity(tableName = "item_return", indices = [Index("item_id"), Index("transaction_id")])
data class ItemReturnEntity(
    @PrimaryKey override val id: String = newId(),
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "transaction_id") val transactionId: String,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    /** The share of the refund that came back for it (positive cents). */
    val amount: Long,
    @ColumnInfo(name = "created_at") override val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    override val deleted: Boolean = false,
) : Synced {
    override fun fields() = mapOf(
        "item_id" to itemId, "transaction_id" to transactionId, "quantity_milli" to quantityMilli, "amount" to amount, "deleted" to deleted,
    )
}
