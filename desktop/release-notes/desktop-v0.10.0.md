Dead Drop Finance for Windows 0.10.0: sync with your own Dead Drop server, and pair a phone by QR code.

- Settings > Server: type your server's address, check its key fingerprint, then log in with the account's username and password. The server's budget replaces this computer's; if this computer has a budget of its own, an encrypted backup of it is saved to Downloads\DeadDropFinance first, opened with the account password.
- Changes go both ways, as on the phone. Made while offline, they wait (even if the program closes) and go once the server can be reached. When two devices change the same thing, the later change wins and the other gets a note.
- The program edits while its window is open and not minimized. Minimized or closed, it lets go, and the phone can edit. While another device edits, the program is read only, with a banner naming that device and a Take over editing button.
- The status pill at the top shows Not connected, Syncing, Synced (with the time), Offline with what's waiting, or Sync error, with Sync now beside it. The Sync screen shows the same.
- Settings > Server lists your devices, can remove one (a lost phone), and logs out. Restoring a backup asks you to log out first.
- Add a phone: a one-time QR code (10 minutes, with a countdown and New code) that the phone app scans under More > Server > Scan QR code. It holds the server's address, its key fingerprint and the code, never your password or a key.
- Everything between the program and the server is encrypted with libsodium. This computer's server key is protected by Windows for your user account.
- Updating from 0.9.0: the program offers this version in its update bar. Your budget and settings are kept.
