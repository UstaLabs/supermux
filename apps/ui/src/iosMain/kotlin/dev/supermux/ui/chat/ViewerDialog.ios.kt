package dev.supermux.ui.chat

import androidx.compose.ui.window.DialogProperties

// Under the status bar and the home indicator, as on Android.
internal actual fun viewerDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false)
