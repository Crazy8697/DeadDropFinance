# Dead Drop Finance

Free, local-first envelope budgeting for Android, from [DarkRelay](https://darkrelay.net).

Give every dollar a job. Your budget lives on your phone, encrypted. There is no account to make, no ads and no
telemetry.

## Status

Version 1.0.0 is the first full release. It does the whole job: accounts, envelopes, paydays, bills, transactions,
receipt scanning, parked buys, backup and restore, and an in-app update.

Bank transactions come in two ways: bank sync through SimpleFIN Bridge (a paid service you sign up for directly with
SimpleFIN), or CSV files you download from your bank and import. You can also enter things by hand.

To install it, [download `DeadDropFinance.apk`](https://github.com/Crazy8697/DeadDropFinance/releases/latest/download/DeadDropFinance.apk)
on your phone, or use the [Releases](https://github.com/Crazy8697/DeadDropFinance/releases) page. When Android asks,
allow your browser to install unknown apps.

## Requirements

Android 13 or newer.

## Privacy

Your budget stays in the app's private storage on your phone, encrypted. Bank sync goes through your own
SimpleFIN account, from your phone, never through DarkRelay. Backups go only where you choose. The app connects to
SimpleFIN only if you set it up, and to GitHub for an optional update check, with nothing about you sent.

## How it works

`FEATURES.md` describes how the app behaves, and `ROADMAP.md` lists the stages it is built in.

## License

Apache License 2.0, see `LICENSE` and `NOTICE`. Third-party components and their licenses are listed in
`THIRD-PARTY-NOTICES.md`, with the license texts in `licenses/`.
