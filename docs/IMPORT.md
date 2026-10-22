# Setup import (JSON)

More, Import setup reads a JSON file picked with the phone's file picker and sets up the app from it: envelopes,
bills, the next payday, auto-categorize rules, subscriptions and settings. It never imports transactions (CSV import
and bank sync bring those in).

- Items are matched by name, ignoring upper and lower case (a rule by its `match` words). A name that's new is
  added. A name that's already in the app is updated with the fields the file gives; fields the file leaves out keep
  what the app has.
- Nothing is ever deleted. Something in the app that isn't in the file stays as it is.
- Before anything changes, the app shows what will be added and what will be updated, with Import and Cancel.
- A file with any problem is refused whole: every problem is listed by where it is (for example
  `bills[2] ("Sample Phone"): "due" must be a date like "2030-05-17".`) and nothing is applied.
- Every part of the file is optional except `format` and `version`. A field the importer doesn't know is an error,
  so a typo never goes unnoticed.
- Export example, next to Pick a JSON file, saves the invented example below (exactly as it is here) to a file
  named `deaddropfinance-setup-example.json`, to see the format or use as a starting point.

## Values

- Amounts: text like `"12.34"` (a plain number like `12.34` works too). Never negative.
- Dates: text like `"2030-05-17"` (year, month, day).
- Switches: `true` or `false`.
- Names refer to things in the file or already in the app: a bill's `envelope`, a rule's `envelope`, `splits`,
  `account` and `bill`, a subscription's `bill`.

## The file

| Field | What it is |
|---|---|
| `format` | Always `"deaddropfinance-setup"`. Required. |
| `version` | Always `1`. Required. |
| `envelopes` | A list of envelopes. |
| `bills` | A list of bills. |
| `paydays` | `{ "next": "<date>" }`: the date the next payday is expected (the Bills tab's Change). Payday itself still starts only when the deposit lands. |
| `rules` | A list of auto-categorize rules. |
| `subscriptions` | A list of subscriptions. |
| `settings` | The app's settings. |
| `transactions` | Not allowed: the file is refused if it has them. |

### Envelopes

| Field | What it is |
|---|---|
| `name` | Required. Rollover and Cash are made by the app and can't be in the file. |
| `mode` | `"fill_to_target"`, `"add_every_payday"`, `"goal"`, `"seasonal"` or `"debt"`. Required for a new envelope. |
| `target` | Fill to target: the amount to fill to each payday. |
| `perPayday` | Add every payday, Seasonal and Debt: the amount added each payday. |
| `goal` | Goal: the goal amount. A goal envelope needs `goal` and `goalDate`. |
| `goalDate` | Goal: the date to reach it by. |
| `everyday` | Everyday spending: counts in Safe to Spend. |
| `owed` | Debt: the balance owed. |
| `requiredPayment` | Debt: the required payment. |
| `dueDate` | Debt: the payment's due date (it repeats monthly from there). |
| `remind` | Debt: remind like a bill. |
| `remindDays` | Debt: how many days ahead to remind, 0 to 30. |
| `order` | Its place in the fill order, from 1 (Rollover always comes first and isn't counted). Left out: a new envelope goes at the end, an existing one stays where it is. |

### Bills

| Field | What it is |
|---|---|
| `name` | Required. |
| `amount` | The amount due. Required for a new bill. |
| `due` | The (first) due date. Required for a new bill. |
| `repeats` | `"monthly"`, `"every_3_months"`, `"every_6_months"`, `"yearly"` or `"one_off"`. Monthly when left out on a new bill. |
| `envelope` | The envelope the bill feeds. |
| `autopay` | It's paid by autopay. |
| `payeeWords` | Words a payment's description has, so bank sync and CSV import tick the bill paid. |
| `anyAmount` | Any payment to that payee counts, whatever the amount (a bill paid in parts). |
| `amountChanges` | The amount changes (utilities): the latest charge is the amount next time. |
| `reminder` | Remind before it's due. Off when left out on a new bill. |
| `reminderDays` | How many days ahead, 0 to 30. 2 when left out on a new bill. |

### Rules

| Field | What it is |
|---|---|
| `match` | Required. Words from the payee's name. Rules are matched to the app's by these words. |
| `envelope` | Where the money goes: an envelope's name, or `"Ready to assign"` for a rule for money in. |
| `splits` | Instead of `envelope`: a list of `{ "envelope": "<name>", "share": <whole number> }`, the money split in proportion to the shares. |
| `transfer` | `true`: a transfer between your own accounts (no envelope). |
| `account` | Only for this account (its name in the app). |
| `direction` | `"out"` (money out, the default), `"in"` or `"either"`. |
| `min`, `max` | Only for amounts in this range, both ends included. |
| `alwaysAsk` | Suggest, but never sort by itself. |
| `bill` | A match counts as a payment toward this bill. |

A rule needs one of `envelope`, `splits` or `transfer`. A new rule is tried first, before the rules already in the app.

### Subscriptions

| Field | What it is |
|---|---|
| `name` | Required. |
| `payees` | The payee names its charges come under (one, or several merged). Required for a new subscription. |
| `status` | `"confirmed"` (the default), `"new"` or `"not_a_subscription"`. |
| `bill` | The bill it was made into. |

### Settings

| Field | What it is |
|---|---|
| `autoApprove` | Approve rule matches automatically. |
| `backgroundSync` | Sync in the background. |
| `syncEveryHours` | 6, 12 or 24. |
| `alerts` | `{ "spending", "lowOrOverspent", "safeToSpendNegative", "amountsOnLockScreen", "billReminders", "autopayWarnings" }`, each `true` or `false`. |
| `appLock` | App lock (it needs a screen lock on the phone). |
| `lockAfterMinutes` | 0, 1, 5, 15 or 30. |
| `hideScreen` | Hide screen. |
| `checkForUpdatesAtStartup` | Check for updates at startup. |
| `parkingLotWait` | `"1 day"`, `"2 days"` or `"1 week"`. |

## Example

Every name and amount here is invented.

```json
{
  "format": "deaddropfinance-setup",
  "version": 1,
  "envelopes": [
    { "name": "Groceries", "mode": "fill_to_target", "target": "420.00", "everyday": true, "order": 1 },
    { "name": "Fuel", "mode": "fill_to_target", "target": "130.00", "everyday": true, "order": 2 },
    { "name": "Power", "mode": "fill_to_target" },
    { "name": "Rainy Day", "mode": "add_every_payday", "perPayday": "40.00" },
    { "name": "Weekend Trip", "mode": "goal", "goal": "650.00", "goalDate": "2030-11-20" },
    { "name": "Sample Card Catch-up", "mode": "debt", "perPayday": "55.00", "owed": "880.00", "requiredPayment": "35.00", "dueDate": "2030-05-17", "remind": true, "remindDays": 3 }
  ],
  "bills": [
    { "name": "Sample Power Co", "amount": "92.40", "due": "2030-05-09", "repeats": "monthly", "envelope": "Power", "payeeWords": "sample power", "amountChanges": true, "reminder": true, "reminderDays": 2 },
    { "name": "Sample Car Cover", "amount": "118.00", "due": "2030-05-25", "repeats": "every_6_months", "autopay": true },
    { "name": "Sample Rent Share", "amount": "700.00", "due": "2030-06-01", "payeeWords": "sample landlord", "anyAmount": true }
  ],
  "paydays": { "next": "2030-05-14" },
  "rules": [
    { "match": "sample market", "envelope": "Groceries" },
    { "match": "sample corner shop", "splits": [ { "envelope": "Groceries", "share": 3 }, { "envelope": "Rainy Day", "share": 1 } ], "alwaysAsk": true },
    { "match": "sample fuel stop", "envelope": "Fuel", "min": "20.00" },
    { "match": "sample payroll", "direction": "in", "envelope": "Ready to assign" },
    { "match": "sample savings transfer", "direction": "either", "transfer": true },
    { "match": "sample power", "envelope": "Power", "bill": "Sample Power Co" }
  ],
  "subscriptions": [
    { "name": "Sample Stream", "payees": [ "sample stream", "samplestream com" ], "status": "confirmed" },
    { "name": "Sample Gym", "payees": [ "sample gym" ], "status": "not_a_subscription" }
  ],
  "settings": {
    "autoApprove": false,
    "backgroundSync": true,
    "syncEveryHours": 12,
    "alerts": { "spending": true, "lowOrOverspent": true, "safeToSpendNegative": true, "amountsOnLockScreen": false, "billReminders": true, "autopayWarnings": true },
    "appLock": true,
    "lockAfterMinutes": 5,
    "hideScreen": false,
    "checkForUpdatesAtStartup": true,
    "parkingLotWait": "2 days"
  }
}
```
