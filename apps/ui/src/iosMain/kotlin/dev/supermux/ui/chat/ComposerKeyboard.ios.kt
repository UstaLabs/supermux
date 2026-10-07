package dev.supermux.ui.chat

import androidx.compose.ui.input.key.KeyEvent

/**
 * Every key event Compose sees on iOS came from a HARDWARE keyboard.
 *
 * UIKit routes software-keyboard Return through the text input protocol (`insertText("\n")`), not
 * through `pressesBegan`, so the on-screen keyboard never produces a `UIPress` and therefore never
 * reaches this seam at all. Only a physical Bluetooth/Smart Keyboard does — which is exactly the
 * "Enter sends" case. The soft Return keeps inserting a newline because it never asks.
 */
actual fun KeyEvent.isFromPhysicalKeyboard(): Boolean = true
