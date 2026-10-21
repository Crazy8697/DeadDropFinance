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

- Teller's certificate and private key, Teller access tokens and SimpleFIN access URLs are kept in their own
  encrypted file (`keys/secrets.bin`), never in the database, SharedPreferences, the change log or any log.
- That file is encrypted with its own Android Keystore key (AES-256-GCM), separate from the database key's.
- If it can't be decrypted, it is left as it is (never replaced or deleted); the affected connection asks to be
  reconnected.
- Screens show only a masked hint (the last four characters), never a value.
- Credentials are their own category: Stage 12 (backup) decides whether they travel. Nothing exports them today.
- Disconnecting deletes that connection's credentials.

## Change log

- Every write goes through one transaction that saves the rows and, for each one, a `change_log` row: entity type,
  entity id, operation (INSERT, UPDATE or DELETE), the changed fields as JSON, a timestamp, the device id and an
  increasing sequence number.
- An update that changes nothing writes no change row.
- The device id is a random UUID made once and stored in the `meta` table.
- The change log is for later stages (sync between the user's own devices); nothing reads it yet.
- The bank sync tables (`connection`, `linked_account`, `sync_log`) describe this phone's own connections and are
  not change-logged. Transactions that bank sync brings in are ordinary budget rows and are.
