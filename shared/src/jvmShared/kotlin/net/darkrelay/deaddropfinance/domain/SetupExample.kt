package net.darkrelay.deaddropfinance.domain

/**
 * Pulls the invented example setup file out of docs/IMPORT.md's first fenced ```json block (Export example, on the
 * Import setup screen pulls its text from the doc the same way, so there's only ever one copy of the example).
 */
object SetupExample {
    /** The example JSON, or null if the doc doesn't have a fenced ```json block. */
    fun extract(doc: String): String? {
        if ("```json" !in doc) return null
        val after = doc.substringAfter("```json")
        if ("```" !in after) return null
        return after.substringBefore("```").trim()
    }
}
