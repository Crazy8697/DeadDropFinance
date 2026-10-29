# Dead Drop Finance privacy policy

Effective with version 1.5.0.

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
- Your settings.
- If you connect to your own Dead Drop server (1.4.0): its address, its public key (pinned the first time), the
  username, this phone's name and id on the server, and its own server key (in the encrypted secrets file, protected
  by the Android Keystore). The account password is never kept on the phone.

Android's own backup is turned off for the app, so none of this leaves the phone through it.

## What the app sends, and where

- No bank connection: the app never connects to your bank or to any service that does. Transactions come in from CSV
  files you import or what you enter yourself. The app doesn't read other apps' notifications (it did, if you turned
  it on, until 1.5.0).
- Update check (on by default, can be turned off in More): the app asks GitHub for the latest release of the app.
  Nothing about you or your budget is sent; GitHub sees the request like any web request.
- Downloading an update (Download and install, from 0.15.1): when you choose to install a newer version, the app
  fetches the release file from GitHub the same way, checks it against the release's own published checksum, and
  installs it through Android's own installer. Nothing about you is sent there either.
- Your own Dead Drop server (from 1.4.0, only if you connect to one in More > Server): the app sends your budget's
  changes to it and takes in the changes your other devices made. That server is yours, on your own network; it is
  not DarkRelay's, and DarkRelay never sees it. Everything between the app and it is encrypted end to end with
  libsodium, to the server's key the app pinned the first time; the network sees only ciphertext. The password is
  sent only when you create the account or log in, inside that encryption. Logging out lets go of the server and
  deletes this phone's server key; the budget stays on the phone.
- Before a login replaces this phone's budget with the server's, a backup of it, encrypted with the account password,
  is saved to Downloads/DeadDropFinance on the phone.
- Links you tap (darkrelay.net, support) open in your browser.

## Backups

A backup is one file encrypted with a password you choose, saved wherever you pick. It contains your budget, receipt
photos and settings. Where it goes after that is up to you. Without the password it
can't be opened, by anyone, including DarkRelay.

## Permissions

- Notifications: for bill reminders and autopay warnings, if you turn them on.
- Internet and network state: for the update check, downloading an update, and syncing with your own server.
- Biometric: for app lock with your fingerprint or the phone's PIN.
- Run at startup, keep awake briefly and run in the foreground: so reminders keep running after a restart.
- Camera (1.5.0): for Scan QR code on the Server screen, which reads the desktop program's Add a phone code on the phone,
  and for Take photo on a receipt, which Android then lets the camera app do only with it. It is asked for only when you
  tap one of them; refused, type the server's address instead, or pick a receipt file. Nothing the camera sees leaves the
  phone: the QR code's content (the server's address, its key fingerprint and a one-time code) is used only to pair.
- The app has no location, contacts or storage permission. Receipt photos come from the camera app or a file you pick.

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
