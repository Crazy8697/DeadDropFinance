# Dead Drop Finance for Windows

The desktop program, in development. It shares this repository with the Android app but is its own Gradle module
(`:desktop`): Kotlin with Compose for Desktop (JetBrains), on a bundled Java runtime, so users install nothing else.
Sharing code with the phone app comes in a later phase.

Version 0.5.0 is the base: the program opens, shows its name and version, and updates itself. Version 0.6.0 adds the
engine: the phone app's own logic, from the shared module (`shared/README.md`), on the program's own budget database.
Version 0.7.0 adds the screens from the approved mockup (`desktop/docs/desktop-mockup.html`, private, not in the public
source): Budget, Activity, Accounts, Reports and Sync placeholders, Settings, the envelope drawer and the Add
transaction and Move money dialogs, and encrypts the budget. Version 0.8.0 adds the phone's Payday and Fill envelopes
boxes, the category picker and split lines to Add transaction, and an encrypted backup and restore in the phone's
backup format (Settings and the File menu). Version 0.9.0 gives the window a dark title bar, splits money in across
envelopes, and sweeps leftovers into Rollover on a payday without Fill envelopes, as the phone does. Version 0.10.0
syncs with the user's own Dead Drop server, as the phone does (Settings > Server: log in, the device list, Log out, and
Add a phone, which shows a one-time QR code the phone app scans). Features come in later stages (see `ROADMAP.md`).

## Install

Download `DeadDropFinance-Setup.exe` from the newest `desktop-v` release on the
[Releases](https://github.com/Crazy8697/DeadDropFinance/releases) page and run it. Desktop releases are tagged
`desktop-vX.Y.Z` and are never marked "latest", which stays the phone app's.

## What it stores and sends

- Settings: `%APPDATA%\DeadDropFinance\settings.properties`. The budget: `%APPDATA%\DeadDropFinance\budget.db`, the
  phone's database (schema 12, SQLCipher 4 format, encrypted from 0.7.0) in its own file, and its key, `budget.key`,
  protected by Windows DPAPI for your user account. Nothing is written next to the exe, and uninstalling leaves this folder alone.
- Server sync (0.10.0), only once you log in: `server.properties` (the address, the server's pinned public key, this
  computer's id and name on the server, the username and where sync is up to; nothing secret) and `server-device.key`
  (this computer's own server key, protected by Windows DPAPI). The password is never kept. A login on a computer with a
  budget of its own first saves an encrypted backup of it to `Downloads\DeadDropFinance`, opened with the account password.
- Network: GitHub, for the update check and an update's download, and, once you log in, your own Dead Drop server at
  the address you typed (every request and reply a libsodium envelope, the server's key pinned on first contact). The check is an anonymous GET of the repo's
  release list a few seconds after startup (Help > Check for updates at startup turns it off) or from
  Help > Check for updates. Nothing about you is sent. The newest `desktop-v` release that carries both
  `DeadDropFinance-Setup.exe` and its `.sha256` is offered in a bar at the top of the window; phone releases are
  ignored. A download is used only if its SHA-256 matches the published one, then Setup runs silently and starts the
  program again.

## Building

On Windows, from the repository root, in Windows PowerShell, with JDK 21 and Inno Setup 6:

```
.\desktop\build.ps1                 # clean, test, build the app image
.\desktop\build.ps1 -Smoke          # start the built program once, off screen, with a throwaway %APPDATA%
.\desktop\build.ps1 -Setup          # plus DeadDropFinance-Setup.exe and its .sha256 in desktop\build\installer
.\desktop\build.ps1 -PrivacyCheck   # tools\privacy_check.py on the tree, the public set and the app image
.\desktop\build.ps1 -Release -Version x.y.z
```

`./gradlew :desktop:run` runs it from source. The version is `ddf.desktop.versionName` in `gradle.properties`, and
nowhere else. The build refuses to package a component that is missing from `desktop/THIRD-PARTY-NOTICES.md`.

## License

Apache License 2.0, see `LICENSE` and `desktop/NOTICE`. The components the desktop program ships, the Java runtime
included, are listed in `desktop/THIRD-PARTY-NOTICES.md`, with the license texts in `licenses/` and `desktop/licenses/`.
