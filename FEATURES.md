# Dead Drop Finance: Feature Spec

How the app behaves. Each section names the stage that builds it. Everything runs on the phone. Nothing user-specific ever goes in code, tests or fixtures; tests use synthetic data only.

## Accounts (Stage 6)
- An account's starting balance is its balance as of its start date, so it already includes everything before that date. A transaction dated before the start date (bank history from a sync or a CSV, for example) is kept and shown as history but doesn't change the balance or the budget.
- Delete account (on the account screen): removes the account and all its transactions for good, after a confirmation that names the account, says how many transactions go with it, and says it can't be undone. Envelope balances and Ready to Assign change to match. A transfer with another account keeps that account's side, as an entry in To sort. Any bank link for the account is removed.
- Erase everything and start over (in More): wipes every account, transaction, envelope, bank connection and setting from the phone, after a confirmation that lists what's lost, says there's no backup, and needs the word ERASE typed. The app then starts like a fresh install, at the setup wizard. The last screen explains that a SimpleFIN connection for the app still exists on the Bridge and how to delete it there.
- Archiving an account takes its balance out of Ready to Assign, after a confirmation that says so, as if the account was never in the budget. Its transactions stay as history. Unarchiving puts it back.
- Track only, not in the budget: an account can hold money that must never be budgeted (for example a membership share that has to stay in a savings account). Its balance shows on the account list and syncs normally, but it never counts in Ready to Assign, and its transactions never land in To sort: they're recorded as already handled. Bank sync's link screen offers it as a choice. Moving money between a budget account and a track-only one comes out of, or goes into, Ready to Assign.
- Held amount: any budget account can have a minimum that stays put. Ready to Assign counts the balance minus the held amount, shown on the account as "Held". If the balance drops below the held amount, the account shows a warning and only the actual balance is held, so Ready to Assign never goes lower than the real balance would make it.
- Account types: bank account, payment app balance, and cash.
- Moving money between the user's own accounts is a transfer, never spending or income.
- Money that arrives in a payment app can be assigned straight to an envelope.
- Pass-through money: a matching incoming and outgoing amount (for example money received and sent on to someone else the same day) can be marked as a wash, so it counts as neither income nor spending.

## Payday and Ready to Assign (Stage 6)
- Payday is triggered by the deposit actually arriving, never by a fixed calendar date. The user marks which deposit is their payday.
- The budget month runs payday to payday, not by calendar month. Anything the spec calls a "month" for budgeting (the Rollover sweep, month-to-date totals) means that span.
- New money lands in Ready to Assign.
- Any deposit that is not the payday deposit (a transfer from family, a refund, side money) also goes to Ready to Assign. If the transfer carries a note (for example "Water bill"), the Assign screen shows the note as a hint. The hint never assigns anything by itself.
- Payday auto-fill runs through the envelope list in list order and fills each envelope by its mode. Whatever is left stays in Ready to Assign.
- If the money runs out before the list does, the app shows which envelopes did not fill and by how much. No advice, no suggestions.
- Stage 10: the next payday is predicted from the marked payday deposits (monthly on the usual day of the month, or the usual number of days apart; one payday so far is taken as two weeks and says so). The Bills tab shows it, and Change sets it by hand. It's used by the payday checklist, autopay warnings and the forecast. Payday itself still starts only when the deposit lands.
- A late payday (the date passed, no deposit yet) shows a quiet note, never an alarm; the forecast counts it as tomorrow until it lands.
- A synced deposit that looks like the payday deposit (same payer, within 20% of the last one, around the expected date) shows a Home card: "It's payday" runs it in one tap. A deposit read from a bank notification starts payday by itself (see Live spend alerts).

## Envelopes (Stage 6)
- One flat list. No groups. The user drags envelopes to set the fill order.
- First run offers starter envelopes with names only and no dollar amounts, plus a "Start blank" button. The app never suggests how much to spend.
- Envelope modes:
  - Fill to target: tops the envelope up to its target each payday. Example, an envelope holding 30 with a target of 90:
    - When the payday does not close a budget month (the first payday, before there is a month to close), nothing is swept, so the envelope gets 60 and ends at 90.
    - When the payday closes a budget month, the 30 is swept to Rollover first, then the envelope fills from 0 to 90 out of Ready to Assign. It ends at 90 and Rollover holds the 30. Sweep always comes before fill.
  - Add every payday: adds the full amount each payday, for things being saved up for.
  - Goal: an add-every-payday envelope with a goal amount and date ("200 by December 10"). The app works out the per-payday amount and stops filling at the goal.
  - Seasonal: for bills that swing by season (heating, cooling). Adds the 12-month average of its linked bills every payday, so low months build a cushion and high months spend it down. (Built: once a year of the linked bills' payments is in; until then it adds its own per-payday amount. A rest moved to a Debt envelope isn't counted as what the bill cost.)
  - Debt: see Debt envelopes.
- An envelope can start with no target. After a month or two of history the app offers the real average spent as the target. The user accepts or ignores it.

## Rollover envelope (Stage 6)
- When the payday deposit lands, which closes the budget month, leftovers from Fill to target envelopes are swept into one Rollover envelope. Add every payday, Goal, Seasonal and Debt envelopes are never swept.
- Rollover is a general stash the user can spend on anything. Nothing touches it automatically.
- Rollover is a plain envelope in the envelope list. It is not shown on the home screen, next to Safe to Spend or anywhere else, and it has no callouts, banners or "extra money" prompts.
- When an envelope goes negative and Ready to Assign cannot cover it, the envelope's screen offers a one-tap "Cover from Rollover". The user decides.
- Rollover is excluded from Safe to Spend.

## Cash envelope (Stage 6)
- An ATM withdrawal moves money from the envelope the user picks into a Cash envelope.
- Cash spending is logged by hand and reduces Cash. Unlogged cash just stays in Cash.
- ATM fees are auto-tagged as fees. ATM fee rebates offset them.
- Quick manual entry exists for anything paid in cash, including bills, so a bill paid in cash can be marked paid.

## Debt envelopes (Stage 6, simple version)
- For money owed: past-due catch-up on a bill, a loan, anything.
- Fields: balance owed (optional until known), optional required payment and due date, and an add-every-payday amount.
- With a required payment and due date it reminds like a bill (Stage 10): a Remind me switch on the envelope, off by default, with how many days ahead. Its payment shows in the Bills tab's Coming up and in the forecast.
- The balance goes down with every payment. Extra payments beyond the required amount are allowed.
- Interest, payoff plans and projected payoff dates come later (Stage 24).

## Transactions (Stage 6)
- One transaction can be split across several envelopes (for example one store run that is part groceries, part household; or one bill payment that is part current charge, part past-due debt).
- Pending and posted versions of the same transaction are matched, never double counted.
- Old School CSV import handles bank exports and payment app statement exports, with duplicate detection:
  - Two rows are the same when they have the same account, date, amount in cents and normalized description.
  - Against what the account already holds, matching is counted. If the file has N rows with a key and the account holds M, min(N, M) rows are "already here" and skipped, and the rest are new. Three identical rows in the file with one already in the app imports two.
  - Identical rows inside one file are never skipped automatically, since two real purchases or transfers can look the same. The preview lists them under "Possible duplicates in this file", grouped, with the row numbers. Each row has an import or skip choice, import by default. The counts at the top (to import, already here, possible duplicates, skipped by you, bad) update as the choices change.
  - A row that is the posted version of a pending transaction (same amount, within 5 days) posts that transaction instead of adding a second one.

## Bank sync and setup (Stage 7)
- A setup wizard on first start: app lock, how transactions get in, optional starter envelopes, finish. Installs that already have accounts get a Finish setup card on Home instead. Setup can be rerun from More.
- Two doors: Easy (SimpleFIN Bridge, a paid service the user signs up for and pays directly) and Old School (CSV import, or entering things by hand). Free sync is CSV.
- Every connection runs from the user's phone with the user's own SimpleFIN login. DarkRelay never holds a bank connection.
- The bank's balance is shown with the date it was true (SimpleFIN's balance date). When that date is more than a few days old, the app says so: the provider may have stopped updating the account.
- When a synced account stops syncing (set to Don't sync, or its connection is disconnected), the app asks once: Remove from budget (the default: the account is archived, its balance leaves Ready to Assign, its transactions stay as history) or Keep tracking it by hand (the account stays and keeps counting).
- Removed in 0.7.1: Teller. It has no public signup for independent developers, so users couldn't get the developer accounts the Nerdy door needed. Its connections were removed on update and their transactions kept.

## Bills (Stage 6 data model, Stage 10 in full)
- A bill has a name, an amount, a due date, how often it repeats (monthly, every 3 months, every 6 months, yearly, or irregular/one-off), the envelope it feeds, and an autopay flag. Each due date is one cycle; payments count toward a cycle.
- Bills and envelopes are many to one: several bills can feed one envelope (for example three phone lines billed on different days feeding one Phone envelope).
- One bill can be paid by several payments, by different methods (bank, ticked by hand, cash). The cycle counts as paid only when the payments reach the amount due.
- Partial payments: the cycle stays open with the rest owed ("$X left"). One tap (Rest to debt) moves the rest into the bill's catch-up Debt envelope (made the first time) and settles the cycle.
- Once-a-year and irregular bills fill a little each payday: a Fill to target envelope with no target that bills feed gets, each payday, the least that has every bill's money in by its due date.
- Bank sync and CSV import mark bills paid when the payment matches: the bill's "payment's description has" words and an amount that fits (a part payment is fine), "any amount counts" for a bill paid in parts by transfers, or a rule set to count toward the bill (any amount unless the rule has a range). A payment toward a bill in To sort is suggested into the bill's envelope.
- Bills can be ticked paid by hand, or "Paid in cash" (logs the cash spend from the bill's envelope). When the bank's record of a hand-ticked bill comes in, it takes the place of the tick, never a second payment.
- The amount changes (utilities): the latest charge is the expected amount next time.
- Detection: repeating charges are found in the history (see Subscriptions); one becomes a bill only when the user taps Make it a bill. Nothing is created silently.
- Bills tab (bottom bar: Home, Envelopes, Activity, Bills, More): the next payday, the autopay check, Coming up (sorted by due date, overdue marked, with debt payments), and Subscriptions.
- Autopay warning: an autopay due before the next payday whose envelope doesn't have enough (all its autopays due by then together) shows the shortfall in the Bills tab, and a notification once per cycle.
- Reminders are off by default and turned on per bill, with how many days ahead (on the day to a week). They come as notifications from a WorkManager job that runs a few times a day (it survives reboots and needs no exact-alarm permission), once per cycle, while unpaid.
- Notification channels of their own: Bill reminders and Autopay warnings, each with a switch in More, Alerts. The lock screen shows no amounts unless that's turned on.

### Payday checklist (Stage 10)
- When the payday deposit lands, auto-fill runs, and then a "Pay these now" card appears on Home (under Ready to Assign) with the bills due before the next payday, and anything still overdue.
- A bill is ticked off by hand (untick undoes a hand tick), or automatically when bank sync matches the payment.
- The card collapses to "All N payday bills paid" once everything on it is paid.

## App lock (Stage 6)
- The app locks with the phone's own fingerprint or PIN, through the system prompt. The app never stores a PIN of its own.
- On by default. It locks after 5 minutes in the background, and the time is configurable.

## Hide screen (Stage 6)
- A toggle, off by default.
- When on, the app shows blank in the recent-apps view and blocks screenshots and screen mirroring.
- It is off by default because it also blacks out screen mirroring tools the user may rely on.

## Matching and approval (Stage 8)
- Every new transaction from bank sync or a CSV gets a suggested envelope when there's a good basis, and lands in To sort with it. With no basis it lands in To sort with no suggestion.
- Payees are compared after cleaning: card numbers, store numbers, dates, times, reference codes and words like "POS PURCHASE" are stripped, so every charge from the same merchant looks the same.
- A suggestion comes from, in order: a likely return (see Returns), the first rule that matches, or the user's own last choices for that payee when they agree.
- Rules match the cleaned payee, and optionally the account, money in or out, and an amount range. A rule puts the money in one envelope, splits it across several (in the same proportions for any amount), or marks it a transfer between the user's own accounts (not spending or income). A rule can also count toward a bill (Stage 10, set in the rule editor).
- Rules screen (More, Rules): list, add, edit, delete, and Up and Down to set which is tried first. The editor shows what the rule matches before it's saved: what in To sort would get its suggestion, and how many earlier transactions it matches (those never change).
- Learning: the first time the user sorts a payee by hand, the app offers once, in the list, to always do that (Make a rule or No thanks). Three identical choices in a row for a payee make the rule by themselves, shown in the list so it can be undone. No thanks or Undo means that payee is never learned. Transactions entered by hand are never learned from.
- Rules never touch history before an account's starting balance date, archived or track-only accounts, or anything already sorted.
- To sort shows each transaction with its suggestion. One tap approves it; picking an envelope sorts it there instead; Split opens the split. Approve all suggested says how many first. A pending transaction can be approved, and the approval stays when it posts.
- A rule can be set to Always ask: it suggests, but never sorts by itself (stores that sell everything).
- Approve rule matches automatically (More, off by default): rule matches are sorted as they arrive and listed under Recently sorted, where tapping one opens it to change. History suggestions, returns and Always ask rules still wait for a tap.
- Rules live only on the user's phone, never in code or test data.
- Transfers to payment apps that do not match a rule go to the approve list.

## Live spend alerts (Stage 8)
- When background sync brings new spending, a notification shows the payee, the amount, the suggested envelope and what would be left in it, with Approve and Change. Several new charges make one notification, not one each.
- Warnings, in a second notification: an envelope going low (under 15% of its target or payday amount) or overspent, and Safe to Spend going negative.
- The notification permission (Android 13 and up) is asked for from a Home card once bank sync is set up, or when an alert is turned on. Never at first launch.
- More, Alerts: a switch for each kind, and Show amounts on the lock screen (off by default: the lock screen shows only how many came in, no payees, envelopes or amounts). Approve from a notification needs the phone unlocked.
- Alerts are only as live as sync: background sync every 6, 12 or 24 hours, and SimpleFIN's own data updates about once a day. The settings say so.
- Everything stays on the phone.
- Reading bank notifications (Stage 10, More, Alerts, Read bank notifications; off by default): with Android's notification access, the app reads only the apps the user picks (a bank or wallet app), each logging to an account the user picks. Every other app's notification is dropped unread.
- A spend notification logs the purchase right away as pending, runs the rules (and auto-approve, if on) and sends the spend alert above. When bank sync brings the real record, the two are matched (the same money within 5 days and 20%, same payee): the entry takes the bank's amount and date, never a second row. An entry sync never brings is flagged in Activity after a week ("Not in bank sync after a week": Keep or Delete).
- A deposit notification that looks like the payday deposit starts payday as soon as it lands (sweep, auto-fill, checklist). Bank sync confirms it later; if its amount differs a little, the same row takes the bank's amount and nothing is doubled. A repeat of the same notification within 10 minutes is ignored.
- Reading is learned per app: the first money notification from an app is shown in the app once ("Check how <app> is read": pick the amount, fix the payee, money in or out); after that the app's notifications like it are read by themselves, spending and deposits each learned once. Nothing bank-specific is built in.
- Privacy: the notification text is read in memory on the phone; only the parsed amount and payee are kept (as the transaction, in the encrypted database). The text is never written, logged or sent. A notification waiting to be checked is held in memory only. Turning reading off switches the reader off entirely (Android stops binding it) and hides the access steps.
- Installed from a download, the app is under Android's restricted settings (Android 13 and up): the reading screen walks through it with buttons to Open notification access and Open app info (three dots, Allow restricted settings).
- Test: each picked app shows what was last read from it (amount, payee, time).

## Subscriptions screen (Stage 10)
- Lists every recurring charge found in the history at any interval: monthly, every 3 months, every 6 months, yearly. A series is one payee's charges at a steady interval (monthly needs three charges, or two of the same amount); a store visited now and then isn't one.
- Each shows the last date charged and the next expected date, and the monthly total sits on top.
- The user can confirm one (it stops being New), rename it, merge it with another (the same thing under two payee names), make it a bill, or say it isn't a subscription (it moves to a list at the bottom and can be brought back).
- Handles monthly, quarterly, semiannual and annual intervals.
- Each subscription shows the current amount, taken from its latest charge, plus the date of that charge and a monthly equivalent so a quarterly or annual charge does not look small.
- An average is never shown as the amount, because averages hide price changes.
- A price change from one charge to the next is flagged.
- A newly detected subscription is marked "New".

## Safe to Spend (Stage 11)
- From 0.8.0, an envelope can be marked Everyday spending (envelope edit screen); the setup wizard marks the Groceries and Gas starters. Alerts already use it; the Home card comes in Stage 11.
- Safe to Spend counts only the envelopes marked as everyday spending. The default is groceries and gas. The user can change which envelopes count.
- It is what is left in those envelopes. Bills due before the next payday and set-asides live in their own envelopes, so they are already out of it.
- Rollover is excluded. It is not shown on the home screen.

## Receipt scanning (Stage 9)
- Scan receipt: from Activity (Scan, and "Scan a receipt for one of these" in To sort), from a transaction's screen (Add receipt), and from More, Receipts. A photo or PDF shared to the app from another app opens the scanner too.
- Take a photo (the phone's camera app, through a private file the app deletes as soon as it's read; the app has no camera permission), or choose an image or a PDF (up to 10 pages). A long receipt can be several photos: the lines at the start of a photo that repeat the end of the one before are read once.
- Before reading, the photo is cropped to the receipt, straightened and given more contrast.
- Text recognition runs on the phone, offline, with no account: Tesseract OCR (through Tesseract4Android, Apache-2.0) with the English model in the app. It has no network code and the app asks for no permission for it. Google ML Kit was not used.
- The store, date, total, tax and items (description, quantity, price) are read and shown as a form under the photo, to fix before saving. Fields the recognition wasn't sure of, or that don't add up (items plus tax against the total), are marked "check this".
- Discounts and coupons are negative items. Quantities like "2 @ 1.99", "2 x" and weights ("1.25 lb @ 3.99/lb") are read.
- Photos are stored encrypted in the app's private files (their own key, wrapped by the Android Keystore), never in shared storage or the gallery. A photo chosen from the phone stays where it was; only the app's encrypted copy is kept.
- Items are kept as data (store, item text, cleaned name, quantity, unit price, date) for the price history later (Stage 23).
- Matching: a receipt is matched to its bank transaction by total, date (the charge may post up to 5 days later, or a day before) and store name. One clear match is attached by itself; several possible ones show a short list; with none, the receipt waits in Receipts and is matched when its charge syncs in or is imported. A transaction has one receipt.
- Restaurants and gas stations: the charge may be up to 30% more than the receipt (a tip, or a pump's final amount), and the app says so.
- Split by items: each item to an envelope (a rule whose words are in the item's name suggests one; a discount goes with the item above it), tax and any tip shared out by what each envelope's items cost, and the transaction split to match. The photos stay after the split (Stage 10; 0.9.0 deleted them, and those can't come back).
- Tax is shared over all items by price: receipts don't reliably say which items were taxed.
- Delete a transaction: its receipt goes back to Receipts (waiting for a charge), or is deleted too when the user ticks that. Delete account: receipts on its transactions go with them, photos included. Erase everything: photos, the recognition files and any camera capture are wiped too.
- Stage 12's encrypted backup must include receipts: the rows and the encrypted photos.

## Transaction detail screen (Stage 9)
- Tapping a charge opens its own screen.
- With a receipt, it shows a Receipt line that opens the receipt: the photo, the items with their envelopes and anything returned, Split by items, and Not this transaction.
- Without a receipt, it has Add receipt, along with Edit and Split.

## Merchant page (Stage 23)
- Part of reports. Reached from a transaction screen ("All <store> trips").
- For the chosen period it shows the amount spent, the number of trips and the average trip, totals by category with bars, the most-bought items counted across scanned trips, and the list of trips.
- Item counts come only from scanned receipts, and the page says so. Trips without a scanned receipt still count toward the totals.

## Returns and refunds (Stage 8 basic, Stage 9 item matching)
- A store refund shows in the approve list marked as a return. It is never dropped into Ready to Assign by default.
- The default suggestion is back to the envelope the original charge at that store went to. The options are that envelope or Ready to Assign.
- Stage 9: when the purchase has a receipt, "What came back?" lists its items: tick what was returned (and how many, for several of one item). Each item counts its share of the tax. The ticked total has to match the refund (within 2% or 10 cents) before it applies, and the money goes back to each item's own envelope (or the purchase's envelopes, for an item that has none). Items already returned can't be returned again; "Not a return" frees them.
- A scanned return receipt on the refund ticks the items for the user: matched to the purchase's items by name and price.
- Without a receipt, the purchase-level return works as before.
- Refunds aren't income and never trigger the payday sweep.
- Stage 8: money in from a merchant with an earlier purchase (within 90 days, at or under what's left to return on it) is suggested as "Return of <purchase>". One tap confirms it and the money goes back to the purchase's envelopes, in proportion for a split purchase. Partial returns add up, never past the purchase.
- Any money in can be marked "This is a return" on its own screen, with a list of likely purchases (the same merchant first). "Not a return" puts it back in To sort.

## Balance check (Stage 8)
- For each linked account, the bank's balance at its as-of date is compared with the app's posted balance at the end of that date (pending transactions and anything dated later aren't counted). A transaction dated on the as-of day itself may not be in the bank's figure yet, so the day before also counts as a match.
- A match is shown quietly. A difference is shown plainly, with Add an adjustment: it shows the amount and date first and adds a Balance adjustment transaction only when the user confirms. Its money comes out of or goes into Ready to Assign. Nothing is ever changed by itself.
- When the bank's balance is the app's with the sign flipped, the app says the bank's balance looks to have the wrong sign and trusts its own math (some banks send an overdrawn balance as positive through SimpleFIN).
- The account screen and the link screen (linking into an account that's already here) show the same check.

## Balance forecast (Stage 10)
- A projected bank balance for each day from today to the day before the next payday.
- It counts unpaid bills on their due dates, autopays, subscriptions on their next charge dates, loan payments, and everyday spending.
- Two lines: Plan (the everyday envelope money spread evenly across the days) and Usual pace (modeled on the timing and size of the user's own spending in recent budget months). With too little history, only Plan shows.
- It flags the first day the balance would go below zero, with a plain overdraft warning.
- A home screen card under Safe to Spend. Tapping it opens a full view with a day-by-day list. Marking bills paid updates it.
- Stage 10 as built: the card sits at the top of Home until Safe to Spend's card arrives (Stage 11) and goes under it. The full view has the chart (Plan solid, Usual pace dashed, the first day below zero marked) and Plan and Usual pace switches. Only budget accounts count (not archived or track-only), and history before an account's starting balance never does.
- Usual pace spends, for each day, what the user spent on the same day after payday in the last three budget months (bills, subscriptions, transfers and washes left out, since those are counted on their own). It needs two full budget months; until then only Plan shows and the view says why.
- Plan spreads what's in the envelopes marked Everyday spending evenly over the days; with none marked it says so.

## Parked buys (Stage 11)
- Park a non-essential purchase with a name and a price. It waits a set time before the app gives a verdict. Wait options are 1 day, 2 days and 1 week; the default is set in Settings (1 day out of the box).
- After the wait, it shows whether Rollover covers it: "There's room" with what would be left, or "Not right now". Actions: buy from Rollover (or from another envelope the user picks), keep waiting, or drop it.
- Buying marks it bought. The real charge is sorted against the chosen envelope when it syncs.
- A running total of dropped amounts.
- A home screen card, plus an entry in More.

## Price history (Stage 23)
- Built from scanned receipt items only: the price of each item over time and the change since it was first seen.
- A "Prices going up" list in Reports, also reachable from the merchant page's most-bought items.
- Cross-store comparison only for items the user pairs by hand, because register names differ from store to store.
