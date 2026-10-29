# Shared: the Dead Drop Finance engine

The logic of Dead Drop Finance, used by both the phone app (`app/`) and the Windows program (`desktop/`): the data
model and its Room database (schema 12, the same file format on both), the budget store with its change log, and the
domain layer (envelopes, transactions, CSV and setup import, rules, sorting, bills, paydays, forecasts, receipts and
the budget math). Nothing here draws a screen.

## How it is built

`:shared` is a Kotlin Multiplatform module with two targets:

- **android** (the AGP `com.android.kotlin.multiplatform.library` plugin): what the phone app depends on. Room's code
  for it is generated exactly as it was inside the app, and the app still opens the database itself on SQLCipher
  (`app/.../data/DatabaseOpener.kt`), with its migrations (`Migrations.kt`) and keys (`DbKeys.kt`).
- **jvm**: what the desktop program depends on. Room runs on bundled SQLite there (`DesktopDatabase`), on the
  program's own file in `%APPDATA%\DeadDropFinance`, never the phone's.

The code is in `src/jvmShared/kotlin`, which both targets compile as their own platform code. That keeps it exactly
as it was in the app: it uses `java.time`, `javax.crypto` and `org.json` (Android's own on the phone, `org.json:json` on
the desktop). The only call that differs is the database transaction, one small file each in `src/androidMain`
(Room's `withTransaction`, as before) and `src/jvmMain` (the writer connection's immediate transaction).

Both targets export Room's schema to `app/schemas`, where every version since 1 is committed. They write the same
file, so a schema that drifted between the phone and the desktop would show up there as a change.

## Tests

The phone app's tests (`app/src/test`) cover this code on Android, as they always have. The desktop program's tests
(`desktop/src/test`, `EngineTest`) run it on the JVM against a real database file, with invented data.
