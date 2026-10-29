package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.BillRecurrence
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.RuleAction
import net.darkrelay.deaddropfinance.data.SubscriptionEntity
import net.darkrelay.deaddropfinance.data.SubscriptionStatus
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The JSON setup importer (docs/IMPORT.md): envelopes, bills, the next payday, rules, subscriptions and settings
 * from a file. Never transactions. Items are matched by name (a rule by its payee words): a new name is added, a known
 * one is updated with the fields the file gives (the rest stay). Nothing is ever deleted. A file with any error is
 * refused whole, with every problem named, and nothing is applied.
 */
object SetupImport {
    const val FORMAT = "deaddropfinance-setup"

    data class EnvelopeIn(
        val name: String, val mode: EnvelopeMode?, val target: Long?, val perPayday: Long?, val goal: Long?, val goalDate: LocalDate?,
        val everyday: Boolean?, val owed: Long?, val requiredPayment: Long?, val dueDate: LocalDate?, val remind: Boolean?,
        val remindDays: Int?, val order: Int?, val given: Set<String>,
        /** The categories it claims (names), or null when the file doesn't say. */
        val categories: List<String>? = null,
    )

    data class BillIn(
        val name: String, val amount: Long?, val due: LocalDate?, val repeats: BillRecurrence?, val envelope: String?,
        val autopay: Boolean?, val payeeWords: String?, val anyAmount: Boolean?, val amountChanges: Boolean?, val reminder: Boolean?,
        val reminderDays: Int?, val given: Set<String>,
    )

    /** [splits]: envelope names (or [READY]) and their shares. */
    data class RuleIn(
        val match: String, val account: String?, val direction: Int?, val min: Long?, val max: Long?, val transfer: Boolean,
        val splits: List<Pair<String, Long>>, val alwaysAsk: Boolean?, val bill: String?, val given: Set<String>,
        /** The category a match gets (a name; money out only), or null. */
        val category: String? = null,
    )

    /** An account already in the app (matched by name): only whether Activity shows a tab for it (1.2.0). */
    data class AccountIn(val name: String, val showInActivity: Boolean?, val given: Set<String>)

    data class SubscriptionIn(val name: String, val payees: List<String>, val status: SubscriptionStatus?, val bill: String?, val given: Set<String>)

    /** Only what the file sets; null is "leave it". [parkingLotWait] is in days. */
    data class SettingsIn(
        val autoApprove: Boolean? = null,
        val alertSpending: Boolean? = null, val alertLowOrOverspent: Boolean? = null, val alertSafeToSpendNegative: Boolean? = null,
        val alertAmountsOnLockScreen: Boolean? = null, val alertBillReminders: Boolean? = null, val alertAutopayWarnings: Boolean? = null,
        val appLock: Boolean? = null, val lockAfterMinutes: Int? = null, val hideScreen: Boolean? = null,
        val checkForUpdatesAtStartup: Boolean? = null, val parkingLotWait: Int? = null,
    ) {
        /** The settings the file changes, by their file names. */
        fun named(): List<String> = listOfNotNull(
            autoApprove?.let { "autoApprove" },
            alertSpending?.let { "alerts.spending" }, alertLowOrOverspent?.let { "alerts.lowOrOverspent" },
            alertSafeToSpendNegative?.let { "alerts.safeToSpendNegative" }, alertAmountsOnLockScreen?.let { "alerts.amountsOnLockScreen" },
            alertBillReminders?.let { "alerts.billReminders" }, alertAutopayWarnings?.let { "alerts.autopayWarnings" },
            appLock?.let { "appLock" }, lockAfterMinutes?.let { "lockAfterMinutes" }, hideScreen?.let { "hideScreen" },
            checkForUpdatesAtStartup?.let { "checkForUpdatesAtStartup" }, parkingLotWait?.let { "parkingLotWait" },
        )
    }

    data class Plan(
        val envelopes: List<EnvelopeIn> = emptyList(),
        val bills: List<BillIn> = emptyList(),
        val nextPayday: LocalDate? = null,
        val rules: List<RuleIn> = emptyList(),
        val subscriptions: List<SubscriptionIn> = emptyList(),
        val settings: SettingsIn? = null,
        val accounts: List<AccountIn> = emptyList(),
    )

    sealed interface Parsed {
        data class Ok(val plan: Plan) : Parsed
        data class Bad(val errors: List<String>) : Parsed
    }

    /** Ready to Assign as a rule's destination (money in only). */
    const val READY = "Ready to assign"

    val MODES = mapOf(
        "fill_to_target" to EnvelopeMode.FILL_TO_TARGET, "add_every_payday" to EnvelopeMode.ADD_EVERY_PAYDAY, "goal" to EnvelopeMode.GOAL,
        "seasonal" to EnvelopeMode.SEASONAL, "debt" to EnvelopeMode.DEBT,
    )
    val REPEATS = mapOf(
        "monthly" to BillRecurrence.MONTHLY, "every_3_months" to BillRecurrence.QUARTERLY, "every_6_months" to BillRecurrence.SEMIANNUAL,
        "yearly" to BillRecurrence.YEARLY, "one_off" to BillRecurrence.NONE,
    )
    val DIRECTIONS = mapOf("out" to -1, "in" to 1, "either" to 0)
    val STATUSES = mapOf("new" to SubscriptionStatus.NEW, "confirmed" to SubscriptionStatus.CONFIRMED, "not_a_subscription" to SubscriptionStatus.DISMISSED)
    val WAITS = mapOf("1 day" to 1, "2 days" to 2, "1 week" to 7)

    private val TOP = setOf("format", "version", "accounts", "envelopes", "bills", "paydays", "rules", "subscriptions", "settings")
    private val ACCOUNT_KEYS = setOf("name", "showInActivity")
    private val ENVELOPE_KEYS = setOf("name", "mode", "target", "perPayday", "goal", "goalDate", "everyday", "owed", "requiredPayment", "dueDate", "remind", "remindDays", "order", "categories")
    private val BILL_KEYS = setOf("name", "amount", "due", "repeats", "envelope", "autopay", "payeeWords", "anyAmount", "amountChanges", "reminder", "reminderDays")
    private val RULE_KEYS = setOf("match", "account", "direction", "min", "max", "envelope", "splits", "transfer", "alwaysAsk", "bill", "category")
    private val SUB_KEYS = setOf("name", "payees", "status", "bill")
    private val ALERT_KEYS = setOf("spending", "lowOrOverspent", "safeToSpendNegative", "amountsOnLockScreen", "billReminders", "autopayWarnings")
    private val SETTING_KEYS = setOf(
        "autoApprove", "alerts", "appLock", "lockAfterMinutes", "hideScreen", "checkForUpdatesAtStartup", "parkingLotWait",
    )
    private val LOCK_MINUTES = setOf(0, 1, 5, 15, 30)

    /** Reads one object's fields, collecting every problem with where it is. */
    private class Reader(val o: JSONObject, val where: String, val errors: MutableList<String>, allowed: Set<String>) {
        val given: Set<String> = o.keys().asSequence().toSet()

        init {
            for (k in given - allowed) errors += "$where: \"$k\" isn't a field here."
        }

        fun has(k: String) = o.has(k) && !o.isNull(k)

        fun text(k: String, required: Boolean = false): String? {
            if (!has(k)) { if (required) errors += "$where: \"$k\" is missing."; return null }
            val v = o.get(k)
            if (v !is String || v.isBlank()) { errors += "$where: \"$k\" must be text."; return null }
            return v.trim()
        }

        fun amount(k: String): Long? {
            if (!has(k)) return null
            val v = o.get(k)
            val cents = when (v) {
                is String -> Money.parse(v)
                is Number -> Money.parse(v.toString())
                else -> null
            }
            if (cents == null || cents < 0) { errors += "$where: \"$k\" must be an amount like \"12.34\"."; return null }
            return cents
        }

        fun date(k: String): LocalDate? {
            if (!has(k)) return null
            val v = o.get(k)
            val d = (v as? String)?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
            if (d == null) errors += "$where: \"$k\" must be a date like \"2030-05-17\"."
            return d
        }

        fun bool(k: String): Boolean? {
            if (!has(k)) return null
            val v = o.get(k)
            if (v !is Boolean) { errors += "$where: \"$k\" must be true or false."; return null }
            return v
        }

        fun int(k: String, allowed: Iterable<Int>, what: String): Int? {
            if (!has(k)) return null
            val v = o.get(k)
            val n = (v as? Int) ?: (v as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            if (n == null || n !in allowed) { errors += "$where: \"$k\" must be $what."; return null }
            return n
        }

        fun <T> choice(k: String, options: Map<String, T>): T? {
            if (!has(k)) return null
            val v = (o.get(k) as? String)?.trim()?.lowercase()
            val t = v?.let { options[it] }
            if (t == null) errors += "$where: \"$k\" must be one of ${options.keys.joinToString(", ") { "\"$it\"" }}."
            return t
        }
    }

    private fun objects(root: JSONObject, k: String, errors: MutableList<String>): List<JSONObject> {
        if (!root.has(k) || root.isNull(k)) return emptyList()
        val a = root.opt(k) as? JSONArray ?: run { errors += "\"$k\" must be a list ([ ... ])."; return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            (a.opt(i) as? JSONObject) ?: run { errors += "$k[${i + 1}]: each entry must be an object ({ ... })."; null }
        }
    }

    private fun label(kind: String, i: Int, o: JSONObject) = "$kind[${i + 1}]" + ((o.opt("name") ?: o.opt("match")) as? String)?.let { " (\"$it\")" }.orEmpty()

    fun parse(text: String): Parsed {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return Parsed.Bad(listOf("This isn't a JSON file the importer can read: ${e.message?.substringBefore(" at character") ?: "bad JSON"}."))
        }
        val errors = mutableListOf<String>()
        if (root.has("transactions")) errors += "\"transactions\": the importer never imports transactions. Take them out of the file (CSV import brings transactions in)."
        for (k in root.keys().asSequence().toList() - TOP - "transactions") errors += "\"$k\" isn't a part of a setup file."
        if (root.optString("format") != FORMAT) errors += "\"format\" must be \"$FORMAT\"."
        if (root.opt("version") != 1) errors += "\"version\" must be 1."

        val accounts = objects(root, "accounts", errors).mapIndexed { i, o ->
            val r = Reader(o, label("accounts", i, o), errors, ACCOUNT_KEYS)
            AccountIn(r.text("name", required = true).orEmpty(), r.bool("showInActivity"), r.given)
        }
        val envelopes = objects(root, "envelopes", errors).mapIndexed { i, o ->
            val r = Reader(o, label("envelopes", i, o), errors, ENVELOPE_KEYS)
            val name = r.text("name", required = true)
            if (name != null && (name.equals("Rollover", true) || name.equals("Cash", true))) errors += "${r.where}: $name is made by the app and can't be imported."
            var claimed: List<String>? = null
            if (r.has("categories")) {
                val a = o.opt("categories") as? JSONArray
                if (a == null) errors += "${r.where}: \"categories\" must be a list of category names."
                else {
                    val names = mutableListOf<String>()
                    for (j in 0 until a.length()) {
                        val c = (a.opt(j) as? String)?.trim()?.takeIf { it.isNotEmpty() }
                        if (c == null) errors += "${r.where}: categories[${j + 1}] must be a category name." else names += c
                    }
                    if (names.map { it.lowercase() }.distinct().size != names.size) errors += "${r.where}: a category is in \"categories\" twice."
                    claimed = names
                }
            }
            EnvelopeIn(
                name.orEmpty(), r.choice("mode", MODES), r.amount("target"), r.amount("perPayday"), r.amount("goal"), r.date("goalDate"),
                r.bool("everyday"), r.amount("owed"), r.amount("requiredPayment"), r.date("dueDate"), r.bool("remind"),
                r.int("remindDays", 0..30, "a number of days from 0 to 30"), r.int("order", 1..9999, "a position from 1 up"), r.given, claimed,
            )
        }
        val bills = objects(root, "bills", errors).mapIndexed { i, o ->
            val r = Reader(o, label("bills", i, o), errors, BILL_KEYS)
            BillIn(
                r.text("name", required = true).orEmpty(), r.amount("amount"), r.date("due"), r.choice("repeats", REPEATS), r.text("envelope"),
                r.bool("autopay"), r.text("payeeWords"), r.bool("anyAmount"), r.bool("amountChanges"), r.bool("reminder"),
                r.int("reminderDays", 0..30, "a number of days from 0 to 30"), r.given,
            )
        }
        var nextPayday: LocalDate? = null
        if (root.has("paydays") && !root.isNull("paydays")) {
            val p = root.opt("paydays") as? JSONObject
            if (p == null) errors += "\"paydays\" must be an object ({ \"next\": \"2030-05-14\" })."
            else nextPayday = Reader(p, "paydays", errors, setOf("next")).date("next")
        }
        val rules = objects(root, "rules", errors).mapIndexed { i, o ->
            val r = Reader(o, label("rules", i, o), errors, RULE_KEYS)
            val transfer = r.bool("transfer") == true
            val splits = mutableListOf<Pair<String, Long>>()
            r.text("envelope")?.let { splits += it to 1L }
            if (r.has("splits")) {
                val a = o.opt("splits") as? JSONArray
                if (a == null) errors += "${r.where}: \"splits\" must be a list."
                else for (j in 0 until a.length()) {
                    val so = a.opt(j) as? JSONObject
                    if (so == null) { errors += "${r.where}: splits[${j + 1}] must be an object."; continue }
                    val sr = Reader(so, "${r.where} splits[${j + 1}]", errors, setOf("envelope", "share"))
                    val env = sr.text("envelope", required = true)
                    val share = sr.int("share", 1..1_000_000, "a whole number from 1 up")
                    if (env != null && share != null) splits += env to share.toLong()
                }
            }
            val category = r.text("category")
            if (transfer && splits.isNotEmpty()) errors += "${r.where}: a transfer rule has no envelope."
            if (transfer && category != null) errors += "${r.where}: a transfer rule has no category."
            if (!transfer && splits.isEmpty() && category == null) errors += "${r.where}: give an \"envelope\", \"splits\", a \"category\", or \"transfer\": true."
            if (r.has("envelope") && r.has("splits")) errors += "${r.where}: give \"envelope\" or \"splits\", not both."
            val min = r.amount("min")
            val max = r.amount("max")
            if (min != null && max != null && min > max) errors += "${r.where}: \"min\" is above \"max\"."
            val match = r.text("match", required = true)
            if (match != null && Payees.words(match).isEmpty()) errors += "${r.where}: \"match\" needs words from the payee's name."
            val direction = r.choice("direction", DIRECTIONS)
            if (category != null && direction != null && direction != -1) errors += "${r.where}: a category is for money out (\"direction\": \"out\")."
            RuleIn(match.orEmpty(), r.text("account"), direction, min, max, transfer, splits, r.bool("alwaysAsk"), r.text("bill"), r.given, category)
        }
        val subs = objects(root, "subscriptions", errors).mapIndexed { i, o ->
            val r = Reader(o, label("subscriptions", i, o), errors, SUB_KEYS)
            val payees = mutableListOf<String>()
            if (r.has("payees")) {
                val a = o.opt("payees") as? JSONArray
                if (a == null) errors += "${r.where}: \"payees\" must be a list of payee names."
                else for (j in 0 until a.length()) {
                    val p = (a.opt(j) as? String)?.let { Payees.clean(it) }?.takeIf { it.isNotBlank() }
                    if (p == null) errors += "${r.where}: payees[${j + 1}] must be a payee name." else payees += p
                }
            }
            SubscriptionIn(r.text("name", required = true).orEmpty(), payees, r.choice("status", STATUSES), r.text("bill"), r.given)
        }
        var settings: SettingsIn? = null
        if (root.has("settings") && !root.isNull("settings")) {
            val so = root.opt("settings") as? JSONObject
            if (so == null) errors += "\"settings\" must be an object ({ ... })."
            else {
                val r = Reader(so, "settings", errors, SETTING_KEYS)
                var alerts = JSONObject()
                if (r.has("alerts")) alerts = so.opt("alerts") as? JSONObject ?: run { errors += "settings: \"alerts\" must be an object."; JSONObject() }
                val ar = Reader(alerts, "settings.alerts", errors, ALERT_KEYS)
                settings = SettingsIn(
                    r.bool("autoApprove"),
                    ar.bool("spending"), ar.bool("lowOrOverspent"), ar.bool("safeToSpendNegative"), ar.bool("amountsOnLockScreen"),
                    ar.bool("billReminders"), ar.bool("autopayWarnings"),
                    r.bool("appLock"), r.int("lockAfterMinutes", LOCK_MINUTES, "0, 1, 5, 15 or 30"), r.bool("hideScreen"),
                    r.bool("checkForUpdatesAtStartup"), r.choice("parkingLotWait", WAITS),
                )
            }
        }
        // the same name twice in one file is a mistake, not two items
        fun dupes(kind: String, names: List<String>) {
            names.filter { it.isNotBlank() }.groupBy { it.lowercase() }.filterValues { it.size > 1 }.keys.forEach { errors += "$kind: \"$it\" is in the file more than once." }
        }
        dupes("accounts", accounts.map { it.name })
        dupes("envelopes", envelopes.map { it.name })
        dupes("bills", bills.map { it.name })
        dupes("rules", rules.map { Payees.clean(it.match) })
        dupes("subscriptions", subs.map { it.name })
        return if (errors.isEmpty()) Parsed.Ok(Plan(envelopes, bills, nextPayday, rules, subs, settings, accounts)) else Parsed.Bad(errors)
    }
}

/**
 * Checks a parsed setup file against what's in the app (the names it refers to), shows what will be added and
 * updated, and applies it. Applying adds and updates only; nothing is ever deleted.
 */
class SetupImporter(
    private val store: BudgetStore,
    private val actions: BudgetActions = BudgetActions(store),
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val dao get() = store.dao
    private val bills = BillActions(store, actions, today)
    private val sorting = Sorting(store, actions)

    /** What applying will do: per kind ("Envelopes", "Bills", ...), the names added and the names updated. */
    data class Preview(val add: Map<String, List<String>>, val update: Map<String, List<String>>, val settings: List<String>, val nextPayday: LocalDate?) {
        val addCount get() = add.values.sumOf { it.size }
        val updateCount get() = update.values.sumOf { it.size }
    }

    sealed interface Checked {
        data class Ok(val preview: Preview) : Checked
        data class Bad(val errors: List<String>) : Checked
    }

    private fun SetupImport.EnvelopeIn.key() = name.lowercase()

    /** Everything the file refers to must be in the file or the app; each envelope must be complete once merged. */
    suspend fun check(plan: SetupImport.Plan): Checked {
        val errors = mutableListOf<String>()
        val envs = dao.envelopesNow().filter { !it.deleted }
        val known = (envs.map { it.name.lowercase() } + plan.envelopes.map { it.key() }).toSet()
        for (e in plan.envelopes) {
            val old = envs.firstOrNull { it.name.equals(e.name, true) && !it.isSystem }
            if (envs.any { it.name.equals(e.name, true) && it.isSystem }) errors += "envelopes (\"${e.name}\"): that envelope is made by the app and can't be imported."
            val mode = e.mode ?: old?.mode
            if (mode == null) errors += "envelopes (\"${e.name}\"): a new envelope needs a \"mode\"."
            if (mode == EnvelopeMode.GOAL && (e.goal ?: old?.goalAmount) == null) errors += "envelopes (\"${e.name}\"): a goal envelope needs \"goal\" and \"goalDate\"."
            if (mode == EnvelopeMode.GOAL && (e.goalDate ?: old?.goalDate) == null && (e.goal ?: old?.goalAmount) != null) errors += "envelopes (\"${e.name}\"): a goal envelope needs \"goalDate\"."
        }
        // categories (1.1.0): an envelope's claims can't take a category another envelope keeps, or two file envelopes' claim
        val catsHere = dao.categoriesNow()
        val inFileClaiming = plan.envelopes.filter { it.categories != null }.map { it.key() }.toSet()
        val claimedInFile = mutableMapOf<String, String>()
        for (e in plan.envelopes) {
            val list = e.categories ?: continue
            val old = envs.firstOrNull { it.name.equals(e.name, true) }
            if (old?.unplanned == true && list.isNotEmpty()) errors += "envelopes (\"${e.name}\"): Unplanned takes every category no envelope claims, so it can't claim any."
            for (c in list) {
                claimedInFile[c.lowercase()]?.let { errors += "categories: \"$c\" is claimed by both \"$it\" and \"${e.name}\" in the file." }
                claimedInFile[c.lowercase()] = e.name
                val holder = catsHere.firstOrNull { it.name.equals(c, true) }?.envelopeId?.let { id -> envs.firstOrNull { it.id == id } }
                if (holder != null && !holder.name.equals(e.name, true) && holder.name.lowercase() !in inFileClaiming) {
                    errors += "envelopes (\"${e.name}\"): the category \"$c\" is already claimed by \"${holder.name}\"."
                }
            }
        }
        val billsHere = dao.billsNow().filter { !it.deleted }
        val billNames = (billsHere.map { it.name.lowercase() } + plan.bills.map { it.name.lowercase() }).toSet()
        for (b in plan.bills) {
            val old = billsHere.firstOrNull { it.name.equals(b.name, true) }
            if (old == null && b.amount == null) errors += "bills (\"${b.name}\"): a new bill needs an \"amount\"."
            if (old == null && b.due == null) errors += "bills (\"${b.name}\"): a new bill needs a \"due\" date."
            b.envelope?.let { if (it.lowercase() !in known) errors += "bills (\"${b.name}\"): there's no envelope called \"$it\" in the file or the app." }
        }
        val accounts = dao.accountsNow().filter { !it.deleted }
        // accounts are never made by the importer: the file can only say whether a known one has its Activity tab
        for (a in plan.accounts) {
            if (accounts.none { it.name.equals(a.name, true) }) errors += "accounts (\"${a.name}\"): there's no account called \"${a.name}\" in the app. The importer doesn't make accounts."
        }
        for (r in plan.rules) {
            r.account?.let { a -> if (accounts.none { it.name.equals(a, true) }) errors += "rules (\"${r.match}\"): there's no account called \"$a\" in the app." }
            r.bill?.let { if (it.lowercase() !in billNames) errors += "rules (\"${r.match}\"): there's no bill called \"$it\" in the file or the app." }
            val direction = r.direction ?: -1
            if (r.category != null && direction != -1) errors += "rules (\"${r.match}\"): a category is for money out."
            for ((env, _) in r.splits) {
                if (env.equals(SetupImport.READY, true)) {
                    if (direction <= 0) errors += "rules (\"${r.match}\"): only a rule for money in (\"direction\": \"in\") can go to $env."
                } else if (env.lowercase() !in known) errors += "rules (\"${r.match}\"): there's no envelope called \"$env\" in the file or the app."
            }
            if (r.splits.map { it.first.lowercase() }.distinct().size != r.splits.size) errors += "rules (\"${r.match}\"): an envelope is in the split twice."
        }
        val subsHere = dao.subscriptionsNow().filter { !it.deleted }
        for (s in plan.subscriptions) {
            val old = subsHere.firstOrNull { it.name.equals(s.name, true) }
            if (old == null && s.payees.isEmpty()) errors += "subscriptions (\"${s.name}\"): a new subscription needs \"payees\"."
            s.bill?.let { if (it.lowercase() !in billNames) errors += "subscriptions (\"${s.name}\"): there's no bill called \"$it\" in the file or the app." }
        }
        if (errors.isNotEmpty()) return Checked.Bad(errors)
        val add = linkedMapOf<String, List<String>>()
        val update = linkedMapOf<String, List<String>>()
        fun put(kind: String, names: List<Pair<String, Boolean>>) {
            names.filter { !it.second }.map { it.first }.takeIf { it.isNotEmpty() }?.let { add[kind] = it }
            names.filter { it.second }.map { it.first }.takeIf { it.isNotEmpty() }?.let { update[kind] = it }
        }
        val wanted = (plan.envelopes.flatMap { it.categories.orEmpty() } + plan.rules.mapNotNull { it.category }).distinct()
        put("Categories", wanted.map { c -> c to catsHere.any { it.name.equals(c, true) } }.filter { !it.second })
        put("Accounts", plan.accounts.filter { "showInActivity" in it.given }.map { it.name to true })
        put("Envelopes", plan.envelopes.map { e -> e.name to envs.any { it.name.equals(e.name, true) } })
        put("Bills", plan.bills.map { b -> b.name to billsHere.any { it.name.equals(b.name, true) } })
        val rulesHere = dao.rulesNow().filter { !it.deleted }
        put("Rules", plan.rules.map { r -> r.match to rulesHere.any { it.matchText == Payees.clean(r.match) } })
        put("Subscriptions", plan.subscriptions.map { s -> s.name to subsHere.any { it.name.equals(s.name, true) } })
        return Checked.Ok(Preview(add, update, plan.settings?.named().orEmpty(), plan.nextPayday))
    }

    /**
     * Applies a checked plan: envelopes (then their fill order), bills, the next payday, rules, subscriptions, and the
     * parking lot wait (the other settings live on the phone and are applied by the screen). Returns the preview it
     * applied. Throws BudgetRuleException (with what's wrong) if the plan no longer fits.
     */
    suspend fun apply(plan: SetupImport.Plan): Preview {
        val preview = (check(plan) as? Checked.Ok)?.preview ?: throw BudgetRuleException((check(plan) as Checked.Bad).errors.first())
        // accounts: only Show in Activity
        for (a in plan.accounts) {
            val show = a.showInActivity ?: continue
            val old = dao.accountsNow().firstOrNull { !it.deleted && it.name.equals(a.name, true) } ?: continue
            actions.saveAccount(old.id, old.name, old.type, old.startingBalance, old.startDate, showInActivity = show)
        }
        // categories the file names that don't exist yet are created
        val categoryActions = CategoryActions(store)
        for (c in (plan.envelopes.flatMap { it.categories.orEmpty() } + plan.rules.mapNotNull { it.category }).distinct()) {
            if (dao.categoriesNow().none { it.name.equals(c, true) }) categoryActions.add(c)
        }
        suspend fun categoryId(name: String) = dao.categoriesNow().first { it.name.equals(name, true) }.id
        // envelopes
        for (e in plan.envelopes) {
            val old = dao.envelopesNow().firstOrNull { !it.deleted && it.name.equals(e.name, true) }
            val mode = e.mode ?: old!!.mode
            fun <T> pick(k: String, v: T?, keep: T?): T? = if (k in e.given) v else keep
            actions.saveEnvelope(
                old?.id,
                BudgetActions.EnvelopeDraft(
                    name = e.name, mode = mode, target = pick("target", e.target, old?.target), perPayday = pick("perPayday", e.perPayday, old?.perPayday),
                    goalAmount = pick("goal", e.goal, old?.goalAmount), goalDate = pick("goalDate", e.goalDate, old?.goalDate),
                    debtOwed = pick("owed", e.owed, old?.debtOwed), requiredPayment = pick("requiredPayment", e.requiredPayment, old?.requiredPayment),
                    dueDate = pick("dueDate", e.dueDate, old?.dueDate), everyday = e.everyday, reminder = e.remind, reminderDays = e.remindDays,
                ),
                today(),
            )
        }
        // each envelope's claims: first release what the file takes away, then claim what it lists
        val claimers = plan.envelopes.filter { it.categories != null }
        for (pass in 1..2) {
            for (e in claimers) {
                val id = dao.envelopesNow().firstOrNull { !it.deleted && it.name.equals(e.name, true) }?.id ?: continue
                val want = e.categories!!.map { categoryId(it) }.toSet()
                val now = if (pass == 1) dao.categoriesNow().filter { it.envelopeId == id }.map { it.id }.toSet().intersect(want) else want
                categoryActions.setClaims(id, now)
            }
        }
        val ordered = plan.envelopes.filter { it.order != null }.sortedBy { it.order }
        if (ordered.isNotEmpty()) {
            val envs = dao.envelopesNow().filter { !it.deleted && !it.archived }.sortedBy { it.sortOrder }
            val roll = envs.filter { it.mode == EnvelopeMode.ROLLOVER }
            val placed = ordered.mapNotNull { o -> envs.firstOrNull { it.name.equals(o.name, true) }?.let { it to o.order!! } }
            val rest = (envs - roll.toSet() - placed.map { it.first }.toSet()).toMutableList()
            for ((env, at) in placed) rest.add((at - 1).coerceIn(0, rest.size), env)
            actions.reorderEnvelopes((roll + rest).map { it.id })
        }
        suspend fun envId(name: String) = dao.envelopesNow().firstOrNull { !it.deleted && it.name.equals(name, true) }?.id
        // bills
        for (b in plan.bills) {
            val old = dao.billsNow().firstOrNull { !it.deleted && it.name.equals(b.name, true) }
            fun <T> pick(k: String, v: T?, keep: T?): T? = if (k in b.given) v else keep
            bills.saveBill(
                old?.id,
                BillActions.BillDraft(
                    name = b.name, amount = pick("amount", b.amount, old?.amountDue)!!, due = pick("due", b.due, old?.dueDate),
                    recurrence = pick("repeats", b.repeats, old?.recurrence) ?: BillRecurrence.MONTHLY,
                    envelopeId = if ("envelope" in b.given) b.envelope?.let { envId(it) } else old?.envelopeId,
                    autopay = pick("autopay", b.autopay, old?.autopay) ?: false, reminder = pick("reminder", b.reminder, old?.reminder) ?: false,
                    reminderDays = pick("reminderDays", b.reminderDays, old?.reminderDays) ?: 2,
                    matchText = if ("payeeWords" in b.given) b.payeeWords else old?.matchText,
                    anyAmount = pick("anyAmount", b.anyAmount, old?.anyAmount) ?: false, varies = pick("amountChanges", b.amountChanges, old?.varies) ?: false,
                ),
            )
        }
        plan.nextPayday?.let { bills.correctPayday(it) }
        suspend fun billId(name: String) = dao.billsNow().firstOrNull { !it.deleted && it.name.equals(name, true) }?.id
        // rules
        val accounts = dao.accountsNow().filter { !it.deleted }
        for (r in plan.rules) {
            val old = dao.rulesNow().firstOrNull { !it.deleted && it.matchText == Payees.clean(r.match) }
            fun <T> pick(k: String, v: T?, keep: T?): T? = if (k in r.given) v else keep
            val splits = r.splits.map { (env, share) -> Rules.Split(if (env.equals(SetupImport.READY, true)) Rules.RTA else envId(env)!!, share) }
            val hasDest = "envelope" in r.given || "splits" in r.given || "transfer" in r.given || "category" in r.given
            sorting.saveRule(
                old?.id,
                Sorting.RuleDraft(
                    matchText = r.match,
                    accountId = if ("account" in r.given) r.account?.let { a -> accounts.firstOrNull { it.name.equals(a, true) }?.id } else old?.accountId,
                    direction = pick("direction", r.direction, old?.direction) ?: -1,
                    minAmount = pick("min", r.min, old?.minAmount), maxAmount = pick("max", r.max, old?.maxAmount),
                    action = if (hasDest) (if (r.transfer) RuleAction.TRANSFER else RuleAction.ENVELOPE) else old?.action ?: RuleAction.ENVELOPE,
                    splits = if (hasDest) splits else old?.let { Rules.splits(it) }.orEmpty(),
                    alwaysAsk = pick("alwaysAsk", r.alwaysAsk, old?.alwaysAsk) ?: false,
                    billId = if ("bill" in r.given) r.bill?.let { billId(it) } else old?.billId,
                    categoryId = if ("category" in r.given) r.category?.let { categoryId(it) } else if (hasDest) null else old?.categoryId,
                ),
            )
        }
        // subscriptions
        for (s in plan.subscriptions) {
            val subBill = s.bill?.let { billId(it) }
            store.write {
                val old = dao.subscriptionsNow().firstOrNull { !it.deleted && it.name.equals(s.name, true) }
                val base = old ?: SubscriptionEntity(matchKey = "", createdAt = now, updatedAt = now)
                val keys = if ("payees" in s.given) s.payees.distinct() else base.keys
                put(
                    old,
                    base.copy(
                        name = s.name, matchKey = keys.joinToString(";"), status = s.status ?: old?.status ?: SubscriptionStatus.CONFIRMED,
                        billId = if ("bill" in s.given) subBill else old?.billId, updatedAt = now,
                    ),
                )
            }
        }
        plan.settings?.parkingLotWait?.let { ParkedBuys(store, actions, today).setDefaultWait(it) }
        return preview
    }
}
