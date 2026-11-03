package dev.supermux.ui.chat

import androidx.compose.ui.window.DialogProperties

// A desktop window has no system bars inside it: the dialog already fills the window.
internal actual fun viewerDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)
