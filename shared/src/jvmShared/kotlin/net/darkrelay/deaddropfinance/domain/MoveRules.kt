package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.MoveKind

/**
 * Which moves can be edited and deleted (1.8.1, program 0.14.0): the ones made by hand, Move money, Assign and Cover
 * from Rollover. The ones the budget makes itself stay as they are: a payday's Rollover sweep and auto-fill (unmarking
 * the payday undoes them, so they go with it), and an ATM withdrawal's move into Cash (it goes with its withdrawal,
 * which is edited or deleted as a transaction).
 */
object MoveRules {
    const val NOT_EDITABLE = "This move was made by a payday or an ATM withdrawal. It changes with them, not by itself."

    fun editable(m: MoveEntity): Boolean =
        !m.deleted && m.kind in setOf(MoveKind.MOVE, MoveKind.ASSIGN, MoveKind.COVER) && m.paydayTransactionId == null && m.linkedTransactionId == null
}
