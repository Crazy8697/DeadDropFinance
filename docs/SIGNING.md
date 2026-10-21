# Release signing

Every release build of Dead Drop Finance is signed with one permanent release key. Android only installs an
update over the app when it is signed with the same key, so this key is the app's identity for good.

**If the release key is lost, no update can ever install over the app again.** Users would have to uninstall
(losing their data) and install a new app under a new key. Keep the backups.

## Where the key lives

- The key (a PKCS12 file) and its `keystore.properties` live outside this repo, in a private keys folder on the
  build machine. Neither is ever committed: `.gitignore` covers `*.p12`, `*.jks`, `*.keystore`,
  `keystore.properties` and `local.properties`.
- `keystore.properties` holds `storeFile` (full path to the key file), `storePassword`, `keyAlias` and
  `keyPassword`. The store and the key use the same password.
- The build finds it through one line in the gitignored `local.properties` at the repo root:

  ```
  ddf.signing.properties=<full path to keystore.properties>
  ```

  In `local.properties`, a Windows path needs its colon and backslashes escaped, for example
  `C\:\\path\\to\\keystore.properties`.

## How the build uses it

- `app/build.gradle.kts` reads that line, loads `keystore.properties` and creates the `release` signing config.
  Release APKs carry APK Signature Scheme v3, which covers every Android version the app supports (minSdk 33)
  and allows key rotation later; the build tools leave out v1 and v2 because nothing the app runs on needs them.
- If the line, the properties file, any of its four values, or the key file is missing, release packaging
  (`assembleRelease`, `bundleRelease`) fails with a clear "Release signing is not set up" message. A release
  build is never left unsigned and never falls back to the debug key.
- Debug builds use Android's standard debug key and build without any of this.

## Debug and release keys differ

A debug build and a release build of the same app are signed with different keys, so one can't install over the
other. To move a phone from a debug build to a release build, uninstall the debug build first.

## Backups

Keep the key in at least two separate places:

- A password manager: the key file (as an attachment, or as base64 text in a secure note), the password, and
  restore steps with the key file's SHA-256 so a restored copy can be checked.
- A second copy of the key file alone, on separate storage only its owner can read. The password is never stored
  with it.

To restore: put the key file back, write `keystore.properties` next to it, point `local.properties` at it, and
check the key file's SHA-256 against the one in the restore steps. The release certificate's SHA-256 is:

```
A9:3E:9A:A1:2B:80:76:33:33:5B:F2:88:00:B4:D9:87:FD:1A:D4:7C:1A:07:8E:F0:95:C2:AC:7F:EA:F4:5C:E0
```

`apksigner verify --print-certs` on a release APK must show that same certificate.
