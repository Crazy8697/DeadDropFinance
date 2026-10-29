# Third-party notices: Dead Drop Finance for Windows

Dead Drop Finance is licensed under the Apache License, Version 2.0 (see LICENSE, and desktop/NOTICE for the desktop
program). This file covers the desktop program (desktop/ in the source, DeadDropFinance-Setup.exe on the Releases
page). The phone app's components are in THIRD-PARTY-NOTICES.md at the top of the repo.

The desktop program ships the components below: the runtime classpath of the desktop module (checked by the build's
license gate, which refuses to package a component missing from this file), the Java runtime bundled with it, and the
native code inside Skiko. Each Java library's license was read from its POM. The installed program carries this file,
desktop/NOTICE, LICENSE and the license texts under licenses\ in its folder. The license texts are in licenses/ (shared
with the phone app: Apache-2.0.txt, libpng-2.0.txt, SQLite-Public-Domain.txt) and desktop/licenses/ (the desktop-only ones).

## The Java runtime (OpenJDK 21, Microsoft Build of OpenJDK)

License: GNU General Public License, version 2, with the Classpath Exception (desktop/licenses/GPL-2.0-with-Classpath-Exception-OpenJDK.txt)  
Upstream: https://openjdk.org, https://www.microsoft.com/openjdk

The program runs on its own copy of the Java runtime (version 21.0.12, made with jlink: the modules java.base,
java.datatransfer, java.desktop, java.logging, java.prefs, java.xml, jdk.crypto.ec and jdk.unsupported), in the
runtime folder of the installed program. The runtime carries its own notices for the components it includes (among
them zlib, libpng, libjpeg, giflib, FreeType, HarfBuzz, Little CMS, ICU and Unicode data) in runtime\legal, one folder
per module, exactly as the JDK ships them.

## AndroidX libraries used by Compose Multiplatform (Google, The Android Open Source Project)

License: Apache-2.0 (licenses/Apache-2.0.txt)  
Upstream: https://github.com/androidx/androidx

| Component | Version | License (from its POM) |
|---|---|---|
| `androidx.annotation:annotation-jvm` | 1.9.1 | Apache License, Version 2.0 |
| `androidx.annotation:annotation` | 1.9.1 | Apache License, Version 2.0 |
| `androidx.arch.core:core-common` | 2.2.0 | Apache License, Version 2.0 |
| `androidx.collection:collection-jvm` | 1.5.0 | Apache License, Version 2.0 |
| `androidx.collection:collection` | 1.5.0 | Apache License, Version 2.0 |
| `androidx.compose.runtime:runtime-annotation-jvm` | 1.12.1 | Apache License, Version 2.0 |
| `androidx.compose.runtime:runtime-annotation` | 1.12.1 | Apache License, Version 2.0 |
| `androidx.compose.runtime:runtime-desktop` | 1.12.1 | Apache License, Version 2.0 |
| `androidx.compose.runtime:runtime-retain-desktop` | 1.12.1 | Apache License, Version 2.0 |
| `androidx.compose.runtime:runtime-retain` | 1.12.1 | Apache License, Version 2.0 |
| `androidx.compose.runtime:runtime-saveable-desktop` | 1.12.1 | Apache License, Version 2.0 |
| `androidx.compose.runtime:runtime-saveable` | 1.12.1 | Apache License, Version 2.0 |
| `androidx.compose.runtime:runtime` | 1.12.1 | Apache License, Version 2.0 |
| `androidx.lifecycle:lifecycle-common-jvm` | 2.11.0 | Apache License, Version 2.0 |
| `androidx.lifecycle:lifecycle-common` | 2.11.0 | Apache License, Version 2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose-desktop` | 2.11.0 | Apache License, Version 2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | Apache License, Version 2.0 |
| `androidx.lifecycle:lifecycle-runtime-desktop` | 2.11.0 | Apache License, Version 2.0 |
| `androidx.lifecycle:lifecycle-runtime` | 2.11.0 | Apache License, Version 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-compose-desktop` | 2.11.0 | Apache License, Version 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | Apache License, Version 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-desktop` | 2.11.0 | Apache License, Version 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-desktop` | 2.11.0 | Apache License, Version 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate` | 2.11.0 | Apache License, Version 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel` | 2.11.0 | Apache License, Version 2.0 |
| `androidx.navigationevent:navigationevent-compose-desktop` | 1.1.1 | Apache License, Version 2.0 |
| `androidx.navigationevent:navigationevent-compose` | 1.1.1 | Apache License, Version 2.0 |
| `androidx.navigationevent:navigationevent-desktop` | 1.1.1 | Apache License, Version 2.0 |
| `androidx.navigationevent:navigationevent` | 1.1.1 | Apache License, Version 2.0 |
| `androidx.savedstate:savedstate-compose-desktop` | 1.4.0 | Apache License, Version 2.0 |
| `androidx.savedstate:savedstate-compose` | 1.4.0 | Apache License, Version 2.0 |
| `androidx.savedstate:savedstate-desktop` | 1.4.0 | Apache License, Version 2.0 |
| `androidx.savedstate:savedstate` | 1.4.0 | Apache License, Version 2.0 |

## Room and SQLite for the desktop (Google, The Android Open Source Project)

License: Apache-2.0 (licenses/Apache-2.0.txt)  
Upstream: https://github.com/androidx/androidx

The budget database (from 0.6.0): the phone app's Room database code, built for the desktop by the shared module, on
bundled SQLite. sqlite-bundled-jvm carries the native library sqliteJni (SQLite 3.50.1, public domain,
licenses/SQLite-Public-Domain.txt) for Windows, Linux and macOS inside the jar; the program uses the Windows one.

| Component | Version | License (from its POM) |
|---|---|---|
| `androidx.room:room-common` | 2.8.5 | Apache License, Version 2.0 |
| `androidx.room:room-common-jvm` | 2.8.5 | Apache License, Version 2.0 |
| `androidx.room:room-runtime` | 2.8.5 | Apache License, Version 2.0 |
| `androidx.room:room-runtime-jvm` | 2.8.5 | Apache License, Version 2.0 |
| `androidx.sqlite:sqlite` | 2.6.2 | Apache License, Version 2.0 |
| `androidx.sqlite:sqlite-jvm` | 2.6.2 | Apache License, Version 2.0 |
| `androidx.sqlite:sqlite-bundled` | 2.6.2 | Apache License, Version 2.0 |
| `androidx.sqlite:sqlite-bundled-jvm` | 2.6.2 | Apache License, Version 2.0 |

## JSON in Java (org.json)

License: Public Domain (from its POM; the upstream LICENSE file reads "Public Domain.")  
Upstream: https://github.com/stleary/JSON-java

The phone app uses Android's own copy; the desktop program ships this one for the same code.

| Component | Version | License (from its POM) |
|---|---|---|
| `org.json:json` | 20260814 | Public Domain |

## Compose Multiplatform for desktop (JetBrains)

License: Apache-2.0 (licenses/Apache-2.0.txt)  
Upstream: https://github.com/JetBrains/compose-multiplatform

| Component | Version | License (from its POM) |
|---|---|---|
| `org.jetbrains.androidx.lifecycle:lifecycle-common` | 2.9.6 | Apache License, Version 2.0 |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop` | 2.9.6 | Apache License, Version 2.0 |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose` | 2.9.6 | Apache License, Version 2.0 |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime` | 2.9.6 | Apache License, Version 2.0 |
| `org.jetbrains.androidx.navigationevent:navigationevent-compose-desktop` | 1.1.0 | Apache License, Version 2.0 |
| `org.jetbrains.androidx.navigationevent:navigationevent-compose` | 1.1.0 | Apache License, Version 2.0 |
| `org.jetbrains.androidx.savedstate:savedstate-compose-desktop` | 1.3.6 | Apache License, Version 2.0 |
| `org.jetbrains.androidx.savedstate:savedstate-compose` | 1.3.6 | Apache License, Version 2.0 |
| `org.jetbrains.androidx.savedstate:savedstate` | 1.3.6 | Apache License, Version 2.0 |
| `org.jetbrains.compose.animation:animation-core-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.animation:animation-core` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.animation:animation-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.animation:animation` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.annotation-internal:annotation` | 1.9.1 | Apache License, Version 2.0 |
| `org.jetbrains.compose.collection-internal:collection` | 1.9.1 | Apache License, Version 2.0 |
| `org.jetbrains.compose.desktop:desktop-jvm-windows-x64` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.desktop:desktop-jvm` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.desktop:desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.foundation:foundation-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.foundation:foundation-layout-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.foundation:foundation-layout` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.foundation:foundation` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.material3:material3-desktop` | 1.9.0 | Apache License, Version 2.0 |
| `org.jetbrains.compose.material3:material3` | 1.9.0 | Apache License, Version 2.0 |
| `org.jetbrains.compose.material:material-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.material:material-ripple-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.material:material-ripple` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.material:material` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.runtime:runtime-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.runtime:runtime-saveable-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.runtime:runtime-saveable` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.runtime:runtime` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-backhandler-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-backhandler` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-geometry-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-geometry` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-graphics-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-graphics` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-text-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-text` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-tooling-preview-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-tooling-preview` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-unit-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-unit` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-util-desktop` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui-util` | 1.12.2 | Apache License, Version 2.0 |
| `org.jetbrains.compose.ui:ui` | 1.12.2 | Apache License, Version 2.0 |

## Skiko (JetBrains), Kotlin bindings for Skia

License: Apache-2.0 (licenses/Apache-2.0.txt)  
Upstream: https://github.com/JetBrains/skiko

Includes the native library skiko-windows-x64.dll and the ICU data file icudtl.dat; their contents are listed under "Inside Skiko's native library" below.

| Component | Version | License (from its POM) |
|---|---|---|
| `org.jetbrains.skiko:skiko-awt-runtime-windows-x64` | 0.150.2 | Apache License, Version 2.0 |
| `org.jetbrains.skiko:skiko-awt` | 0.150.2 | Apache License, Version 2.0 |
| `org.jetbrains.skiko:skiko` | 0.150.2 | Apache License, Version 2.0 |

## Kotlin standard library and kotlinx libraries (JetBrains)

License: Apache-2.0 (licenses/Apache-2.0.txt)  
Upstream: https://github.com/JetBrains/kotlin, https://github.com/Kotlin

| Component | Version | License (from its POM) |
|---|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.21 | Apache License, Version 2.0 |
| `org.jetbrains.kotlinx:atomicfu-jvm` | 0.28.0 | Apache License, Version 2.0 |
| `org.jetbrains.kotlinx:atomicfu` | 0.28.0 | Apache License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom` | 1.9.0 | Apache License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.9.0 | Apache License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.9.0 | Apache License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-datetime-jvm` | 0.7.1 | Apache License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-datetime` | 0.7.1 | Apache License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom` | 1.7.3 | Apache License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.7.3 | Apache License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.7.3 | Apache License, Version 2.0 |

## JetBrains annotations and the JetBrains Runtime API (JetBrains)

License: Apache-2.0 (licenses/Apache-2.0.txt)  
Upstream: https://github.com/JetBrains/java-annotations, https://github.com/JetBrains/JetBrainsRuntimeApi

| Component | Version | License (from its POM) |
|---|---|---|
| `org.jetbrains.runtime:jbr-api` | 1.9.0 | Apache License, Version 2.0 |
| `org.jetbrains:annotations` | 23.0.0 | Apache License, Version 2.0 |

## JSpecify

License: Apache-2.0 (licenses/Apache-2.0.txt)  
Upstream: https://github.com/jspecify/jspecify

| Component | Version | License (from its POM) |
|---|---|---|
| `org.jspecify:jspecify` | 1.0.0 | Apache License, Version 2.0 |
## Inside Skiko's native library

skiko-windows-x64.dll is Skia, Google's 2D graphics library, built by JetBrains with the libraries below linked in.
They were identified from the version strings and names inside the DLL; icudtl.dat is ICU's data file.

| Component | Version | License | License text |
|---|---|---|---|
| Skia (Google) | as built into Skiko 0.150.2 | BSD-3-Clause | desktop/licenses/BSD-3-Clause-Skia.txt |
| ICU (Unicode), with icudtl.dat | 74 | Unicode License V3 (includes the notices of ICU's own third-party parts) | desktop/licenses/Unicode-3.0-ICU.txt |
| HarfBuzz | as built into Skiko 0.150.2 | MIT ("Old MIT") | desktop/licenses/MIT-HarfBuzz.txt |
| libpng | 1.6.56 | PNG Reference Library License version 2 | licenses/libpng-2.0.txt |
| libjpeg-turbo | 3.1.0 | IJG License, Modified BSD-3-Clause and zlib License | desktop/licenses/BSD-3-Clause-IJG-libjpeg-turbo.txt |
| libwebp | as built into Skiko 0.150.2 | BSD-3-Clause | desktop/licenses/BSD-3-Clause-libwebp.txt |
| Expat | 2.7.4 | MIT | desktop/licenses/MIT-Expat.txt |
| zlib | 1.3.0.1 | zlib License | desktop/licenses/Zlib.txt |
| Wuffs (Google) | as built into Skiko 0.150.2 | Apache-2.0 or MIT | desktop/licenses/Apache-2.0-OR-MIT-Wuffs.txt |

## Notices carried under section 4(d) of the Apache License, Version 2.0

These are repeated in desktop/NOTICE:

    kotlinx.coroutines library.
    Copyright 2016-2025 JetBrains s.r.o and contributors

    kotlinx.serialization library.
    Copyright 2017-2019 JetBrains s.r.o and respective authors and developers

Skiko: "This project contains code adapted from The Android Open Source Project licensed under the Apache License,
Version 2.0" (https://android.googlesource.com/platform/frameworks/base).

## Build tools (not shipped)

Inno Setup builds the installer and Gradle, the Kotlin compiler and the Compose Gradle plugin build the program. None
of them is part of what is installed.
