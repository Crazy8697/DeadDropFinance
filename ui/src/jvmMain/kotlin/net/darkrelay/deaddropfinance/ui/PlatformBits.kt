package net.darkrelay.deaddropfinance.ui

import androidx.compose.runtime.Composable

/** A file the user picked (a Save As or Open dialog): on the program, the file itself. */
typealias PickedUri = java.io.File

/** No system Back on the program: each screen has its own Back. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {}
