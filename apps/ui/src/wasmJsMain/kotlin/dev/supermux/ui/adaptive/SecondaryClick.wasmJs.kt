package dev.supermux.ui.adaptive

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed

internal actual fun PointerEvent.isSecondaryButtonPress(): Boolean =
    type == PointerEventType.Press && buttons.isSecondaryPressed

internal actual fun PointerEvent.isTertiaryButtonPress(): Boolean =
    type == PointerEventType.Press && buttons.isTertiaryPressed
