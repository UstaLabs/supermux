package dev.supermux.ui.adaptive

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed

// Byte-identical to the jvm and android actuals: `PointerButtons.isSecondaryPressed` is declared
// per-platform in Compose Multiplatform, so commonMain cannot name it even though every platform
// has it. An iPad trackpad DOES report a secondary button, so this is live code here, not a stub.
internal actual fun PointerEvent.isSecondaryButtonPress(): Boolean =
    type == PointerEventType.Press && buttons.isSecondaryPressed

internal actual fun PointerEvent.isTertiaryButtonPress(): Boolean =
    type == PointerEventType.Press && buttons.isTertiaryPressed
