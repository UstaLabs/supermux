package dev.supermux.ui.chat

import androidx.compose.ui.window.DialogProperties

// A browser tab has no system bars inside it: the dialog already fills the page.
internal actual fun viewerDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)
