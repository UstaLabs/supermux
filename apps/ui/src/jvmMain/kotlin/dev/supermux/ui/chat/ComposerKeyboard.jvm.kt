package dev.supermux.ui.chat

import androidx.compose.ui.input.key.KeyEvent

/** Desktop has no soft keyboard: every key event comes from a real one, so Enter always sends. */
actual fun KeyEvent.isFromPhysicalKeyboard(): Boolean = true
