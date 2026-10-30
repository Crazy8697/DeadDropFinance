Dead Drop Finance for Windows 0.12.2: restore while logged in.

- You can now restore a backup while logged in to your server. Before anything happens, a screen says that this replaces the budget on your server, that every device on the account will get this backup instead of what it has now, and that the server keeps a backup of its current budget first. Cancel is the default and changes nothing.
- The server writes and checks its own backup first. If it can't, nothing is changed anywhere, and the screen says why.
- The program must hold the edit lock. If another device is editing, the screen offers Take over editing.
- Your other devices take the restored budget at their next sync. Each one saves its own budget to a file first, then replaces it.
- Needs server 0.5.1 or newer. Logged out, a restore stays on this computer as before.
- Updating from 0.12.1: the program offers this version in its update bar. Your budget and settings are kept.
