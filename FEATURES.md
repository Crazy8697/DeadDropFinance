# Dead Drop Finance: Feature Spec

How the app behaves. Each section names the stage that builds it. Everything runs on the phone. Nothing user-specific ever goes in code, tests or fixtures; tests use synthetic data only.

## Accounts (Stage 6)
- An account's starting balance is its balance as of its start date, so it already includes everything before that date. A transaction dated before the start date (bank history from a CSV, for example) is kept and shown as history but doesn't change the balance or the budget.
- Delete account (on the account screen): removes the account and all its transactions for good, after a confirmation that names the account, says how many transactions go with it, and says it can't be undone. Envelope balances and Ready to Assign change to match. A transfer with another account keeps that account's side, as an entry in To sort.
- Purge data (in More, next to Erase everything, with the same kind of confirmation: what goes, and PURGE typed): resets the app to a fresh install's state. Every account, transaction, envelope, bill, payday, rule, subscription, receipt and receipt photo and the change log are deleted, every saved secret goes, and every setting goes back to its default. Unlike Erase everything, the database stays open and keeps its key. The app then opens at the setup wizard.
- Erase everything and start over (in More): wipes every account, transaction, envelope and setting from the phone, after a confirmation that lists what's lost, says there's no backup, and needs the word ERASE typed. The app then starts like a fresh install, at the setup wizard.
- Archiving an account takes its balance out of Ready to Assign, after a confirmation that says so, as if the account was never in the budget. Its transactions stay as history. Unarchiving puts it back.
- Track only, not in the budget: an account can hold money that must never be budgeted (for example a membership share that has to stay in a savings account). Its balance shows on the account list, but it never counts in Ready to Assign, and its transactions never land in To sort: they're recorded as already handled. Moving money between a budget account and a track-only one comes out of, or goes into, Ready to Assign.
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
- Stage 10: the next payday is predicted from the marked payday deposits (monthly on the usual day of the month, or the usual number of days apart; one payday so far is taken as two weeks and says so). The Bills tab shows it, and Change sets it by hand. It's used by the payday checklist, autopay warnings and the forecast. Payday itself starts only when the deposit is marked as payday.
- A late payday (the date passed, no deposit marked yet) shows a quiet note, never an alarm; the forecast counts it as tomorrow until it's marked.
- Payday is marked by hand only (1.3.1): on the deposit's transaction screen, Mark as payday runs it (Rollover sweep, auto-fill, the Pay these now checklist). A deposit that comes in through a CSV import is an ordinary transaction: it never starts payday by itself. From Stage 11 until 1.3.0 a deposit that looked like the payday deposit started payday with no tap; that is gone.
- There is no "It's payday" card (Stage 10 had one; Stage 11 removed it). Marking a deposit as payday by hand, on its own screen, still works.
- Payday and Fill envelopes on New transaction (1.3.3, the desktop program 0.8.0 too): Income going to Ready to assign can be ticked as Payday when it's entered; the new budget month starts on that transaction's date (paydays move, so the month starts on whatever date is marked). Fill envelopes, under it, can only be ticked once Payday is: it runs the same payday fill as Mark as payday (the Rollover sweep, then auto-fill in fill order, shortfalls on the Assign screen). Both are optional, every time: Payday without Fill envelopes only starts the month and moves no money. Money in that isn't ticked as Payday (a refund, say) is plain money in. Spending, transfers, a split, or income going anywhere but Ready to assign grey both out. Mark as payday on a deposit's own screen still works as before and always fills.
- Payday changes in 1.4.0 (the desktop program 0.9.0 too): Payday without Fill envelopes still closes the month, so Fill to target leftovers sweep into Rollover; it fills nothing. With Fill envelopes it sweeps, then fills. Edit transaction on an existing deposit has the same two boxes: ticking Payday marks it (Fill as chosen), unticking it unmarks it; Fill is greyed on a deposit that is already a payday. Mark as payday on a deposit's own screen asks first, with Fill envelopes as a choice (never automatic). The desktop program also splits money in across envelopes, Ready to assign and To sort, as the phone does.

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
- When payday is marked, which closes the budget month, leftovers from Fill to target envelopes are swept into one Rollover envelope. Add every payday, Goal, Seasonal and Debt envelopes are never swept.
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
- Interest, payoff plans and projected payoff dates come later (Stage 21).

## Transactions (Stage 6)
- One transaction can be split across several envelopes (for example one store run that is part groceries, part household; or one bill payment that is part current charge, part past-due debt).
- Pending and posted versions of the same transaction are matched, never double counted.
- Old School CSV import handles bank exports and payment app statement exports, with duplicate detection:
  - Two rows are the same when they have the same account, date, amount in cents and normalized description.
  - Against what the account already holds, matching is counted. If the file has N rows with a key and the account holds M, min(N, M) rows are "already here" and skipped, and the rest are new. Three identical rows in the file with one already in the app imports two.
  - Identical rows inside one file are never skipped automatically, since two real purchases or transfers can look the same. The preview lists them under "Possible duplicates in this file", grouped, with the row numbers. Each row has an import or skip choice, import by default. The counts at the top (to import, already here, possible duplicates, skipped by you, bad) update as the choices change.
  - A row that is the posted version of a pending transaction (same amount, within 5 days) posts that transaction instead of adding a second one.

## Setup (Stage 7)
- A setup wizard on first start: app lock, how transactions get in (Old School: CSV import, or entering things by hand), optional starter envelopes, finish. Installs that already have accounts get a Finish setup card on Home instead. Setup can be rerun from More.
- Removed in 0.7.1: Teller. It has no public signup for independent developers, so users couldn't get the developer accounts the Nerdy door needed. Its connections were removed on update and their transactions kept.

## Bank sync removed (1.3.0)
- Bank sync through SimpleFIN is gone: the Easy door, connecting and linking, the Sync button and background sync, the Bank sync screen in More, the Bank sync section on the account screen with its balance check, Add an adjustment, the wrong-sign warning and Adjust, "It's actually negative (overdrawn)", and "Last sync". SimpleFIN only refreshed about once a day and couldn't be made to refresh sooner.
- Every account and transaction stays. Accounts that were linked are ordinary manual accounts ("Manual account" on their Activity tab, with their starting balance entry). Synced transactions stay as ordinary ones; any still pending become posted. Nothing else in the budget changes: balances, envelopes and Ready to Assign are as before.
- On the first start of 1.3.0 the saved SimpleFIN login is deleted from the phone, with bank sync's settings and its background job. The SimpleFIN Bridge still has the app's connection until the user deletes it there.
- CSV import, entering things by hand and Scan are how transactions get in.

## Bills (Stage 6 data model, Stage 10 in full)
- A bill has a name, an amount, a due date, how often it repeats (monthly, every 3 months, every 6 months, yearly, or irregular/one-off), the envelope it feeds, and an autopay flag. Each due date is one cycle; payments count toward a cycle.
- Bills and envelopes are many to one: several bills can feed one envelope (for example three phone lines billed on different days feeding one Phone envelope).
- One bill can be paid by several payments, by different methods (bank, ticked by hand, cash). The cycle counts as paid only when the payments reach the amount due.
- Partial payments: the cycle stays open with the rest owed ("$X left"). One tap (Rest to debt) moves the rest into the bill's catch-up Debt envelope (made the first time) and settles the cycle.
- Once-a-year and irregular bills fill a little each payday: a Fill to target envelope with no target that bills feed gets, each payday, the least that has every bill's money in by its due date.
- CSV import marks bills paid when the payment matches: the bill's "payment's description has" words and an amount that fits (a part payment is fine), "any amount counts" for a bill paid in parts by transfers, or a rule set to count toward the bill (any amount unless the rule has a range). A payment toward a bill in To sort is suggested into the bill's envelope.
- Bills can be ticked paid by hand, or "Paid in cash" (logs the cash spend from the bill's envelope). When the bank's record of a hand-ticked bill comes in, it takes the place of the tick, never a second payment.
- The amount changes (utilities): the latest charge is the expected amount next time.
- Detection: repeating charges are found in the history (see Subscriptions); one becomes a bill only when the user taps Make it a bill. Nothing is created silently.
- Bills tab (bottom bar: Home, Envelopes, Activity, Bills, More): the next payday, the autopay check, Coming up (sorted by due date, overdue marked, with debt payments), and Subscriptions.
- Autopay warning: an autopay due before the next payday whose envelope doesn't have enough (all its autopays due by then together) shows the shortfall in the Bills tab, and a notification once per cycle.
- Reminders are off by default and turned on per bill, with how many days ahead (on the day to a week). They come as notifications from a WorkManager job that runs a few times a day (it survives reboots and needs no exact-alarm permission), once per cycle, while unpaid.
- Notification channels of their own: Bill reminders and Autopay warnings, each with a switch in More, Alerts. The lock screen shows no bill names or amounts unless Show amounts on the lock screen (More, Alerts, off by default) is turned on.
- The notification permission (Android 13 and up) is asked for when one is turned on. Never at first launch.

### Payday checklist (Stage 10)
- When payday is marked, auto-fill runs, and then a "Pay these now" card appears on Home (under Ready to Assign) with the bills due before the next payday, and anything still overdue.
- A bill is ticked off by hand (untick undoes a hand tick), or automatically when an imported payment matches.
- The card collapses to "All N payday bills paid" once everything on it is paid.

## App lock (Stage 6)
- The app locks with the phone's own fingerprint or PIN, through the system prompt. The app never stores a PIN of its own.
- On by default. It locks after 5 minutes in the background, and the time is configurable.

## Hide screen (Stage 6)
- A toggle, off by default.
- When on, the app shows blank in the recent-apps view and blocks screenshots and screen mirroring.
- It is off by default because it also blacks out screen mirroring tools the user may rely on.
- The program has it too (0.14.0), in Settings, Privacy, off by default: Windows' SetWindowDisplayAffinity keeps the window out of captures, which show it black.

## Matching and approval (Stage 8)
- Every new transaction from a CSV import gets a suggested envelope when there's a good basis, and lands in To sort with it. With no basis it lands in To sort with no suggestion.
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

## Notification reader removed (1.5.0)
- Reading bank notifications is gone from the phone, with the live spend alerts it sent: the reader itself and Android's notification access for it, Read bank notifications in More, Alerts (picking apps, checking how a notification is read, the restricted settings steps), the New spending, Envelope low or overspent and Safe to Spend going negative alerts with their Approve button, and "Not imported after a week" in Activity.
- Every transaction the reader logged stays as an ordinary transaction, with its envelopes and balances as before. One still marked pending stays pending: a CSV import posts it when the bank's record comes in (never a second row), or it can be unticked by hand.
- On the first start of 1.5.0 the reader's settings and picked apps are removed from the phone, and its three notification channels (New spending, Envelope warnings, Notification reading) are deleted. A backup or setup file from an older version that still has the spend alert switches restores and imports as before; those switches are ignored.
- CSV import, entering things by hand and Scan are how transactions get in. Bill reminders and autopay warnings stay.

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
- From 0.8.0, an envelope can be marked Everyday spending (envelope edit screen); the setup wizard marks the Groceries and Gas starters. The forecast uses it too.
- Safe to Spend counts only the envelopes marked as everyday spending. The default is groceries and gas. The user can change which envelopes count: tapping the Safe to Spend card opens a list of envelopes with a switch each, and the envelope edit screen has the same switch. A change shows on Home right away.
- It is what is left in those envelopes. Bills due before the next payday and set-asides live in their own envelopes, so they are already out of it.
- Rollover is excluded, even if marked. It is not shown on the home screen anywhere and has no callouts there. Cover from Rollover stays on the envelope screens.
- Home, top to bottom (the reference prototype's order): Safe to Spend, the biggest thing on the screen, with which envelopes it counts and "until next payday"; the balance forecast card; Ready to Assign, only when money is waiting; Pay these now (collapsing to "All N payday bills paid"); transactions to sort; the everyday envelopes plus any debt envelope with a payment due before the next payday, then All envelopes. The setup card, the alerts offer and the update bar, when there is one, sit above it all.
- A negative Ready to Assign (over-assigned) shows in its place as a warning row with the amount; tapping it opens Move money, set to move that much from the fullest envelope back to Ready to Assign.
- With no envelope marked everyday, the card asks the user to pick some.

## Receipt scanning (Stage 9)
- Scan receipt: from Activity (Scan, and "Scan a receipt for one of these" in To sort), from a transaction's screen (Add receipt), and from More, Receipts. A photo or PDF shared to the app from another app opens the scanner too.
- Take a photo (the phone's camera app, through a private file the app deletes as soon as it's read; the app has no camera permission), or choose an image or a PDF (up to 10 pages). A long receipt can be several photos: the lines at the start of a photo that repeat the end of the one before are read once.
- Before reading, the photo is cropped to the receipt, straightened and given more contrast.
- Text recognition runs on the phone, offline, with no account: Tesseract OCR (through Tesseract4Android, Apache-2.0) with the English model in the app. It has no network code and the app asks for no permission for it. Google ML Kit was not used.
- The store, date, total, tax and items (description, quantity, price) are read and shown as a form under the photo, to fix before saving. Fields the recognition wasn't sure of, or that don't add up (items plus tax against the total), are marked "check this".
- Discounts and coupons are negative items. Quantities like "2 @ 1.99", "2 x" and weights ("1.25 lb @ 3.99/lb") are read.
- Photos are stored encrypted in the app's private files (their own key, wrapped by the Android Keystore), never in shared storage or the gallery. A photo chosen from the phone stays where it was; only the app's encrypted copy is kept.
- Items are kept as data (store, item text, cleaned name, quantity, unit price, date) for the price history later (Stage 20).
- Matching: a receipt is matched to its bank transaction by total, date (the charge may post up to 5 days later, or a day before) and store name. One clear match is attached by itself; several possible ones show a short list; with none, the receipt waits in Receipts and is matched when its charge syncs in or is imported. A transaction has one receipt.
- Restaurants and gas stations: the charge may be up to 30% more than the receipt (a tip, or a pump's final amount), and the app says so.
- Split by items: each item to an envelope (a rule whose words are in the item's name suggests one; a discount goes with the item above it), tax and any tip shared out by what each envelope's items cost, and the transaction split to match. The photos stay after the split (Stage 10; 0.9.0 deleted them, and those can't come back).
- Tax is shared over all items by price: receipts don't reliably say which items were taxed.
- Delete a transaction: its receipt goes back to Receipts (waiting for a charge), or is deleted too when the user ticks that. Delete account: receipts on its transactions go with them, photos included. Erase everything: photos, the recognition files and any camera capture are wiped too.
- The encrypted backup (Stage 12) includes receipts: the rows and the photos.

## Transaction detail screen (Stage 9)
- Tapping a charge opens its own screen.
- With a receipt, it shows a Receipt line that opens the receipt: the photo, the items with their envelopes and anything returned, Split by items, and Not this transaction.
- Without a receipt, it has Add receipt, along with Edit and Split.

## Merchant page (Stage 20)
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

## Balance check (Stage 8, removed in 1.3.0)
- It compared a linked account with the bank's balance through bank sync, and went with bank sync in 1.3.0, with Add an adjustment and the wrong-sign Adjust.

## Balance forecast (Stage 10)
- A projected bank balance for each day from today to the day before the next payday.
- It counts unpaid bills on their due dates, autopays, subscriptions on their next charge dates, loan payments, and everyday spending.
- Two lines: Plan (the everyday envelope money spread evenly across the days) and Usual pace (modeled on the timing and size of the user's own spending in recent budget months). With too little history, only Plan shows.
- It flags the first day the balance would go below zero, with a plain overdraft warning.
- A home screen card under Safe to Spend. Tapping it opens a full view with a day-by-day list. Marking bills paid updates it.
- As built: the card sits right under Safe to Spend (Stage 11; Stage 10 had it at the top of Home). The full view has the chart (Plan solid, Usual pace dashed, the first day below zero marked) and Plan and Usual pace switches. Only budget accounts count (not archived or track-only), and history before an account's starting balance never does.
- Usual pace spends, for each day, what the user spent on the same day after payday in the last three budget months (bills, subscriptions, transfers and washes left out, since those are counted on their own). It needs two full budget months; until then only Plan shows and the view says why.
- Plan spreads what's in the envelopes marked Everyday spending evenly over the days; with none marked it says so.

## Backup and restore (Stage 12)
- More, Backup and restore. Back up now saves one file wherever the user picks with the phone's file picker (a name like deaddrop-2030-05-14.ddfbak). Restore from file picks one the same way.
- The file is encrypted with the recovery key (1.6.0): 256 random bits, made on the first backup (or when the server account is created) and shown once, as 14 groups of 4 letters and digits with Copy and "I saved it". It is never shown again, never logged and never stored in plain text (it lives in the Keystore-wrapped secrets file, the program's in a DPAPI-protected file). The contents are sealed with AES-256-GCM in chunks that can't be reordered, swapped or cut short unnoticed. Lose the key and the backups made with it are gone; the screens say so when it is shown.
- With a server account, the account has one recovery key: the creating device's own, or one made then. It reaches the account's other devices through the server, sealed to each device's own key (the server stores only those sealed copies and can't open them), so a backup made on one device opens on any of them. A device that logs in takes the account's key; its own earlier key is kept only to open its older backups. Without a server, each device has its own.
- Before a login or a QR pairing replaces a device's budget, its backup uses the device's recovery key, made and shown first if it has none.
- A backup made before 1.6.0 was encrypted with a password the user typed (PBKDF2 with HMAC-SHA256, 600,000 rounds); it still restores with that password. Restore (1.7.0) first tries the recovery keys saved on the device: the account's key, then the device's older keys. Only when none of them opens the backup is the key typed. An older backup asks for its password.
- It holds the whole budget (every budget table, the change log included), the receipt photos (on the program too from 0.14.0: a backup made on either restores on the other with its photos, and one without photos, older ones included, restores with none), and the user's settings (sorting, alerts, app lock, Hide screen, update checks). Saved secrets never travel in the backup. A backup made before 1.3.0 still restores: bank sync's ids on its transactions are dropped, a synced row still pending becomes posted, and bank sync's settings in it are ignored.
- Restore replaces everything in the app with the backup's, after a warning on the screen. The whole file is read and checked first (key or password, every table, every photo); only then is the budget replaced, in one step, and the photos swapped in. A wrong or mistyped key, a wrong password, a file that isn't a backup, a damaged or cut-short file, or a backup from a newer version is refused with a plain reason, and nothing changes. The phone's own bank connections stay as they are.
- Restore from file (1.7.3) reads the picked file once, right when it is picked, into the app's own storage, and every later step uses that copy; it is deleted when the restore ends or is cancelled. The restore itself does no screen work, so a restore while logged in can't fail halfway on the wrong thread. A failed restore says what failed: the app wasn't allowed to read the file, the file couldn't be read where it is, it's empty, it isn't a backup (or is from a newer version), it's damaged, a wrong key or password, the server refused (and whether this phone already has the backup's budget), or an unexpected problem by name. No message names the file, a path, a key or its contents. The program's restore messages say the same kinds of thing.
- The screen shows when the last backup was made and its file name. Dropbox shows as "later" (Stage 69D).

## JSON setup importer (Stage 12)
- More, Import setup: a JSON file picked with the phone's file picker sets up envelopes (name, mode, target or per-payday amount, goal, fill order, Everyday spending, debt fields), bills (every field), the next payday, auto-categorize rules, subscriptions and settings. The format, every field and an example are in docs/IMPORT.md.
- It never imports transactions: a file with them is refused.
- New names are added; names already in the app are updated with the fields the file gives. Nothing is ever deleted.
- Before anything changes it shows what will be added and what will be updated (and which settings and the next payday), with Import and Cancel.
- A bad file or field is refused whole, with each problem named by where it is. Nothing is applied from a file with errors.

## Bonus income (1.0.1)

- A Bonus income button on Home opens a short form: Amount (required, above zero), Date (today unless changed),
  Received as (Cash, Gift card, Venmo or Other: only a label, never an account) and a Note for where it came from.
- Saving adds the amount to Ready to Assign and to the overall balance. It is tied to no account and creates none.
  It is not a payday: no Rollover sweep, no auto-fill, no payday checklist.
- It shows in Activity with the other transactions, labeled Bonus income with its received-as label and note. Tapping
  it opens the same form to edit it, and Delete there takes the amount back off.
- CSV import, matching and rules never see it: it is its own kind of transaction
  (BONUS), and everything they touch is bank or hand-entered spending and income on an account.
- It travels in backup and restore, and Purge data and Erase everything remove it like other data. The JSON setup
  importer never includes it.

## Categories (1.1.0)

- A category is a label for what a purchase was (Rent, Groceries). It is separate from the envelope, which is where the
  money comes from, but envelopes claim categories (below).
- The default set, created on first run and on upgrade (once: a category the user deletes doesn't come back): Rent,
  Utilities, Phone and internet, Insurance, Groceries, Snacks, Eating out, Car gas, Car upkeep, Household,
  Subscriptions, Kids, Medical, Debt payments, Gifts, Paid a friend, Other. The user can add, rename and delete
  categories (Categories tab, Manage). Deleting one that has purchases (or rules) asks which category to move them to
  first; their envelopes don't change.
- Every purchase (money out) gets a category when it is sorted by hand or entered by hand. Income, bonus income and
  transfers don't. A split purchase has a category per line. Rule matches and approved suggestions carry the rule's
  or the earlier choice's category when they have one; a rule made before 1.1.0 has none until it is edited.
- A purchase from before 1.1.0 has no category: it shows as Uncategorized in the breakdown and can be given one from
  Activity (open the purchase, Category). Nothing is guessed for it. Giving a sorted purchase a category doesn't
  move its money; sorting one that is still in To sort by category does.
- Returns and refunds take their amount back off the category of the purchase they return (a split return follows
  the purchase's lines).
- The category shows on each purchase in Activity and can be changed there. Changing a purchase's envelope by hand
  (the edit screen) doesn't change its category.
- Envelopes claim categories: in the envelope editor an envelope can claim any number of categories, and a category
  can be claimed by at most one envelope. Picking a category on a purchase puts it in the envelope that claims it.
  A category nothing claims goes to Unplanned. Archiving an envelope drops its claims.
- Unplanned is a built-in envelope (created on first run and on upgrade; an envelope the user already named
  Unplanned is used as it is). It is an everyday envelope, so it counts toward Safe to Spend, it is funded like any
  other envelope, and it can't be archived or deleted. It claims no categories.
- A payee rule can set a category with or instead of an envelope. A rule with only a category sorts by the claim.
  Money-in rules have no category. Existing rules work unchanged.
- The Categories tab (between Bills and More) shows spending per category for a budget month (payday to payday),
  biggest first, with the total at the top. Older and Newer step through the budget months; tapping a category lists
  its purchases for that month. Uncategorized is the spending that has no category yet.
- The JSON setup importer accepts a `category` on a rule and a `categories` list on an envelope (docs/IMPORT.md);
  unknown category names are created. Backup and restore carry categories and claims. Purge data and Erase everything
  reset them: the default set and Unplanned come back the next time the app starts.
- The Envelopes screen starts with one line, "$X of $Y assigned": Y is the income that came in during the current budget
  month (the payday deposit, other deposits that went to Ready to Assign, and bonus income); X is how much was
  assigned to envelopes this budget month (out of Ready to Assign, less what was moved back into it). It follows
  assigning, moving money and new income right away. Income put straight into an envelope (not through Ready to
  Assign) counts in Y as income and in X as assigned (1.1.1). A Rollover sweep (envelope to Rollover) and a return
  count in neither.

## Currency (1.7.0)

- One currency for the whole budget, in More (phone) and Settings > Budget (program), and on setup's first page on the
  phone. A budget that never chose one is USD, as before; a new budget starts as USD unless it's changed in setup.
- The picker lists the common currencies first (USD, EUR, GBP, CAD, AUD, MXN, CHF, INR, NZD), then the other ISO 4217
  currencies the device knows, searchable by code or name. It uses the platform's own currency data: nothing is fetched.
- Only currencies whose usual convention is two decimal places are offered (1.7.1): amounts are stored as whole
  hundredths. Those with none (JPY, KRW, VND, CLP, ISK and others) or three (KWD, BHD, TND, JOD, OMR and others), and
  the funds and metals codes, aren't offered. A budget that already holds one keeps showing it and can change away.
- Amounts show and are typed in the currency's usual convention: its symbol and where it goes, and its decimal and
  thousands separators ("1.234,56 €" for EUR). Everywhere money is shown or typed: the screens, reminders, CSV
  import (a file's own amounts decide its decimal comma; when they don't show it, the currency does).
- Every currency shows two decimal places, since amounts are stored as whole hundredths.
- Display only: no exchange rates, no conversion, nothing sent anywhere. Changing it changes no stored amount.
- It syncs to every device of the account as a meta change, like the budget start date, and a backup carries it.

## Budget start date (1.1.1)

- One budget start date for the whole budget. Transactions dated before it (purchases, income, bonus income,
  transfers) are bank history: paid for before budgeting began.
- History never counts in envelope balances, Ready to Assign, Safe to Spend, the assigned total, the payday sweep or
  the payday checklist (a deposit before it can't be marked payday). It does count in the Categories tab, in the budget
  month it happened. It still shows in Activity, marked "before budget start", and never appears in To sort.
- A purchase in history can be given a category from Activity (that only sets the label). A payee rule that sets a
  category labels it too, as it would any purchase; nothing in history is sorted. A purchase already sorted keeps its
  category; its envelope line just stops counting toward the envelope.
- Account balances are untouched: they still show what the bank says. Ready to Assign at the budget start is the budget
  accounts' balances on that day (net of held amounts and track-only accounts, as before), less what is assigned to
  envelopes; money assigned before the start stays in its envelopes.
- An account's own starting balance date works as before. When both apply, the later date wins for that account.
- On upgrade to 1.1.1 the budget start date is the day of the upgrade. On a new install, and after Purge data or Erase
  everything, it is the day setup finishes (running setup again later keeps it).
- More, Budget: "Budget start date" shows the date; Change asks plainly first that envelope balances and Ready to
  Assign will be recalculated. Changing it never deletes or edits a transaction.
- It travels in backup and restore. It isn't part of the JSON setup import.

## Activity tabs (1.2.0)

- Budget month and Export CSV (phone 1.8.0, program 0.13.0): Activity shows one budget month at a time (Older and Newer, as in Categories; the program's month picker), on All and each account's tab. To sort and Recently sorted stay whole on the phone: they're work to do. Export CSV saves what the tab shows for that month where the user picks (the phone's save dialog, the program's Save As): one row per line, with Date, Description, Amount, Envelope, Category, Account, Memo, Pending and Kind, and the envelope moves where the program's All tab shows them. UTF-8, a header row, plain decimal amounts, named for the tab and the dates (deaddrop-activity-checking-2030-10-01-to-2030-10-29.csv). Nothing goes to the server. An exported account tab imports back through Import CSV as the same rows.

- Each account's screen (More, Accounts) has a "Show in Activity" checkbox, ticked for every account, old and new.
  Delete account stays at the bottom.
- Activity has a row of tabs across the top, scrolling when it's wider than the screen: All first, then one tab per
  account with Show in Activity ticked (archived accounts have none), in the account order of More, Accounts.
  Activity opens on All, as before.
- All is the combined list as before (To sort, what rules sorted lately, Recent), view only: no Add and no Scan. A
  transaction can still be opened, viewed and given a category from it. To sort lives only here; the All tab shows
  its count.
- An account's tab: "Current balance" at the top, with "Manual account" (1.3.0: every account; "Last sync" went with
  bank sync). Add, Scan and Import CSV, acting on that account (Add and Import CSV start with that account picked). Then only that account's transactions, newest first, with the
  same rows, categories and "before budget start" marking; a transfer in shows as "Transfer from ...".
- Every account's list ends with its starting balance and that balance's date: its first entry, so it's clear how the balance is built.
- Unticking Show in Activity only removes the account's tab. Its transactions, balance, envelopes and Categories
  totals don't change, and they still show under All.
- Show in Activity travels in backup and restore, goes with the account in Purge data and Erase everything, and the
  JSON setup importer can set it on an account already in the app (docs/IMPORT.md, `accounts`).

## Add activity on envelopes (1.3.0)
- Every envelope's screen (Rollover and Unplanned too) has Add activity, with Move money and Archive. It opens the same
  Add transaction screen as Add in Activity (the same fields, account picker, checks and save) with that envelope
  already chosen; everything on it can still be changed.
- Saving makes one ordinary transaction, the same as Add in Activity: it shows in Activity and in the envelope's
  Activity list, the envelope's and the account's balances change by its amount, and the app goes back to the
  envelope's screen. Back saves nothing. There is no bank matching: if the bank's record of the same money comes in
  later (a CSV), one of the two is deleted by hand.

## Envelope Activity (1.2.3)

- Every envelope's screen (Rollover and Unplanned too) has an Activity list: the envelope's spending lines and every
  money move into or out of it, newest first. Same date: by time, then by id, so the order never changes.
- Spending rows look as before (name, date, amount) and open the transaction. Money moves read "Assigned" (Ready to
  Assign into it, from Assign or Add), "Moved from Ready to Assign" and "Moved to Ready to Assign" (Move money),
  "Moved in from <envelope>" and "Moved out to <envelope>" (between envelopes, Cover from Rollover and an ATM
  withdrawal included), "Payday auto-fill" (one line per fill, no deposit name), "Rollover sweep" (and "From rollover
  sweep" on Rollover's own screen). Money in is positive, money out negative.
- Every row is listed, with no cap, 50 at a time: "Show more" below adds the next 50. Rows from before the budget
  start are listed and marked "before budget start", as Activity shows them; they don't count in the balance, and the
  rows that count add up to it.
- With no spending and no moves it says "No transactions in this envelope yet."
- Edit a move (1.8.1, program 0.14.0): a move made by hand (Move money, Assign, Cover from Rollover) opens Edit move
  from the envelope's list, and on the program from Activity and the envelope's drawer too: its date, from, to and
  amount, Save, and Delete after "Delete this move?" (Delete or Keep), as a transaction's. Saving is one change to that
  move, synced as an edit; deleting is a soft delete, synced. As Move money, an edit isn't stopped at what the source
  holds: an envelope below zero shows negative, and Ready to assign below zero shows as over-assigned. A payday's
  Rollover sweep and auto-fill (they go with the payday when it's unmarked) and an ATM withdrawal's move into Cash
  (it goes with its withdrawal) don't open.
- Move money opened from inside an envelope's screen starts as Ready to Assign into that envelope; either side can be
  changed. Opened from the Envelopes screen's top bar it keeps its old start (Ready to Assign into the first envelope).

## Activity rows (1.2.1)

- Every row in Activity (All's To sort and Recent, every account tab, a manual account's Starting balance) has
  three lines: the name on one line, cut with an ellipsis when it's long; the info line (date
  and status, as before); and a line of chips: the category (or Uncategorized) first, then what used to sit beside
  the name: how bonus income came in, payday, pending, fee, fee rebate.
- The amount sits in a fixed column on the right, sized for the widest realistic amount ("+$12,345.67",
  "-$98,765.43") at the phone's own font size, so it never wraps, shrinks or moves and amounts line up. A long name
  never changes a row's height. Tapping a row still opens it with the full name.

## Updates (Stage 5, downloaded and installed in-app from 0.15.1)
- An update check (on by default; More, Check for updates) asks GitHub for the app's own latest
  release. No account, no telemetry: just an anonymous request, and nothing about the phone or the budget is sent.
- A newer release shows a bar on Home: release notes, Download and install, Skip this version, Later.
- Download and install fetches the release APK into the app's own cache, its progress shown in the bar, then
  checks it against the release's own published checksum before anything is installed; a mismatch deletes the file
  and nothing is installed. The download always comes from this app's own fixed GitHub release path, never a URL a
  server answer supplies.
- On mobile data (or any metered network) Download and install first asks: "You're on mobile data. This download is about X MB." with Download and Cancel (0.15.2). On an unmetered network it starts right away. This needs Android's read-only network state permission, nothing else.
- Installing goes through Android's own installer (PackageInstaller): no browser, no file manager. The first time,
  if the app isn't yet allowed to install updates, a short message and a button open Android's "Install unknown
  apps" screen for it; once allowed, the same verified download continues straight to installing, with no
  re-download.
- A failed download, a failed check or a failed install never touches the budget and never crashes the app; the
  downloaded file is always cleaned up afterward, whichever way it goes.

## About and legal (Stages 14 and 15; Help folded into About in 1.1.0)
- About (More): the app's icon, name, version, license line, credits (every shipped component whose license asks for one), Licenses, the privacy policy, the end user agreement, a darkrelay.net link and "Support DarkRelay: buy some cheese 🧀" (buymeacoffee.com/crazy8697). There is no Help screen: Check for updates lives only in More, with the update bar right there.
- Licenses shows the app's license, NOTICE, the third-party notices and every license text, all carried in the app.
- The privacy policy (PRIVACY.md) and end user agreement (TERMS.md) are in the repository and in the app.
- The launcher icon is built from the locked master (design/deaddropfinance-icon.svg): an adaptive icon with background and foreground, and a one-colour version for themed icons. The launch screen and window behind the app are the DarkRelay near-black.

## Price history (Stage 20)
- Built from scanned receipt items only: the price of each item over time and the change since it was first seen.
- A "Prices going up" list in Reports, also reachable from the merchant page's most-bought items.
- Cross-store comparison only for items the user pairs by hand, because register names differ from store to store.

## Server sync (1.4.0)
- The user's own Dead Drop server keeps the budget in step across devices. One account per server; a new server has none. On a start with no account, the server makes a one-time setup code, shown once in its log and in setup-code.txt in its data folder.
- More > Server > Connect to server: the server's address first, then its key's fingerprint to check against the server's (it's pinned: a different key later is refused with a clear error and nothing is sent). Then Create the account (setup code, username, password typed twice, this device's name) on the first device, or Log in (username and password) on each other device. From 1.6.0 the desktop program can create the account too, in the same steps. The password is never kept on the phone; the server keeps only an Argon2id hash. Each device gets its own key, so the device list and remove work per device.
- Stay logged in (1.6.0), on the Log in screen and on by default: the device stays logged in until Log out. Unticked, closing the app (Back out of it, or swiping it away) logs it out: the lock let go, the device removed on the server and its key deleted here; if Android stops the app without a word, the log out is finished on the next open, before anything shows, and the login is asked for again.
- Password (1.6.0): Settings > Server, while logged in. Any logged-in device sets a new password, typed twice, without the old one (it already proved itself with its device key). Other devices stay logged in. There is no password reset on the server: a forgotten password with every device logged out locks the account out for good.
- The server is optional: the app works fully on its own with no account.
- The device that creates the account sends its whole budget up as the server's copy. A device that logs in to a server holding a budget takes the server's copy; if it had a budget of its own, it is first saved to Downloads/DeadDropFinance as an encrypted backup (the phone's backup format, opened with this device's recovery key), then replaced.
- Changes go both ways, in the server's change format, each change taken once. When two devices change the same thing, the later change (by the time it was made) wins on every device, and the device whose change lost gets a note naming the other device.
- Offline: changes wait in the change log, so nothing is lost if the app closes; they go when the server can be reached.
- Reaching the server (1.6.0): the address can be typed as host:port, http://host:port or https://host:port (spaces and a trailing slash are trimmed; no port means 5090, or 443 for https). On Android 17 the app needs the local network permission (Nearby devices) to reach a server on the phone's own Wi-Fi network; Connect and QR pairing ask for it. A failed connect says what failed: plain HTTP refused by the phone's policy, the local network permission off, the name not found (DNS), the connection refused, no answer in time, no route, an https:// (TLS) failure, a reply that isn't a Dead Drop server, or a fingerprint that doesn't match. Plain HTTP is allowed for the server (the network security config can't name address ranges, so it's allowed by default) and refused for the update check's hosts.
- The edit lock: the app takes it while on screen, sends a heartbeat every 40 seconds (the server lets it lapse after 120 seconds without one), and lets go when the app goes to the back or closes. While another device holds it, the app is read only, with a banner naming that device; what the app does on its own (the start-up checks) still goes in and reaches the server as an offline change.
- Take over editing (1.5.0): the read-only banner has Take over editing. A confirmation names the device holding the lock; then the server moves the lock to this one, and the other device turns read only with a banner saying who took over. Its changes that hadn't reached the server wait there (the server refuses them until then) and go once it holds the lock again, the later change winning as usual.
- Status, on Home and the Server screen: Not connected, Syncing, Synced, Offline (with how many changes wait) or an error, when it last synced, and Sync Now.
- Settings > Server, connected: the server's address, fingerprint, account and this device's name; the device list with Remove (that device's key is deleted on the server and it is cut off); Log out (sends what's waiting if it can, lets go of the lock, removes this device on the server and deletes its key here; the budget stays).
- While connected, Purge data asks to log out first. Erase everything logs out first. Restore a backup works while connected (1.7.2): after the file is chosen and its key (or an older backup's password) is in, its own screen says "This replaces the budget on your server. Every device on this account will get this backup instead of what it has now. The server keeps a backup of the current budget first." with Cancel (the default) and Replace server budget; Cancel changes nothing. The phone must hold the edit lock (if another device does, the screen offers Take over editing, the existing flow). The server first writes and verifies its own encrypted backup of its current budget and says so; if that fails, nothing changes anywhere and the screen says why. Then the backup replaces the phone's budget, and the same budget goes up as the server's master copy in one step. Every other device takes it at its next sync as at login: its own budget is saved first (a file, encrypted with the recovery key), then replaced, and a note says so. If the upload fails after the restore, this phone keeps the restored budget and tries again at its next sync. Logged out, a restore stays on this phone, as before.
- Receipt photos stay on the phone that took them; the receipts themselves sync.
- Scan QR code (1.5.0): Connect to server has Scan QR code beside the typed address. The camera is asked for only when it's tapped; refused, a short note says to type the address instead. The code (the desktop program's Settings > Server > Add a phone) holds the server's address, its key fingerprint and a one-time pairing code, never a password or key. The app checks the server's key against the fingerprint (a mismatch is refused and nothing is sent), then redeems the code for this phone's own key (it works once, for 10 minutes), and then it's the same as a login: the server's budget replaces this phone's, after an encrypted backup of the phone's own budget under its recovery key (1.6.0; before that, a password typed twice).
- The desktop program (0.10.0) syncs the same way: Settings > Server, the same lock (held while its window is open and not minimized), Take over editing, devices and Log out, and Add a phone. From 0.11.0 it also creates the account, has Stay logged in (unticked, closing the program logs out), Change password, and backups under the recovery key.
- The desktop program runs one copy per Windows user (0.12.3). Starting it again brings the open window to the front (restored if it was minimized) and the new start closes. The lock is a file next to the program's settings, held while the program runs, so Windows lets it go if the program crashes or is ended, and the next start opens normally. The update's relaunch waits for the old copy to finish closing. A copy with different settings (a test install) is separate.
- The desktop program does what the app does (0.12.3): click a transaction's row in Activity, an account tab or an envelope's drawer to edit it with the same fields as the app, or Delete it (the same confirm and the same soft delete, synced as a change). It also has Transfer (with ATM withdrawals into a cash account), Bonus income, Money in straight into an envelope, Pending, Archive and Delete account, Archive envelope with an Archived list and Unarchive, Cover from Rollover, changing the budget start date, and the app's after-import step.
- From 0.13.0 (phone 1.8.0) the program shows the app's own screens for everything else: the screens are one shared module (ui/) that both build. From 0.14.0 (phone 1.8.1) it has one sidebar, in this order: Budget, Activity, Accounts, Receipts, Bills, Categories, Reports, Sync, Settings. There is no Home and no More. What Home showed that nothing else did is on Budget in the program's style: the over-assigned warning with Move some back, the balance forecast (with the late payday note and Details) and Pay these now, plus Safe to Spend envelopes (the chooser); Safe to spend, Ready to assign, Bonus income and the envelopes were Budget's already. Receipts is its own sidebar item (the app's Receipts screens, its name only in the top bar). Bills and Categories are in the program's layout (cards and tables across the window, the month stepper in the top bar, the program's buttons) with the app's data and actions: Bills has the next payday (Change payday), the autopay checks, what's coming up in 45 days and the repeating charges, a bill opening the app's bill screen and Add bill and Subscriptions the app's screens; Categories has what each category spent in the month, a row opening its purchases, and Manage categories. More's settings are in Settings, each in its own section: Rules and Approve rule matches automatically, Import (the full Import CSV with column mapping, preview and duplicates, and Import setup), Alerts (bill reminders and autopay warnings), Privacy (Hide screen), and Setup and data (Run setup again, Purge data, Erase everything), with About Dead Drop Finance beside the program's About; budget start date and currency were in Settings already. Account tabs keep the quick Import CSV. The program's own screens open the app's where they belong: Fill order on Budget (drag the handle with the mouse), To sort on Activity (approve, recently sorted, learned-rule cards), Details on a transaction's edit form (payday, wash, returns, each line's category, make a rule, its receipt) and on an envelope's drawer (Goal and Debt fields, claimed categories, the suggested target). They fill the window, say "computer" and "program", and Click for Tap. The program's header pill and its Sync now are the only sync status on every screen; Settings, Server keeps its Status line.
- What differs on the program: there's no app lock or lock screen setting; the app's update and backup screens are the program's own (its update bar has Release notes and Skip this version, as the app's); a receipt comes from a file, an image (JPG, PNG, WebP, GIF or BMP) or a PDF (its first ten pages), since the program has no camera, and from 0.14.0 it's read on the computer as the app reads its photos: Tesseract with the app's own English model, shipped inside the installer, the same parser filling in the store, total and items, with nothing downloaded or sent; if reading can't run, the pages are kept and the form is typed. Bill reminders and autopay warnings are Windows notifications under the same Alerts settings, checked a minute after the budget opens and every 6 hours. From 0.14.0 the program's backups carry receipt photos too, in the app's format (see Backup and restore). Hide screen (0.14.0) is in Settings, Privacy, off by default as on the app: Windows keeps the window out of screenshots, screen recording and screen sharing, which show it black.
- The program's window opens at 1400 x 900 and can't be made smaller than 1100 x 700 (0.14.2); every screen fits at that size. Settings (0.14.2) lays its cards out in an even grid, three to a row, each row's cards the same width and height: General, Privacy, Backup and restore; Rules, Alerts, Import; Budget, Setup and data, About; then Server across the window.
- Windows Hello unlock was in desktop 0.11.0 only; it was removed in 0.12.0 (the setting and its code). The phone's own App lock (Stage 6) is unchanged.
