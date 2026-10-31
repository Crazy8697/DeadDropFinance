# Third-party notices: Dead Drop Finance for Windows

Dead Drop Finance is licensed under the Apache License, Version 2.0 (see LICENSE, and desktop/NOTICE for the desktop
program). This file covers the desktop program (desktop/ in the source, DeadDropFinance-Setup.exe on the Releases
page). The phone app's components are in THIRD-PARTY-NOTICES.md at the top of the repo.

The desktop program ships the components below: the runtime classpath of the desktop module (checked by the build's
license gate, which refuses to package a component missing from this file), the Java runtime bundled with it, and the
native code inside Skiko. Each Java library's license was read from its POM. The installed program carries this file,
desktop/NOTICE, LICENSE and the license texts under licenses\ in its folder. The license texts are in licenses/ (shared
with the phone app: Apache-2.0.txt, libpng-2.0.txt, SQLite-Public-Domain.txt, MPL-2.0.txt, ISC-libsodium.txt, BSD-2-Clause-Leptonica.txt, IJG-libjpeg.txt) and desktop/licenses/ (the desktop-only ones).

## The Java runtime (OpenJDK 21, Microsoft Build of OpenJDK)

License: GNU General Public License, version 2, with the Classpath Exception (desktop/licenses/GPL-2.0-with-Classpath-Exception-OpenJDK.txt)  
Upstream: https://openjdk.org, https://www.microsoft.com/openjdk

The program runs on its own copy of the Java runtime (version 21.0.12, made with jlink: the modules java.base,
java.datatransfer, java.desktop, java.logging, java.prefs, java.sql, java.transaction.xa, java.xml, jdk.crypto.ec and
jdk.unsupported), in the
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

## Room for the desktop (Google, The Android Open Source Project)

License: Apache-2.0 (licenses/Apache-2.0.txt)  
Upstream: https://github.com/androidx/androidx

The budget database: the phone app's Room database code, built for the desktop by the shared module. From 0.7.0 it
runs on the encrypted SQLite below (0.6.0 used androidx.sqlite:sqlite-bundled, which no longer ships).

| Component | Version | License (from its POM) |
|---|---|---|
| `androidx.room:room-common` | 2.8.5 | Apache License, Version 2.0 |
| `androidx.room:room-common-jvm` | 2.8.5 | Apache License, Version 2.0 |
| `androidx.room:room-runtime` | 2.8.5 | Apache License, Version 2.0 |
| `androidx.room:room-runtime-jvm` | 2.8.5 | Apache License, Version 2.0 |
| `androidx.sqlite:sqlite` | 2.6.2 | Apache License, Version 2.0 |
| `androidx.sqlite:sqlite-jvm` | 2.6.2 | Apache License, Version 2.0 |

## SQLite JDBC with SQLite3 Multiple Ciphers (the encrypted budget, from 0.7.0)

License: Apache-2.0 (licenses/Apache-2.0.txt), with parts under the BSD-2-Clause-style license of the original Zentus
driver (desktop/licenses/BSD-2-Clause-sqlite-jdbc.txt)  
Upstream: https://github.com/Willena/sqlite-jdbc-crypt, https://github.com/utelle/SQLite3MultipleCiphers

The budget file is encrypted in SQLCipher 4's format by SQLite3 Multiple Ciphers 2.4.0 (Ulrich Telle, MIT,
desktop/licenses/MIT-SQLite3MultipleCiphers.txt), built into the driver's native library with SQLite 3.53.4 (public
domain, licenses/SQLite-Public-Domain.txt). The native library also carries SQLite3 Multiple Ciphers' crypto code: SHA-2
by Olivier Gay (BSD-3-Clause, desktop/licenses/BSD-3-Clause-SHA2-Olivier-Gay.txt), fastpbkdf2 (CC0, public domain),
and ChaCha20, Poly1305 and Rijndael (public domain). The jar holds the native library for Windows, Linux, macOS and
FreeBSD; the program loads the Windows x86-64 one.

| Component | Version | License (from its POM) |
|---|---|---|
| `io.github.willena:sqlite-jdbc` | 3.53.4.0 | The Apache Software License, Version 2.0 |

## JNA, Java Native Access (the DPAPI key protection from 0.7.0, the dark title bar from 0.9.0)

License: Apache-2.0 (licenses/Apache-2.0.txt). JNA is offered under LGPL-2.1-or-later or Apache-2.0, at the user's
choice; Dead Drop Finance uses it under Apache-2.0.  
Upstream: https://github.com/java-native-access/jna

JNA calls Windows' CryptProtectData and CryptUnprotectData, which protect the budget's key for the current Windows
user. From 0.9.0 it also calls DwmSetWindowAttribute, which turns the window's own title bar dark. The jna jar carries its native dispatch library for many systems; the program loads the Windows x86-64 one.

| Component | Version | License (from its POM) |
|---|---|---|
| `net.java.dev.jna:jna` | 5.19.1 | LGPL-2.1-or-later or Apache-2.0 |
| `net.java.dev.jna:jna-platform` | 5.19.1 | LGPL-2.1-or-later or Apache-2.0 |

## Lazysodium for Java and libsodium (server sync, from 0.10.0)

License: Mozilla Public License 2.0 for Lazysodium (licenses/MPL-2.0.txt); ISC for libsodium (licenses/ISC-libsodium.txt);
MIT for its resource loader (desktop/licenses/MIT-resource-loader.txt) and SLF4J's API (desktop/licenses/MIT-SLF4J.txt).  
Upstream: https://github.com/terl/lazysodium-java, https://github.com/jedisct1/libsodium, https://github.com/terl/resource-loader, https://github.com/qos-ch/slf4j

Every message between the program and the user's own Dead Drop server is a libsodium envelope (crypto_box and
crypto_box_seal), made through Lazysodium, which calls libsodium through JNA. The lazysodium-java jar carries libsodium
built for several systems; the program loads the Windows x86-64 one. The program is not changed from upstream; MPL-2.0's
source is at the upstream URL. SLF4J's API comes along as Lazysodium's logging interface; with no logger bound it logs
nothing.

| Component | Version | License (from its POM) |
|---|---|---|
| `com.goterl:lazysodium-java` | 5.2.0 | Mozilla Public License, Version 2.0 |
| `com.goterl:resource-loader` | 2.1.0 | MIT |
| `org.slf4j:slf4j-api` | 2.0.17 | MIT |

## ZXing core (the Add a phone QR code, from 0.10.0)

License: Apache-2.0 (licenses/Apache-2.0.txt)  
Upstream: https://github.com/zxing/zxing

Makes the modules of the Add a phone QR code; the program draws them itself.

| Component | Version | License (from its POM) |
|---|---|---|
| `com.google.zxing:core` | 3.5.3 | The Apache Software License, Version 2.0 |

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
## Tesseract OCR, Leptonica and the English model, through Tess4J and Lept4J (receipt text recognition, from 0.14.0)

License: Apache-2.0 (Tess4J, Lept4J, Tesseract OCR, the English model; licenses/Apache-2.0.txt), with Leptonica and the
image libraries built into it under their own licenses below
Upstream: https://github.com/nguyenq/tess4j, https://github.com/nguyenq/lept4j, https://github.com/tesseract-ocr/tesseract,
https://github.com/DanBloomberg/leptonica, https://github.com/tesseract-ocr/tessdata_fast

Tess4J and Lept4J are JNA wrappers (their POMs and LICENSE files: Apache License 2.0). Tess4J 5.20.0's jar carries
libtesseract553.dll, Tesseract OCR 5.5.3 for 64-bit Windows (its version resource: "Copyright (C) 2026 Google, Inc.
Licensed under the Apache License, Version 2.0"; Tesseract's LICENSE at tag 5.5.3 is the Apache License 2.0). Lept4J
1.24.0's jar carries libleptonica1870.dll, Leptonica 1.87.0, for 64-bit and 32-bit Windows (the program loads the
64-bit one). The program uses only the Tesseract C API (TessAPI1) and the loader that unpacks the two DLLs into the
temporary folder (LoadLibs, with Apache Commons IO). Tess4J's jar also carries Tesseract's own copies of the English and
orientation models and its configs, which the program doesn't use; its other dependencies are not shipped. Both DLLs
link only to Windows and the Visual C++ runtime (which the bundled Java runtime carries); neither links to a network
library. Tesseract's debug viewer, which can connect to a local socket, runs only when its debug settings are set, and
the program sets none. The program gives the engine a page's pixels and reads back text; nothing is sent anywhere.

Built into libleptonica1870.dll (read from the DLL's own version strings; each license from the library's own source):

- Leptonica 1.87.0: Copyright (C) 2001-2020 Leptonica. BSD 2-clause style license, licenses/BSD-2-Clause-Leptonica.txt
  (leptonica-license.txt at tag 1.87.0 is the same text as at 1.85.0).
- libjpeg 9f (14-Jan-2024): this software is based in part on the work of the Independent JPEG Group.
  licenses/IJG-libjpeg.txt.
- libpng 1.6.50: PNG Reference Library License version 2, licenses/libpng-2.0.txt (LICENSE at tag v1.6.50 is the same
  text as at v1.6.48).
- zlib 1.3.1: Copyright (C) 1995-2024 Jean-loup Gailly and Mark Adler, under the zlib License (desktop/licenses/Zlib.txt).
- LibTIFF 4.7.1: Copyright (c) 1988-1997 Sam Leffler, Copyright (c) 1991-1997 Silicon Graphics, Inc.
  desktop/licenses/libtiff.txt.
- OpenJPEG 2.5.4: BSD 2-clause license, desktop/licenses/BSD-2-Clause-OpenJPEG.txt.
- libwebp: Copyright (c) 2010, Google Inc. BSD-3-Clause, desktop/licenses/BSD-3-Clause-libwebp.txt.

The English model is the phone app's own file (app/src/main/assets/tessdata/eng.traineddata, from tessdata_fast,
Apache-2.0; byte for byte the same as tessdata_fast's
eng.traineddata). It's installed in the program's app\resources folder:

| Bundled file | License |
|---|---|
| `tessdata/eng.traineddata` | Apache-2.0 (tessdata_fast) |

| Component | Version | License (from its POM) |
|---|---|---|
| `net.sourceforge.tess4j:tess4j` | 5.20.0 | Apache License 2.0 |
| `net.sourceforge.lept4j:lept4j` | 1.24.0 | Apache License 2.0 |
| `commons-io:commons-io` | 2.22.0 | Apache-2.0 |

## Apache PDFBox (PDF receipts, from 0.14.0)

License: Apache-2.0, with the external components listed in its LICENSE (desktop/licenses/Apache-2.0-PDFBox.txt,
desktop/licenses/Apache-2.0-FontBox.txt)
Upstream: https://pdfbox.apache.org, https://commons.apache.org/proper/commons-logging/

PDFBox draws a PDF receipt's pages so they're read like a photo. The license texts are each jar's own META-INF/LICENSE;
their NOTICE files are repeated below and in desktop/NOTICE.

| Component | Version | License (from its POM) |
|---|---|---|
| `org.apache.pdfbox:pdfbox` | 3.0.8 | Apache-2.0 |
| `org.apache.pdfbox:pdfbox-io` | 3.0.8 | Apache-2.0 |
| `org.apache.pdfbox:fontbox` | 3.0.8 | Apache-2.0 |
| `commons-logging:commons-logging` | 1.4.0 | Apache-2.0 |

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

Apache PDFBox, PDFBox io, FontBox, Commons IO and Commons Logging (from 0.14.0), from each jar's META-INF/NOTICE:

    Apache PDFBox
    Copyright 2002-2026 The Apache Software Foundation
    Based on source code contributed to the original PDFBox project. Copyright (c) 2002-2007, www.pdfbox.org
    Includes the Adobe Glyph List. Copyright 1997, 1998, 2002, 2007, 2010 Adobe Systems Incorporated.
    Includes the Zapf Dingbats Glyph List. Copyright 2002, 2010 Adobe Systems Incorporated.
    Includes the Bidi Mirroring Glyph Property (BidiMirroring-8.0.0.txt). Copyright 1991-2015 Unicode, Inc.
    Includes parts of TwelveMonkeys ImageIO. Copyright 2008-2016 Harald Kuhr
    Includes the CMYK ICC-profile (CGATS001Compat-v2-micro.icc), https://github.com/saucecontrol/Compact-ICC-Profiles,
    released to the public domain under the Creative Commons CC0 license by Clinton Ingram, saucecontrol

    Apache PDFBox io
    Copyright 2002-2026 The Apache Software Foundation

    Apache FontBox
    Copyright 2008-2026 The Apache Software Foundation
    Based on source code contributed to the original FontBox project. Copyright (c) 2006-2007, www.fontbox.org
    Includes the Script Property (Scripts-10.0.0.txt). Copyright 2017 Unicode, Inc.

    Apache Commons IO
    Copyright 2002-2026 The Apache Software Foundation

    Apache Commons Logging
    Copyright 2001-2026 The Apache Software Foundation

    These products include software developed at The Apache Software Foundation (https://www.apache.org/).

## Build tools (not shipped)

Inno Setup builds the installer and Gradle, the Kotlin compiler and the Compose Gradle plugin build the program. None
of them is part of what is installed.
