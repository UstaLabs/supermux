package dev.supermux.ui.panes

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

actual val ColResizeIcon: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))

actual val RowResizeIcon: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR))
