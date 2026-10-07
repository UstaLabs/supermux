package dev.supermux.ui.panes

import androidx.compose.ui.input.pointer.PointerIcon

// iOS has no mouse cursor to shape (an attached trackpad's pointer is the system's, and UIKit
// exposes no col-resize/row-resize shape) — same situation as Android, same actual.
actual val ColResizeIcon: PointerIcon = PointerIcon.Default

actual val RowResizeIcon: PointerIcon = PointerIcon.Default
