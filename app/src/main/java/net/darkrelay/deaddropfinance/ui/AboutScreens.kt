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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.R

/** Where About and Help link. */
object DarkRelayLinks {
    const val SITE = "https://darkrelay.net"
    const val CHEESE = "https://buymeacoffee.com/crazy8697"
    const val SIMPLEFIN = "https://www.simplefin.org"
}

/** The documents the app carries (assets/licenses), by file name, with what each one is. */
val APP_DOCS = listOf(
    "LICENSE" to "Dead Drop Finance license (Apache License 2.0)",
    "NOTICE" to "Notice",
    "THIRD-PARTY-NOTICES.md" to "Third-party notices",
    "PRIVACY.md" to "Privacy policy",
    "TERMS.md" to "End user agreement",
)

/** Credits for what the app is built with: the services and every shipped component whose license asks for one. */
val CREDITS = listOf(
    "Bank sync" to "SimpleFIN Bridge, from SimpleFIN (simplefin.org). A separate service you sign up for with SimpleFIN; DarkRelay isn't affiliated with it.",
    "Encrypted database" to "SQLCipher for Android, Copyright (c) 2008-2026, ZETETIC, LLC, under its BSD-style license. It contains SQLite and LibTomCrypt, both in the public domain.",
    "Receipt text recognition" to "Tesseract OCR and Tesseract4Android (Apache License 2.0), with Leptonica (Copyright (C) 2001-2020 Leptonica), libpng, and libjpeg: this software is based in part on the work of the Independent JPEG Group.",
    "Kotlin" to "The Kotlin standard library, kotlinx.coroutines (Copyright 2016-2025 JetBrains s.r.o and contributors) and kotlinx.serialization (Copyright 2017-2019 JetBrains s.r.o and respective authors and developers), Apache License 2.0. Parts of the standard library come from ThreeTen-Backport (Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos) and Boost (Copyright Eric Ford & Hubert Holin 2001).",
    "Android" to "AndroidX, Jetpack Compose, Room and WorkManager from The Android Open Source Project, Apache License 2.0.",
)

/** About: the icon, name, version, license, credits, Licenses, the documents, darkrelay.net and support. */
@Composable
fun AboutScreen(version: String, onBack: () -> Unit, onOpen: (String) -> Unit, onLicenses: () -> Unit, onDoc: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("About", onBack)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_ddf_master), contentDescription = "Dead Drop Finance icon", modifier = Modifier.size(96.dp))
            Gap()
            Text("Dead Drop Finance", style = MaterialTheme.typography.titleLarge, color = DdfColors.Cyan)
            Text("Version $version", color = DdfColors.Muted)
            Gap(4)
            Text("Free and open source under the Apache License 2.0. Copyright 2026 DarkRelay.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onOpen(DarkRelayLinks.SITE) }) { Text("darkrelay.net", color = DdfColors.Cyan) }
            OutlinedButton(onClick = { onOpen(DarkRelayLinks.CHEESE) }) { Text("Buy DarkRelay some cheese 🧀") }
        }
        SectionTitle("Credits")
        CREDITS.forEach { (what, who) ->
            Text(what, modifier = Modifier.padding(top = 6.dp))
            Text(who, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { onOpen(DarkRelayLinks.SIMPLEFIN) }) { Text("simplefin.org", color = DdfColors.Cyan) }
        SectionTitle("Legal")
        ListRow(onLicenses) { Column { Text("Licenses"); Text("Full license texts for the app and everything it ships", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) } }
        ListRow({ onDoc("PRIVACY.md") }) { Text("Privacy policy") }
        ListRow({ onDoc("TERMS.md") }) { Text("End user agreement") }
        Gap(24)
    }
}

/** Help: Licenses, Support DarkRelay, and Check for updates. */
@Composable
fun HelpScreen(checking: Boolean, updateMessage: String?, onBack: () -> Unit, onLicenses: () -> Unit, onSupport: () -> Unit, onCheck: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Help", onBack)
        ListRow(onLicenses) { Column { Text("Licenses"); Text("The app's license and every component's", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) } }
        ListRow(onSupport) { Column { Text("Support DarkRelay"); Text("Buy DarkRelay some cheese 🧀", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) } }
        ListRow(if (checking) null else onCheck) {
            Column {
                Text(if (checking) "Checking..." else "Check for updates")
                Text(updateMessage ?: "Asks GitHub for a newer version. Nothing about you is sent.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
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
