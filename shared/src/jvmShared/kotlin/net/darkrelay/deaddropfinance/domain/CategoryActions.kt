package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.CategoryEntity

/**
 * Categories (1.1.0): add, rename, delete (moving what used the category to another), and which envelope claims
 * which. A category is claimed by at most one envelope, so a claim is one field on the category.
 */
class CategoryActions(private val store: BudgetStore) {

    private val dao get() = store.dao

    private fun clean(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) throw BudgetRuleException("Give the category a name.")
        return n
    }

    suspend fun add(name: String): String {
        val n = clean(name)
        return store.write {
            val all = dao.categoriesNow()
            if (all.any { it.name.equals(n, ignoreCase = true) }) throw BudgetRuleException("There is already a category named $n.")
            put(null, CategoryEntity(name = n, sortOrder = (all.maxOfOrNull { it.sortOrder } ?: -1) + 1, createdAt = now, updatedAt = now)).id
        }
    }

    suspend fun rename(id: String, name: String) {
        val n = clean(name)
        store.write {
            val c = dao.category(id)?.takeIf { !it.deleted } ?: throw BudgetRuleException("That category is gone.")
            if (dao.categoriesNow().any { it.id != id && it.name.equals(n, ignoreCase = true) }) throw BudgetRuleException("There is already a category named $n.")
            put(c, c.copy(name = n, updatedAt = now))
        }
    }

    /** How many purchases (lines) and rules use the category: what deleting it has to move. */
    suspend fun usage(id: String): Int = dao.linesNow().count { it.categoryId == id } + dao.rulesNow().count { it.categoryId == id }

    /**
     * Deletes a category. If purchases or rules use it, [moveTo] (another category) says where they go first; their
     * envelopes don't change.
     */
    suspend fun delete(id: String, moveTo: String?) {
        store.write {
            val c = dao.category(id)?.takeIf { !it.deleted } ?: throw BudgetRuleException("That category is gone.")
            val lines = dao.linesNow().filter { it.categoryId == id }
            val rules = dao.rulesNow().filter { it.categoryId == id }
            if (lines.isNotEmpty() || rules.isNotEmpty()) {
                if (moveTo == null) throw BudgetRuleException("Choose a category to move its purchases to.")
                if (moveTo == id) throw BudgetRuleException("Choose a different category to move them to.")
                if (dao.category(moveTo)?.deleted != false) throw BudgetRuleException("That category is gone.")
                lines.forEach { put(it, it.copy(categoryId = moveTo, updatedAt = now)) }
                rules.forEach { put(it, it.copy(categoryId = moveTo, updatedAt = now)) }
            }
            put(c, c.copy(deleted = true, envelopeId = null, updatedAt = now))
        }
    }

    /**
     * Sets exactly which categories [envelopeId] claims. A category another envelope already claims can't also be
     * claimed here (release it there first). Unplanned takes what nothing claims, so it claims nothing itself.
     */
    suspend fun setClaims(envelopeId: String, categoryIds: Set<String>) {
        store.write {
            val env = dao.envelope(envelopeId)?.takeIf { !it.deleted } ?: throw BudgetRuleException("That envelope is gone.")
            if (env.unplanned && categoryIds.isNotEmpty()) throw BudgetRuleException("Unplanned takes every category no envelope claims, so it can't claim any.")
            val envs = dao.envelopesNow().associateBy { it.id }
            val all = dao.categoriesNow()
            for (c in all) {
                if (c.id in categoryIds && c.envelopeId != null && c.envelopeId != envelopeId) {
                    throw BudgetRuleException("${c.name} is already claimed by ${envs[c.envelopeId]?.name ?: "another envelope"}.")
                }
            }
            if (categoryIds.any { id -> all.none { it.id == id } }) throw BudgetRuleException("That category is gone.")
            for (c in all) {
                val want = if (c.id in categoryIds) envelopeId else if (c.envelopeId == envelopeId) null else c.envelopeId
                if (want != c.envelopeId) put(c, c.copy(envelopeId = want, updatedAt = now))
            }
        }
    }
}
