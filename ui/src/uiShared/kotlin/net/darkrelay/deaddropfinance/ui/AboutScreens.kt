package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Where About links. */
object DarkRelayLinks {
    const val SITE = "https://darkrelay.net"
    const val CHEESE = "https://buymeacoffee.com/crazy8697"
}

/** The documents the app carries (assets/licenses), by file name, with what each one is. */
val APP_DOCS = listOf(
    "LICENSE" to "Dead Drop Finance license (Apache License 2.0)",
    "NOTICE" to "Notice",
    "THIRD-PARTY-NOTICES.md" to "Third-party notices",
    "PRIVACY.md" to "Privacy policy",
    "TERMS.md" to "End user agreement",
)

/** Credits for what the app is built with: every shipped component whose license asks for one. */
val CREDITS = listOf(
    "Encrypted database" to "SQLCipher for Android, Copyright (c) 2008-2026, ZETETIC, LLC, under its BSD-style license. It contains SQLite and LibTomCrypt, both in the public domain.",
    "Receipt text recognition" to "Tesseract OCR and Tesseract4Android (Apache License 2.0), with Leptonica (Copyright (C) 2001-2020 Leptonica), libpng, and libjpeg: this software is based in part on the work of the Independent JPEG Group.",
    "Kotlin" to "The Kotlin standard library, kotlinx.coroutines (Copyright 2016-2025 JetBrains s.r.o and contributors) and kotlinx.serialization (Copyright 2017-2019 JetBrains s.r.o and respective authors and developers), Apache License 2.0. Parts of the standard library come from ThreeTen-Backport (Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos) and Boost (Copyright Eric Ford & Hubert Holin 2001).",
    "Android" to "AndroidX, Jetpack Compose, Room and WorkManager from The Android Open Source Project, Apache License 2.0.",
)

/** The program's credits (0.14.0): what the program ships in place of the phone's Android libraries and engine. */
val PROGRAM_CREDITS = listOf(
    "Encrypted database" to "SQLite JDBC with SQLite3 Multiple Ciphers (Copyright (c) 2019-2026 Ulrich Telle, MIT) and SQLite, in the public domain; Room from The Android Open Source Project, Apache License 2.0.",
    "Receipt text recognition" to "Tesseract OCR through Tess4J and Lept4J (Apache License 2.0), with Leptonica (Copyright (C) 2001-2020 Leptonica), libpng, zlib, LibTIFF, OpenJPEG, libwebp and libjpeg: this software is based in part on the work of the Independent JPEG Group.",
    "PDF receipts" to "Apache PDFBox and FontBox, Copyright 2002-2026 The Apache Software Foundation, Apache License 2.0.",
    "Kotlin" to "The Kotlin standard library, kotlinx.coroutines (Copyright 2016-2025 JetBrains s.r.o and contributors) and kotlinx.serialization (Copyright 2017-2019 JetBrains s.r.o and respective authors and developers), Apache License 2.0.",
    "Windows" to "Compose Multiplatform and Skiko (JetBrains, Apache License 2.0) with Skia (Google, BSD-3-Clause), on OpenJDK (GPL 2.0 with the Classpath Exception).",
)

/** About: the icon, name, version, license, credits, Licenses, the documents, darkrelay.net and support. */
@Composable
fun AboutScreen(version: String, icon: androidx.compose.ui.graphics.painter.Painter?, onBack: () -> Unit, onOpen: (String) -> Unit, onLicenses: () -> Unit, onDoc: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("About", onBack)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Image(icon, contentDescription = "Dead Drop Finance icon", modifier = Modifier.size(96.dp))
            Gap()
            Text("Dead Drop Finance", style = MaterialTheme.typography.titleLarge, color = DdfColors.Cyan)
            Text("Version $version", color = DdfColors.Muted)
            Gap(4)
            Text("Free and open source under the Apache License 2.0. Copyright 2026 DarkRelay.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onOpen(DarkRelayLinks.SITE) }) { Text("darkrelay.net", color = DdfColors.Cyan) }
            OutlinedButton(onClick = { onOpen(DarkRelayLinks.CHEESE) }) { Text("Support DarkRelay: buy some cheese 🧀") }
        }
        SectionTitle("Credits")
        (if (LocalDeviceWords.current.device == "phone") CREDITS else PROGRAM_CREDITS).forEach { (what, who) ->
            Text(what, modifier = Modifier.padding(top = 6.dp))
            Text(who, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        SectionTitle("Legal")
        ListRow(onLicenses) { Column { Text("Licenses"); Text("Full license texts for the ${LocalDeviceWords.current.product} and everything it ships", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) } }
        ListRow({ onDoc("PRIVACY.md") }) { Text("Privacy policy") }
        ListRow({ onDoc("TERMS.md") }) { Text("End user agreement") }
        Gap(24)
    }
}

/** Licenses: the documents and every license text the app carries. */
@Composable
fun LicensesScreen(files: List<String>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Licenses", onBack)
        val titled = APP_DOCS.filter { (name, _) -> name in files }
        titled.forEach { (name, title) -> ListRow({ onOpen(name) }) { Column { Text(title); Text(name, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) } } }
        SectionTitle("License texts")
        files.filter { f -> APP_DOCS.none { it.first == f } }.sorted().forEach { name -> ListRow({ onOpen(name) }) { Text(name) } }
        Gap(24)
    }
}

/** One carried document, as plain text. */
@Composable
fun DocumentScreen(title: String, text: String?, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(title, onBack)
        if (text == null) EmptyState("This document couldn't be read.")
        else SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = if (title.endsWith(".txt")) FontFamily.Monospace else null) }
        Gap(24)
    }
}
