# Dead Drop Finance: Feature Spec

How the app behaves. Each section names the stage that builds it. Everything runs on the phone. Nothing user-specific ever goes in code, tests or fixtures; tests use synthetic data only.

## Accounts (Stage 6)
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

## Envelopes (Stage 6)
- One flat list. No groups. The user drags envelopes to set the fill order.
- First run offers starter envelopes with names only and no dollar amounts, plus a "Start blank" button. The app never suggests how much to spend.
- Envelope modes:
  - Fill to target: tops the envelope up to its target each payday. Example, an envelope holding 30 with a target of 90:
    - When the payday does not close a budget month (the first payday, before there is a month to close), nothing is swept, so the envelope gets 60 and ends at 90.
    - When the payday closes a budget month, the 30 is swept to Rollover first, then the envelope fills from 0 to 90 out of Ready to Assign. It ends at 90 and Rollover holds the 30. Sweep always comes before fill.
  - Add every payday: adds the full amount each payday, for things being saved up for.
  - Goal: an add-every-payday envelope with a goal amount and date ("200 by December 10"). The app works out the per-payday amount and stops filling at the goal.
  - Seasonal: for bills that swing by season (heating, cooling). Adds the 12-month average of its linked bills every payday, so low months build a cushion and high months spend it down.
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
- With a required payment and due date it reminds like a bill.
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
- Removed in 0.7.1: Teller. It has no public signup for independent developers, so users couldn't get the developer accounts the Nerdy door needed. Its connections were removed on update and their transactions kept.

## Bills (Stage 6 data model, Stage 10 reminders and checklist)
- Bills and envelopes are many to one: several bills can feed one envelope (for example three phone lines billed on different days feeding one Phone envelope).
- One bill can be paid by several payments, by different methods (for example part by one transfer service, the rest by another). The bill counts as paid only when the payments reach the amount due.
- Partial payments: the bill stays open with the remainder owed. The remainder can be moved into a Debt envelope.
- Once-a-year or irregular bills (property tax, registrations) fill a little each payday.
- Autopay flag: the user does nothing, but the app warns ahead of time when an autopay is due before the next deposit and its envelope is not full.
- Reminders are off by default and are turned on per bill.

### Payday checklist (Stage 10)
- When the payday deposit lands, auto-fill runs, and then a "Pay these now" list appears on the home screen with the bills due before the next payday.
- A bill is ticked off by hand, or automatically when bank sync matches the payment.
- The list collapses once everything on it is paid.

## App lock (Stage 6)
- The app locks with the phone's own fingerprint or PIN, through the system prompt. The app never stores a PIN of its own.
- On by default. It locks after 5 minutes in the background, and the time is configurable.

## Hide screen (Stage 6)
- A toggle, off by default.
- When on, the app shows blank in the recent-apps view and blocks screenshots and screen mirroring.
- It is off by default because it also blacks out screen mirroring tools the user may rely on.

## Matching and approval (Stage 8)
- Rules match on payee and amount. A rule can say "any transfer to this payee counts toward this bill, whatever the amount".
- Rules live only on the user's phone, never in code or test data.
- Stores that sell everything (supercenters, convenience stores that sell gas and groceries) always ask instead of auto-sorting.
- Transfers to payment apps that do not match a rule go to the approve list.

## Live spend alerts (Stage 8)
- With the user's permission (Android notification access), the app reads notifications from apps the user picks, such as a bank app or a wallet app.
- A spend notification logs the purchase right away as pending and pings the user. When bank sync brings the real record, the two are matched.
- A deposit notification triggers payday auto-fill as soon as the money lands.
- Bank sync fills in anything the notifications miss and double checks them.
- Everything stays on the phone.

## Subscriptions screen (Stage 10)
- Handles monthly, quarterly, semiannual and annual intervals.
- Each subscription shows the current amount, taken from its latest charge, plus the date of that charge and a monthly equivalent so a quarterly or annual charge does not look small.
- An average is never shown as the amount, because averages hide price changes.
- A price change from one charge to the next is flagged.
- A newly detected subscription is marked "New".

## Safe to Spend (Stage 11)
- Safe to Spend counts only the envelopes marked as everyday spending. The default is groceries and gas. The user can change which envelopes count.
- It is what is left in those envelopes. Bills due before the next payday and set-asides live in their own envelopes, so they are already out of it.
- Rollover is excluded. It is not shown on the home screen.

## Receipt scanning (Stage 9)
- Every card charge at a store gets a "Scan receipt" option, both on its approve-list entry and on its transaction screen. Transfers, deposits and payment-app transfers don't get one.
- The To sort list also has a "Scan receipt" button at the top. It matches the receipt to a waiting charge by store, amount and date.
- Take one or more photos of the same receipt, top to bottom, for long receipts. Photos can also be picked from the gallery, to sort a receipt later.
- The photos are stitched by finding the lines that overlap between shots, so no item is counted twice.
- Text recognition runs on the phone, offline, with no account. The candidate is Google ML Kit text recognition with the bundled model. This is a candidate, not a decision: its terms, licensing and offline behavior are confirmed in Stage 9.
- Check: the item total plus tax is compared with the receipt's subtotal and total. On a mismatch, the app says a section is probably missing and asks for another photo.
- Items are sorted into envelopes. Store shorthand item names (abbreviated register text) are matched by rules that learn from how the user sorts them. This is the same rule engine as transaction auto-categorize, and the rules live only on the phone.
- The bank charge is split to match the sorted receipt.
- Tax is spread only across the taxed items, in proportion to their price. Untaxed lines, like fuel, get no share of it.
- The receipt is matched to its bank transaction by store, amount and date.
- Gas stations: the receipt matches the final posted amount, not a pump pre-authorization hold. Pump and inside purchases are often two separate charges with two receipts; each receipt matches its own charge. Fuel lines go to the gas envelope.
- Photos are deleted when the user taps Apply split (decided). The app keeps the result: the split, the item names and prices as text, the tax, and the learned item rules. A photo picked from the gallery is never deleted from the gallery; only the app's own copy is removed.

## Transaction detail screen (Stage 9)
- Tapping a charge opens its own screen.
- With a scanned receipt, it shows the items grouped by envelope, each group's total including its tax share, the tax line, "receipt scanned, photo deleted", and a Re-sort items action.
- Without a receipt, it shows the envelope, with Change envelope and Split actions.

## Merchant page (Stage 23)
- Part of reports. Reached from a transaction screen ("All <store> trips").
- For the chosen period it shows the amount spent, the number of trips and the average trip, totals by category with bars, the most-bought items counted across scanned trips, and the list of trips.
- Item counts come only from scanned receipts, and the page says so. Trips without a scanned receipt still count toward the totals.

## Returns and refunds (Stage 8 basic, Stage 9 item matching)
- A store refund shows in the approve list marked as a return. It is never dropped into Ready to Assign by default.
- The default suggestion is back to the envelope the original charge at that store went to. The options are that envelope or Ready to Assign.
- If the original trip has a scanned receipt (Stage 9), "Match items" lists that receipt's items. The user picks what went back. Each item's tax share is included for taxed items only. The selected total has to equal the refund before it applies, and the money goes back to each item's own envelope.
- Refunds aren't income and never trigger the payday sweep.

## Balance forecast (Stage 10)
- A projected bank balance for each day from today to the day before the next payday.
- It counts unpaid bills on their due dates, autopays, subscriptions on their next charge dates, loan payments, and everyday spending.
- Two lines: Plan (the everyday envelope money spread evenly across the days) and Usual pace (modeled on the timing and size of the user's own spending in recent budget months). With too little history, only Plan shows.
- It flags the first day the balance would go below zero, with a plain overdraft warning.
- A home screen card under Safe to Spend. Tapping it opens a full view with a day-by-day list. Marking bills paid updates it.

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
