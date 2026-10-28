# Database

The budget lives in one encrypted database on the phone: Room on SQLCipher.

## Encryption

- A random 256-bit database key is made on first launch. It is stored only wrapped (AES-256-GCM) by a key in the
  Android Keystore, which never leaves the phone's secure hardware.
- The database is opened with that key in SQLCipher's raw key form. The plain key is wiped from memory once the
  database is open.
- If the key can't be unwrapped (for example after the phone's screen lock is reset), the app says the budget
  can't be opened. It never deletes or recreates the database.
- Android backup is off for the app, so the database and key never leave the phone that way.

## Schema

- Version 1 (0.6.0): the budget tables.
- Version 2 (0.7.0): bank sync. Adds a nullable `external_id` column to `txn` (the provider's id for a synced
  transaction) and three tables for this phone's own connections: `connection`, `linked_account` and `sync_log`.
  Migration 1 to 2 only adds; `MigrationTest` builds a version 1 database from `1.json`, migrates it and checks
  every row survived.
- Version 3 (0.7.2): adds `track_only` and `held_amount` to `account`, both with a default of 0, so every existing
  account stays in the budget with nothing held. `MigrationTest` checks 2 to 3 as well.
- Version 4 (0.7.3): adds `provider_balance_date` to `linked_account` (when the bank's balance was true). One
  nullable column on this phone's own table.
- Version 5 (0.8.0): adds the `rule` table (auto-categorize rules, change-logged like the budget tables), four
  nullable columns on `txn` (`sorted_by`, `sorted_at`, `rule_id`, `return_of`) and `everyday` on `envelope`
  (default 0). No existing value changes.
- Version 6 (0.9.0): adds `receipt`, `receipt_item` and `item_return` (change-logged like the budget tables). No
  existing table changes. Receipt photos are files, not rows: AES-256-GCM in the app's private `files/receipts`, with
  their own random key kept in the encrypted credentials file (SecretStore), which a Keystore key wraps.
- Version 7 (0.10.0): bill columns (`reminder_days`, `match_text`, `any_amount`, `varies`), which cycle a payment
  counts toward and how it was paid (`period_due`, `method` on `bill_payment`), debt reminders (`reminder`,
  `reminder_days` on `envelope`), `bill_id` on `rule`, the `subscription` table (change-logged) and `notif_app` (the
  bank apps whose notifications are read: this phone's own, not change-logged, holding only the last parsed amount
  and payee, never notification text). All new columns have defaults or are nullable.
- Version 8 (0.11.0): adds the `parked_buy` table (parked buys: name, price, wait, when it's ready, bought,
  dropped, the envelope it's bought from and its matched charge; change-logged like the budget tables). No existing
  table changes. `MigrationTest` checks 7 to 8 and 1 to the latest.
- Version 9 (1.1.0): categories. A new `category` table (name, the envelope that claims it, order; change-logged like
  the budget tables), a nullable `category_id` on `txn_line` (a purchase's category, per line) and on `rule`, and
  `is_unplanned` on `envelope` (default 0; marks the built-in Unplanned envelope, which may be one the user already
  had). Nothing existing changes: every purchase keeps its envelope and has no category until it is given one.
  Migration 8 to 9 only adds; `MigrationTest` builds a version 8 database from `8.json`, migrates it, checks every
  row survived, and checks that the first start creates the default set and Unplanned. The `meta` row
  `categories_seeded` records that the default set was created once. Version 1.0.1 (Bonus income) needed no schema
  change: it is a transaction kind (`BONUS`, with an empty account id and one Ready to Assign line).
- Version 10 (1.2.0): `show_in_activity` on `account` (default 1), whether Activity has a tab for the account.
  Migration 9 to 10 only adds; `MigrationTest` builds a version 9 database from `9.json`, migrates it and checks every
  row survived and every account is ticked. It is an ordinary account column, so backup carries it and Purge and
  Erase remove it with the account.
- Version 11 (1.2.1): `balance_sign_flipped` on `linked_account` (default 0): the user's choice that this bank
  account's balance comes with the wrong sign. It lives on the phone's own link, not in the budget, so Purge data
  (which keeps the connection and its linked accounts) keeps it, and a backup restored on the same phone keeps it
  too (the backup leaves the bank tables out, and restore leaves the phone's own links in place); a new phone sets it
  again when it links. The 0.15.2 one-time setting (`flip_start_ids` in the sync settings) is gone. Migration 10 to 11
  only adds; `MigrationTest` checks it.
- The envelope Activity list (1.2.3) needed no schema change: it is read from the `transaction_line` and
  `envelope_move` rows already loaded for the budget (`Budget.envelopeActivity`), and writes nothing.
- The wrong-sign Adjust (1.2.2) needed no schema change: it writes the account's `starting_balance` once, through the store (one change log row like any account edit), and touches no transaction.
- The budget start date (1.1.1) needed no schema change: it is the `meta` row `budget_start` (an ISO date), so the
  backup carries it, and Purge data (which empties `meta` but the device id) and Erase everything reset it. Changing
  it never writes a budget row: only what counts in the budget is worked out again.
- Every version's schema is exported to `app/schemas` and committed, so every change is reviewable.
- There is no destructive migration fallback. A test fails the build if one is added.
- Every later schema change adds a Room `Migration` from the previous version and a migration test that opens a
  database at the old version, migrates it and checks the data survived.

## Rows

- Every synced row has a random UUID as its id, `created_at` and `updated_at` (epoch milliseconds) and a `deleted`
  flag. Deleting sets the flag (a tombstone); rows are not removed.
- Money is stored as whole cents in 64-bit integers. No floating point is used for money anywhere.
- Dates are stored as ISO text (`yyyy-mm-dd`).

## Bank sync credentials

- SimpleFIN access URLs (and any later provider's credentials) are kept in their own
  encrypted file (`keys/secrets.bin`), never in the database, SharedPreferences, the change log or any log.
- That file is encrypted with its own Android Keystore key (AES-256-GCM), separate from the database key's.
- If it can't be decrypted, it is left as it is (never replaced or deleted); the affected connection asks to be
  reconnected.
- Screens show only a masked hint (the last four characters), never a value.
- Decided (0.15.0, confirmed 0.15.1): credentials don't travel. The backup leaves out the bank connection tables
  (`connection`, `linked_account`, `sync_log`) and the credentials file. Nothing exports them.
- The backup carries every other table (the change log included; this phone's device id in `meta` is kept on
  restore) and the receipt photos, decrypted from `files/receipts` and sealed under the backup's own key. Restore
  re-encrypts them under the phone's photo key. A backup from an older schema restores into the current one (every
  migration only adds, so its columns are a subset); one from a newer schema is refused.
- Categories, claims and a purchase's category are ordinary rows, so the backup carries them (1.1.0).
- Purge data (0.15.0) empties every table but `connection` and `linked_account` (whose accounts are unlinked), keeps
  this phone's device id and the database key, deletes the receipt photos and every saved secret but the bank
  access, and clears every settings file.
- Disconnecting deletes that connection's credentials.
- Erase everything deletes the database files, the keys folder, both Keystore keys and every settings file.
- `provider` holds `SIMPLEFIN`. Rows a removed provider left behind are cleaned up on start (see
  `RemovedProviderCleanup`), so the app never reads a provider it no longer knows.

## Change log

- Every write goes through one transaction that saves the rows and, for each one, a `change_log` row: entity type,
  entity id, operation (INSERT, UPDATE or DELETE), the changed fields as JSON, a timestamp, the device id and an
  increasing sequence number.
- An update that changes nothing writes no change row.
- The device id is a random UUID made once and stored in the `meta` table.
- The change log is for later stages (sync between the user's own devices); nothing reads it yet.
- The bank sync tables (`connection`, `linked_account`, `sync_log`) describe this phone's own connections and are
  not change-logged. Transactions that bank sync brings in are ordinary budget rows and are.
