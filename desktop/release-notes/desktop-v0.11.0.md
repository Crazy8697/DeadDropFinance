Dead Drop Finance for Windows 0.11.0: create the account here, Stay logged in, a recovery key for backups, and Windows Hello unlock.

- Settings > Server > Create the account: with the server's one-time setup code, a username and a password typed twice, this computer makes the server's account and its budget becomes the server's copy. Log in works as before.
- Log in has Stay logged in, on by default. Unticked, closing the program logs this computer out (it lets go of the edit lock and is removed from the server), and the next open asks for the login again.
- Change password, while logged in: a new account password, typed twice, without the old one. Your other devices stay logged in. There is no password reset on the server.
- Backups now use a recovery key instead of a password: made on the first backup (or with the account) and shown once, with Copy and I saved it. Lose it and the backups made with it are gone. With an account, the key reaches your other devices through your server, sealed so the server can't read it, so a backup opens on any of them. Backups made before 0.11.0 still restore with their password.
- Windows Hello unlock, in Settings, off by default: turning it on asks for Windows Hello first. While on, the program opens locked until Windows Hello says it's you. Without Hello set up on this computer it says so and stays off.
- Plain http:// to the server goes only to a home network, tailnet or loopback address; https:// goes anywhere. A failed connect says what failed.
- Updating from 0.10.0: the program offers this version in its update bar. Your budget and settings are kept.
