# Dead Drop Finance

Free, local-first envelope budgeting for Android, from [DarkRelay](https://darkrelay.net).

Give every dollar a job. Your budget lives on your phone, encrypted. There is no account to make, no ads and no
telemetry.

## Status

Version 1.0.0 is the first full release. It does the whole job: accounts, envelopes, paydays, bills, transactions,
receipt scanning, backup and restore, and an in-app update.

Bank transactions come in as CSV files you download from your bank and import. You can also enter things by hand
or scan receipts. There is no bank connection (bank sync was removed in 1.3.0).

To install it, [download `DeadDropFinance.apk`](https://github.com/Crazy8697/DeadDropFinance/releases/latest/download/DeadDropFinance.apk)
on your phone, or use the [Releases](https://github.com/Crazy8697/DeadDropFinance/releases) page. When Android asks,
allow your browser to install unknown apps.

## Requirements

Android 13 or newer.

## Windows

A desktop program for Windows is in development in `desktop/` (see `desktop/README.md`). Its releases are tagged
`desktop-vX.Y.Z`; the phone app's update check never sees them.

## Privacy

Your budget stays in the app's private storage on your phone, encrypted. The app never connects to your bank, and no
third party receives your finances. Backups go only where you choose. The app's network use is GitHub, for an
optional update check and downloading an update, with nothing about you sent, and, only if you connect it (1.4.0),
your own Dead Drop server on your own network. The connection to it is encrypted; the server itself keeps a readable
copy of the budget (its master copy), on your own hardware.

## How it works

`FEATURES.md` describes how the app behaves, and `ROADMAP.md` lists the stages it is built in.

## License

Apache License 2.0, see `LICENSE` and `NOTICE`. Third-party components and their licenses are listed in
`THIRD-PARTY-NOTICES.md`, with the license texts in `licenses/`.
