package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.data.TransactionLineEntity

/** Synthetic budget rows for tests: invented names, round numbers, nothing real. */
object F {
    val D0: LocalDate = LocalDate.of(2030, 1, 1)

    fun account(id: String, start: Long = 0, type: AccountType = AccountType.BANK) =
        AccountEntity(id = id, name = "Account $id", type = type, startingBalance = start, startDate = D0, createdAt = 0, updatedAt = 0)

    fun envelope(id: String, mode: EnvelopeMode = EnvelopeMode.FILL_TO_TARGET, order: Int = 1, target: Long? = null, perPayday: Long? = null) =
        EnvelopeEntity(id = id, name = "Envelope $id", mode = mode, target = target, perPayday = perPayday, sortOrder = order, createdAt = 0, updatedAt = 0)

    fun rollover() = EnvelopeEntity(id = "roll", name = "Rollover", mode = EnvelopeMode.ROLLOVER, sortOrder = 0, createdAt = 0, updatedAt = 0)

    fun tx(id: String, account: String, amount: Long, date: LocalDate = D0, payee: String = "Payee $id", payday: Boolean = false, wash: String? = null) =
        TransactionEntity(id = id, accountId = account, date = date, payee = payee, amount = amount, isPayday = payday, washGroupId = wash, createdAt = 0, updatedAt = 0)

    fun transfer(id: String, from: String, to: String, amount: Long) =
        TransactionEntity(id = id, accountId = from, kind = TransactionKind.TRANSFER, transferAccountId = to, date = D0, amount = amount, createdAt = 0, updatedAt = 0)

    fun line(tx: String, amount: Long, envelope: String? = null, rta: Boolean = false) = TransactionLineEntity(
        transactionId = tx, bucket = if (envelope != null) LineBucket.ENVELOPE else if (rta) LineBucket.READY_TO_ASSIGN else LineBucket.UNSORTED,
        envelopeId = envelope, amount = amount, createdAt = 0, updatedAt = 0,
    )

    fun move(from: String?, to: String?, amount: Long, kind: MoveKind = MoveKind.MOVE) =
        MoveEntity(fromEnvelopeId = from, toEnvelopeId = to, amount = amount, date = D0, kind = kind, createdAt = 0, updatedAt = 0)
}
