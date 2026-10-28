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
import net.darkrelay.deaddropfinance.data.TransactionLineEntity

/** A synthetic budget for the envelope Activity list (1.2.3): every kind of row in and out of one envelope. Invented numbers. */
object EnvelopeActivityFixture {
    val today: LocalDate = LocalDate.of(2030, 8, 20)
    val start: LocalDate = today.minusDays(30)
    private val d1 = today.minusDays(10)
    private val d2 = today.minusDays(5)

    val acct = AccountEntity(id = "a", name = "Sample Checking", type = AccountType.BANK, startDate = today.minusDays(90), createdAt = 1, updatedAt = 1)
    val gaming = EnvelopeEntity(id = "g", name = "Gaming", mode = EnvelopeMode.FILL_TO_TARGET, target = 40_00, createdAt = 1, updatedAt = 1)
    val food = EnvelopeEntity(id = "f", name = "Food", mode = EnvelopeMode.ADD_EVERY_PAYDAY, perPayday = 50_00, sortOrder = 1, createdAt = 1, updatedAt = 1)
    val rollover = EnvelopeEntity(id = "r", name = "Rollover", mode = EnvelopeMode.ROLLOVER, sortOrder = 2, createdAt = 1, updatedAt = 1)
    val unplanned = EnvelopeEntity(id = "u", name = "Unplanned", mode = EnvelopeMode.FILL_TO_TARGET, unplanned = true, sortOrder = 3, createdAt = 1, updatedAt = 1)
    val onlyTarget = EnvelopeEntity(id = "t", name = "Hobby", mode = EnvelopeMode.FILL_TO_TARGET, target = 25_00, sortOrder = 4, createdAt = 1, updatedAt = 1)
    val onlyAssign = EnvelopeEntity(id = "o", name = "Gifts", mode = EnvelopeMode.FILL_TO_TARGET, target = 30_00, sortOrder = 5, createdAt = 1, updatedAt = 1)

    private fun tx(id: String, d: LocalDate, payee: String, amount: Long, payday: Boolean = false) =
        TransactionEntity(id = id, accountId = "a", date = d, payee = payee, amount = amount, isPayday = payday, createdAt = 1, updatedAt = 1)
    private fun line(id: String, tx: String, amount: Long, bucket: LineBucket, env: String? = null, at: Long = 1) =
        TransactionLineEntity(id = id, transactionId = tx, bucket = bucket, envelopeId = env, amount = amount, createdAt = at, updatedAt = at)
    private fun move(id: String, from: String?, to: String?, amount: Long, kind: MoveKind, d: LocalDate, at: Long) =
        MoveEntity(id = id, fromEnvelopeId = from, toEnvelopeId = to, amount = amount, date = d, kind = kind, createdAt = at, updatedAt = at)

    fun budget(extraMoves: List<MoveEntity> = emptyList()) = Budget(
        listOf(acct), listOf(gaming, food, rollover, unplanned, onlyTarget, onlyAssign),
        listOf(
            tx("p", d1, "SAMPLE PAY", 500_00, payday = true), tx("s1", d2, "SAMPLE GAME STORE", -29_97), tx("s2", d2, "SAMPLE REFUND", 5_00),
            tx("old", today.minusDays(40), "SAMPLE OLD SHOP", -7_00), tx("s3", d2, "SAMPLE SNACKS", -9_99),
        ),
        listOf(
            line("lp", "p", 500_00, LineBucket.READY_TO_ASSIGN), line("l1", "s1", -29_97, LineBucket.ENVELOPE, "g", at = 20),
            line("l2", "s2", 5_00, LineBucket.ENVELOPE, "g", at = 21), line("lo", "old", -7_00, LineBucket.ENVELOPE, "g"),
            line("l3", "s3", -9_99, LineBucket.ENVELOPE, "u", at = 22),
        ),
        listOf(
            move("m1", null, "g", 40_00, MoveKind.ASSIGN, d1, 10), move("m2", null, "g", 10_00, MoveKind.ASSIGN, d1, 11),
            move("m3", "f", "g", 5_00, MoveKind.MOVE, d1, 12), move("m4", "g", "f", 3_00, MoveKind.MOVE, d1, 13),
            move("m5", null, "g", 20_00, MoveKind.AUTOFILL, d1, 14), move("m6", "g", "r", 12_00, MoveKind.SWEEP, d1, 15),
            move("m7", null, "g", 2_00, MoveKind.MOVE, d2, 16), move("m8", "g", null, 1_00, MoveKind.MOVE, d2, 17),
            move("m9", "r", "g", 4_00, MoveKind.COVER, d2, 18), move("m10", null, "o", 30_00, MoveKind.ASSIGN, d1, 19),
        ) + extraMoves,
        budgetStart = start,
    )
}
