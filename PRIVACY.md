# Dead Drop Finance privacy policy

Effective with version 1.3.0.

Dead Drop Finance is an Android app from DarkRelay for envelope budgeting. It keeps your budget on your phone. This
policy says what the app keeps, what it sends, and to whom.

## What DarkRelay gets

Nothing. The app has no DarkRelay account, no DarkRelay server, no analytics, no crash reporting and no ads.
DarkRelay never receives your budget, your bank data, your bank logins or anything about how you use the app.

## What the app keeps, on your phone

- Your budget: accounts, transactions, envelopes, bills, rules, subscriptions, parked buys and receipts, in a
  database encrypted with a key that's protected by your phone's secure hardware (the Android Keystore).
- Receipt photos you take or pick, encrypted in the app's private storage. They never go to the phone's gallery or
  shared storage.
- From bank notifications you choose to have read: the amount and payee, saved as a transaction, and for each app
  how its notifications are laid out (the few words around the amount and payee in the one you confirmed). The rest
  of the text is read in memory and never saved, logged or sent.
- Your settings.

Android's own backup is turned off for the app, so none of this leaves the phone through it.

## What the app sends, and where

- No bank connection: the app never connects to your bank or to any service that does. Transactions come in from CSV
  files you import, notifications you choose to have read, or what you enter yourself.
- Update check (on by default, can be turned off in More): the app asks GitHub for the latest release of the app.
  Nothing about you or your budget is sent; GitHub sees the request like any web request.
- Downloading an update (Download and install, from 0.15.1): when you choose to install a newer version, the app
  fetches the release file from GitHub the same way, checks it against the release's own published checksum, and
  installs it through Android's own installer. Nothing about you is sent there either.
- Links you tap (darkrelay.net, support) open in your browser.

## Backups

A backup is one file encrypted with a password you choose, saved wherever you pick. It contains your budget, receipt
photos and settings. Where it goes after that is up to you. Without the password it
can't be opened, by anyone, including DarkRelay.

## Permissions

- Notifications: for spend alerts, bill reminders and autopay warnings, if you turn them on.
- Notification access: only if you turn on reading bank notifications, for the apps you pick.
- Internet and network state: for the update check and downloading an update.
- Biometric: for app lock with your fingerprint or the phone's PIN.
- Run at startup, keep awake briefly and run in the foreground: so reminders keep running after a restart.
- The app has no camera, location, contacts or storage permission. Photos come from the camera app or a file you pick.

## Deleting your data

- Purge data (More) deletes your data and settings.
- Erase everything (More) deletes everything the app keeps, its keys included.
- Uninstalling the app deletes everything it keeps on the phone.

## Children

The app isn't directed to children under 13.

## Changes

A new version of this policy comes with a new version of the app, and the app's release notes say when it changed.

## Contact

Questions about this policy: email support@darkrelay.net.
