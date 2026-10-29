Dead Drop Finance for Windows 0.5.0, the first build of the desktop program.

This is the base the desktop program is built on: it opens and shows its name and version, and it keeps itself up to
date. There is no budgeting in it yet, and it doesn't connect to anything but GitHub. Your phone app is not affected.

## Install

Download `DeadDropFinance-Setup.exe` and run it. Windows asks once for permission to install. The program goes to
Program Files with a Start menu shortcut (and a desktop shortcut if you leave that box ticked). Nothing else needs to
be installed: the program brings its own Java runtime.

To check the download, compare its SHA-256 with `DeadDropFinance-Setup.exe.sha256`.

## Updates

A few seconds after it starts, the program asks GitHub whether a newer desktop version exists. If there is one, a bar
at the top of the window offers it; nothing pops up. Download and install fetches it, checks it against its published
SHA-256, installs it and starts the program again. Help > Check for updates checks right away, and Help > Check for
updates at startup turns the automatic check off or on. The check sends nothing about you.

## Your data

Settings are kept in your own profile (`%APPDATA%\DeadDropFinance`), never next to the program. Uninstalling the program
leaves them in place.
