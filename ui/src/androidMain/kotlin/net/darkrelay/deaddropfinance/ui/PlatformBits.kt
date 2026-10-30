package net.darkrelay.deaddropfinance.ui

import androidx.compose.runtime.Composable

/** A file the user picked or a photo the camera took: on the phone, its content address. */
typealias PickedUri = android.net.Uri

/** The system Back gesture and button. */
@Composable
fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)
