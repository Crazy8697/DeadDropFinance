# Dead Drop Finance for Windows

The desktop program, in development. It shares this repository with the Android app but is its own Gradle module
(`:desktop`): Kotlin with Compose for Desktop (JetBrains), on a bundled Java runtime, so users install nothing else.
Sharing code with the phone app comes in a later phase.

Version 0.5.0 is the base: the program opens, shows its name and version, and updates itself. Features come in later
stages (see `ROADMAP.md`).

## Install

Download `DeadDropFinance-Setup.exe` from the newest `desktop-v` release on the
[Releases](https://github.com/Crazy8697/DeadDropFinance/releases) page and run it. Desktop releases are tagged
`desktop-vX.Y.Z` and are never marked "latest", which stays the phone app's.

## What it stores and sends

- Settings: `%APPDATA%\DeadDropFinance\settings.properties`. Nothing is written next to the exe, and uninstalling
  leaves this folder alone.
- Network: only GitHub, for the update check and an update's download. The check is an anonymous GET of the repo's
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
