# Third-party notices

Dead Drop Finance is licensed under the Apache License, Version 2.0 (see LICENSE and NOTICE).

The release app ships the components below: the release runtime classpath, checked against the contents of the built APK (including its native libraries). Each license was read from the component's own POM and from the license in its upstream repository or website. The full license texts are in the licenses folder (licenses/Apache-2.0.txt, licenses/BSD-3-Clause-SQLCipher.txt, licenses/BSD-3-Clause-ThreeTen-Backport.txt, licenses/BSL-1.0.txt, licenses/LibTom-Unlicense-LibTomCrypt.txt, licenses/SQLite-Public-Domain.txt, licenses/BSD-2-Clause-Leptonica.txt, licenses/libpng-2.0.txt, licenses/IJG-libjpeg.txt), and the app carries copies of this file, NOTICE, LICENSE and those texts under assets/licenses.

## AndroidX, Jetpack Compose, Room and WorkManager (Google, The Android Open Source Project)

License: Apache-2.0  
Upstream: https://github.com/androidx/androidx

Includes the native library libandroidx.graphics.path.so (androidx.graphics:graphics-path) for each CPU type the app includes (arm64-v8a, armeabi-v7a and x86_64).

| Component | Version | License (from its POM) |
|---|---|---|
| `androidx.activity:activity` | 1.13.0 | The Apache Software License, Version 2.0 |
| `androidx.activity:activity-compose` | 1.13.0 | The Apache Software License, Version 2.0 |
| `androidx.activity:activity-ktx` | 1.13.0 | The Apache Software License, Version 2.0 |
| `androidx.annotation:annotation` | 1.9.1 | The Apache Software License, Version 2.0 |
| `androidx.annotation:annotation-experimental` | 1.5.0 | The Apache Software License, Version 2.0 |
| `androidx.annotation:annotation-jvm` | 1.9.1 | The Apache Software License, Version 2.0 |
| `androidx.arch.core:core-common` | 2.2.0 | The Apache Software License, Version 2.0 |
| `androidx.arch.core:core-runtime` | 2.2.0 | The Apache Software License, Version 2.0 |
| `androidx.autofill:autofill` | 1.0.0 | The Apache Software License, Version 2.0 |
| `androidx.collection:collection` | 1.5.0 | The Apache Software License, Version 2.0 |
| `androidx.collection:collection-jvm` | 1.5.0 | The Apache Software License, Version 2.0 |
| `androidx.collection:collection-ktx` | 1.5.0 | The Apache Software License, Version 2.0 |
| `androidx.compose.animation:animation` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.animation:animation-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.animation:animation-core` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.animation:animation-core-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.foundation:foundation` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.foundation:foundation-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.foundation:foundation-layout` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.foundation:foundation-layout-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.material3:material3` | 1.4.0 | The Apache Software License, Version 2.0 |
| `androidx.compose.material3:material3-android` | 1.4.0 | The Apache Software License, Version 2.0 |
| `androidx.compose.material:material-ripple` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.material:material-ripple-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.runtime:runtime` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.runtime:runtime-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.runtime:runtime-annotation` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.runtime:runtime-annotation-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.runtime:runtime-retain` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.runtime:runtime-retain-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.runtime:runtime-saveable` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.runtime:runtime-saveable-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.ui:ui` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.ui:ui-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.ui:ui-geometry` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.ui:ui-geometry-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.ui:ui-graphics` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.ui:ui-graphics-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.ui:ui-text` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.ui:ui-text-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.ui:ui-unit` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.ui:ui-unit-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.ui:ui-util` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.compose.ui:ui-util-android` | 1.12.1 | The Apache Software License, Version 2.0 |
| `androidx.concurrent:concurrent-futures` | 1.1.0 | The Apache Software License, Version 2.0 |
| `androidx.concurrent:concurrent-futures-ktx` | 1.1.0 | The Apache Software License, Version 2.0 |
| `androidx.core:core` | 1.18.0 | The Apache Software License, Version 2.0 |
| `androidx.core:core-ktx` | 1.18.0 | The Apache Software License, Version 2.0 |
| `androidx.core:core-viewtree` | 1.0.0 | The Apache Software License, Version 2.0 |
| `androidx.customview:customview-poolingcontainer` | 1.0.0 | The Apache Software License, Version 2.0 |
| `androidx.emoji2:emoji2` | 1.4.0 | The Apache Software License, Version 2.0 |
| `androidx.graphics:graphics-path` | 1.0.1 | The Apache Software License, Version 2.0 |
| `androidx.interpolator:interpolator` | 1.0.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-common` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-common-java8` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-common-jvm` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-livedata` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-livedata-core` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-livedata-core-ktx` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-process` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-runtime` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-runtime-android` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose-android` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-runtime-ktx-android` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-service` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-android` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-android` | 2.11.0 | The Apache Software License, Version 2.0 |
| `androidx.navigationevent:navigationevent` | 1.0.0 | The Apache Software License, Version 2.0 |
| `androidx.navigationevent:navigationevent-android` | 1.0.0 | The Apache Software License, Version 2.0 |
| `androidx.navigationevent:navigationevent-compose` | 1.0.0 | The Apache Software License, Version 2.0 |
| `androidx.navigationevent:navigationevent-compose-android` | 1.0.0 | The Apache Software License, Version 2.0 |
| `androidx.profileinstaller:profileinstaller` | 1.4.0 | The Apache Software License, Version 2.0 |
| `androidx.room:room-common` | 2.8.5 | The Apache Software License, Version 2.0 |
| `androidx.room:room-common-jvm` | 2.8.5 | The Apache Software License, Version 2.0 |
| `androidx.room:room-runtime` | 2.8.5 | The Apache Software License, Version 2.0 |
| `androidx.room:room-runtime-android` | 2.8.5 | The Apache Software License, Version 2.0 |
| `androidx.savedstate:savedstate` | 1.4.0 | The Apache Software License, Version 2.0 |
| `androidx.savedstate:savedstate-android` | 1.4.0 | The Apache Software License, Version 2.0 |
| `androidx.savedstate:savedstate-compose` | 1.4.0 | The Apache Software License, Version 2.0 |
| `androidx.savedstate:savedstate-compose-android` | 1.4.0 | The Apache Software License, Version 2.0 |
| `androidx.savedstate:savedstate-ktx` | 1.4.0 | The Apache Software License, Version 2.0 |
| `androidx.sqlite:sqlite` | 2.7.0 | The Apache Software License, Version 2.0 |
| `androidx.sqlite:sqlite-android` | 2.7.0 | The Apache Software License, Version 2.0 |
| `androidx.sqlite:sqlite-framework` | 2.7.0 | The Apache Software License, Version 2.0 |
| `androidx.sqlite:sqlite-framework-android` | 2.7.0 | The Apache Software License, Version 2.0 |
| `androidx.startup:startup-runtime` | 1.1.1 | The Apache Software License, Version 2.0 |
| `androidx.tracing:tracing` | 1.2.0 | The Apache Software License, Version 2.0 |
| `androidx.tracing:tracing-ktx` | 1.2.0 | The Apache Software License, Version 2.0 |
| `androidx.versionedparcelable:versionedparcelable` | 1.1.1 | The Apache Software License, Version 2.0 |
| `androidx.window:window` | 1.5.0 | The Apache Software License, Version 2.0 |
| `androidx.window:window-core` | 1.5.0 | The Apache Software License, Version 2.0 |
| `androidx.window:window-core-android` | 1.5.0 | The Apache Software License, Version 2.0 |
| `androidx.work:work-runtime` | 2.12.0 | The Apache Software License, Version 2.0 |

## SQLCipher for Android (Zetetic)

License: SQLCipher Community Edition license (BSD-3-Clause style); its bundled native code also contains public domain parts  
Upstream: https://github.com/sqlcipher/sqlcipher-android

Its POM names only https://www.zetetic.net/sqlcipher/license/ ; the license was read there and in the repository's LICENSE. SQLCipher's terms require its license and copyright to be reproduced in the app and its documentation: the full text is at the end of this file and in licenses/BSD-3-Clause-SQLCipher.txt, and the app carries it under assets/licenses. It ships the native library libsqlcipher.so for each CPU type, which contains SQLCipher (Copyright (c) 2008-2026, ZETETIC, LLC), SQLite (public domain), the SQLite Android bindings (public domain, see licenses/SQLite-Public-Domain.txt) and LibTomCrypt as its crypto provider (the LibTom license, public domain in Unlicense wording, licenses/LibTom-Unlicense-LibTomCrypt.txt). No OpenSSL is included.

| Component | Version | License (from its POM) |
|---|---|---|
| `net.zetetic:sqlcipher-android` | 4.19.1 | (URL only in its POM; see above) |

## Kotlin standard library (JetBrains)

License: Apache-2.0, with parts under BSD-3-Clause and BSL-1.0  
Upstream: https://github.com/JetBrains/kotlin

Per the Kotlin repository's license/README.md, parts of the standard library are derived from other work: kotlin.collections from GWT (Apache-2.0, Copyright 2007-08 Google Inc.), kotlin.time from ThreeTen-Backport (BSD-3-Clause, Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos, see licenses/BSD-3-Clause-ThreeTen-Backport.txt), UnsignedJVM.kt from Guava (Apache-2.0, Copyright 2011 The Guava Authors) and MathJVM.kt from Boost (BSL-1.0, Copyright Eric Ford & Hubert Holin 2001, see licenses/BSL-1.0.txt).

| Component | Version | License (from its POM) |
|---|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.21 | Apache-2.0 |

## kotlinx.coroutines (JetBrains)

License: Apache-2.0  
Upstream: https://github.com/Kotlin/kotlinx.coroutines

NOTICE: kotlinx.coroutines library. Copyright 2016-2025 JetBrains s.r.o and contributors.

| Component | Version | License (from its POM) |
|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | The Apache Software License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.9.0 | The Apache Software License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.9.0 | The Apache Software License, Version 2.0 |

## kotlinx.serialization (JetBrains)

License: Apache-2.0  
Upstream: https://github.com/Kotlin/kotlinx.serialization

NOTICE: kotlinx.serialization library. Copyright 2017-2019 JetBrains s.r.o and respective authors and developers.

| Component | Version | License (from its POM) |
|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.7.3 | The Apache Software License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.7.3 | The Apache Software License, Version 2.0 |

## JetBrains Java Annotations

License: Apache-2.0  
Upstream: https://github.com/JetBrains/java-annotations

| Component | Version | License (from its POM) |
|---|---|---|
| `org.jetbrains:annotations` | 23.0.0 | The Apache Software License, Version 2.0 |

## JSpecify

License: Apache-2.0  
Upstream: https://github.com/jspecify/jspecify

| Component | Version | License (from its POM) |
|---|---|---|
| `org.jspecify:jspecify` | 1.0.0 | The Apache License, Version 2.0 |

## Guava ListenableFuture (Google)

License: Apache-2.0  
Upstream: https://github.com/google/guava

Its own POM names no license; the license comes from its parent POM (guava-parent 26.0-android) and the Guava repository's LICENSE file, both Apache-2.0.

| Component | Version | License (from its POM) |
|---|---|---|
| `com.google.guava:listenablefuture` | 1.0 | The Apache Software License, Version 2.0 |

## Tesseract4Android: Tesseract OCR, Leptonica, libjpeg, libpng (receipt text recognition)

License: Apache-2.0 (Tesseract4Android, Tesseract OCR, the English model), with Leptonica, libjpeg and libpng under their own licenses below
Upstream: https://github.com/adaptech-cz/Tesseract4Android (published on JitPack)

Its POM names no license; the license was read in the repository's LICENSE and README (Apache License 2.0). Version 4.9.0 builds Tesseract OCR 5.5.1 (https://github.com/tesseract-ocr/tesseract, Apache-2.0), Leptonica 1.85.0, libjpeg v9f and libpng 1.6.48, and its Java wrapper is based on tess-two (Apache-2.0). It ships, for each CPU type the app includes (arm64-v8a, armeabi-v7a and x86_64), the native libraries libtesseract.so, libleptonica.so, libjpeg.so and libpngx.so. The AAR asks for no permissions and has no network code; its native libraries link to no network library.

- Leptonica: Copyright (C) 2001-2020 Leptonica. BSD 2-clause style license, full text in licenses/BSD-2-Clause-Leptonica.txt.
- libjpeg: this software is based in part on the work of the Independent JPEG Group. License text (the README's LEGAL ISSUES section) in licenses/IJG-libjpeg.txt.
- libpng: PNG Reference Library License version 2, full text in licenses/libpng-2.0.txt.
- The English model eng.traineddata (assets/tessdata) is from https://github.com/tesseract-ocr/tessdata_fast, Apache-2.0.

| Component | Version | License (from its POM) |
|---|---|---|
| `cz.adaptech.tesseract4android:tesseract4android` | 4.9.0 | (none in its POM; see above) |

## Lazysodium for Android and libsodium (server sync's encryption, from 1.4.0)

License: MPL-2.0 (Lazysodium, licenses/MPL-2.0.txt); its bundled libsodium is under the ISC license (licenses/ISC-libsodium.txt)  
Upstream: https://github.com/terl/lazysodium-android and https://github.com/jedisct1/libsodium

Lazysodium is used as published, unmodified; its source is at the upstream address above. It ships libsodium.so for each CPU type the app includes (arm64-v8a, armeabi-v7a and x86_64), which contains libsodium (Copyright (c) 2013-2026 Frank Denis). The app calls only libsodium's standard constructions: crypto_box, crypto_box_seal and its random number generator. Neither has network code of its own.

| Component | Version | License (from its POM) |
|---|---|---|
| `com.goterl:lazysodium-android` | 5.2.0 | Mozilla Public License, Version 2.0 |

## JNA, Java Native Access (how Lazysodium calls libsodium, from 1.4.0)

License: Apache-2.0 (licenses/Apache-2.0.txt). JNA is offered under LGPL-2.1-or-later or Apache-2.0, at the user's choice; the app uses it under Apache-2.0.  
Upstream: https://github.com/java-native-access/jna

The Android build of JNA ships its native dispatch library libjnidispatch.so; the app includes it for arm64-v8a, armeabi-v7a and x86_64.

| Component | Version | License (from its POM) |
|---|---|---|
| `net.java.dev.jna:jna` | 5.19.1 | LGPL-2.1-or-later, Apache-2.0 |

## ZXing Android Embedded and ZXing core (Scan QR code, from 1.5.0)

License: Apache-2.0 (licenses/Apache-2.0.txt)  
Upstream: https://github.com/journeyapps/zxing-android-embedded and https://github.com/zxing/zxing

Scan QR code on the Server screen reads the desktop program's Add a phone code with the camera, on the phone, through
ZXing Android Embedded's scanner and ZXing's core decoder. Both are used as published. Neither has network code, and
neither needs Google Play services. The camera permission they bring is asked for only when Scan QR code is tapped.

| Component | Version | License (from its POM) |
|---|---|---|
| `com.journeyapps:zxing-android-embedded` | 4.3.0 | The Apache License, Version 2.0 |
| `com.google.zxing:core` | 3.5.3 | The Apache Software License, Version 2.0 |

## Version alignment only (no code shipped)

These are bills of materials: they only pin the versions of the components above.

| Component | Version | License (from its POM) |
|---|---|---|
| `androidx.compose:compose-bom` | 2026.09.00 | The Apache Software License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom` | 1.9.0 | The Apache Software License, Version 2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom` | 1.7.3 | The Apache Software License, Version 2.0 |

## SQLCipher license (full text)

```
Copyright (c) 2008-2026, ZETETIC, LLC
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the ZETETIC LLC nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ZETETIC LLC ''AS IS'' AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ZETETIC LLC BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
