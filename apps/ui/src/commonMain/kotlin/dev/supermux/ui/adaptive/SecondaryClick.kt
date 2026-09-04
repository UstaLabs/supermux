package dev.supermux.ui.adaptive

import androidx.compose.ui.input.pointer.PointerEvent

/**
 * True when [this] is the *press* half of a right-click (secondary mouse button).
 *
 * `PointerButtons.isSecondaryPressed` exists on both targets but is declared per-platform in
 * Compose Multiplatform (`PointerEvent.skiko.kt` / `PointerEvent.android.kt`), so commonMain cannot
 * name it — hence this one-line seam rather than a per-app copy of the gesture.
 *
 * Press, not release: a context menu must open on button-down, which is what both desktop toolkits
 * do and what a mouse on an Android tablet reports too.
 */
internal expect fun PointerEvent.isSecondaryButtonPress(): Boolean
