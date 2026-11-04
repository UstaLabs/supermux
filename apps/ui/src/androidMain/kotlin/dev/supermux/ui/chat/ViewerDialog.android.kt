package dev.supermux.ui.chat

import androidx.compose.ui.window.DialogProperties

// Without decorFitsSystemWindows = false the dialog's window stops at the navigation bar and the
// activity shows through, dimmed, behind the buttons.
internal actual fun viewerDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
