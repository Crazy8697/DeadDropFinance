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
- **jvm**: what the desktop program depends on. Room runs there (`DesktopDatabase`) on the program's own file in
  `%APPDATA%\DeadDropFinance`, never the phone's, encrypted in SQLCipher 4's format like the phone's (SQLite3 Multiple
  Ciphers through its JDBC driver, `desktopdb/CipherDriver.kt`), with its 32-byte key protected by Windows DPAPI for
  the current user (`DesktopKey`, `budget.key`). A plain budget left by desktop 0.6.0 is copied into an encrypted one,
  checked, and only then removed. Tests: `src/jvmTest` (`DesktopEncryptionTest`).

The code is in `src/jvmShared/kotlin`, which both targets compile as their own platform code. That keeps it exactly
as it was in the app: it uses `java.time`, `javax.crypto` and `org.json` (Android's own on the phone, `org.json:json` on
the desktop). The only call that differs is the database transaction, one small file each in `src/androidMain`
(Room's `withTransaction`, as before) and `src/jvmMain` (the writer connection's immediate transaction).

Both targets export Room's schema to `app/schemas`, where every version since 1 is committed. They write the same
file, so a schema that drifted between the phone and the desktop would show up there as a change.

## Server sync (1.4.0, desktop 0.10.0)

`serversync` is the phone's side of the user's own Dead Drop server: `Box` (libsodium envelopes through lazysodium,
built for Android in `src/androidMain` and for the JVM in `src/jvmMain`), `ServerClient` (setup, login and the
encrypted calls, every reply opened with the pinned server key), `HttpTransport`, and `ServerSync` (first upload,
takeover, pull and push of the change log with last write wins, the offline queue, the edit lock, status, devices and
log out). 1.5.0 and desktop 0.10.0 add take over (`takeOver`, and the handoff notice that keeps a device's waiting changes
until it holds the lock again), pairing from a QR code (`PairingQr`, `pair`) and the codes for it (`newPairingCode`).
The desktop program ships it with lazysodium-java (libsodium for Windows inside the jar). Tests: `ServerSyncTest` against
an in-memory server (no network), and `ServerE2ETest`, which runs only when pointed at a throwaway test server.

## Tests

The phone app's tests (`app/src/test`) cover this code on Android, as they always have. The desktop program's tests
(`desktop/src/test`, `EngineTest`) run it on the JVM against a real database file, with invented data.
