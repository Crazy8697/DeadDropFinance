# UI: the shared screens

The phone app's Compose screens, and AppRoot, which wires them to the shared logic (`:shared`). Both the phone app
(`app/`) and the Windows program (`desktop/`) use them, so a screen the program shows from here has the same fields,
confirms, wording and sync as on the phone.

## How it is built

`:ui` is a Kotlin Multiplatform module built the way `:shared` is, with two targets:

- **android**: what the phone app depends on, on Jetpack Compose from the app's own Compose BOM. The phone's APK
  gets no component it didn't have before.
- **jvm**: what the program depends on, on Compose Multiplatform (the program's own Compose version).

The code is in `src/uiShared/kotlin`, which both targets compile as their own platform code. The few things that
differ are one small file each in `src/androidMain` and `src/jvmMain` (`PlatformBits.kt`): a picked file (a content
address on the phone, a file on the program) and the system Back (none on the program; each screen has its Back).

What a screen needs from its device (pickers, the browser, receipt photos, notifications, settings) comes through
AppRoot's `Platform` and `AppSettings`: the phone implements them in `MainActivity` and `SyncSettings`, the program in
`desktop/.../PhoneScreens.kt`.
