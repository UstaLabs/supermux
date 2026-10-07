package dev.supermux.ui.chat

import androidx.compose.ui.input.key.KeyEvent

/** Browser key events are keyboard events; soft keyboards commit text through the IME path. */
actual fun KeyEvent.isFromPhysicalKeyboard(): Boolean = true
