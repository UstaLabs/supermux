// CSS cursor keywords. `PointerIcon.fromKeyword` is Compose-for-Web's public factory over
// `style.cursor` (the internal `BrowserCursor`), and is the only way to name a cursor the common
// `PointerIcon` companion does not already have — hence the opt-in.
@file:OptIn(ExperimentalComposeUiApi::class)

package dev.supermux.ui.panes

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.fromKeyword

actual val ColResizeIcon: PointerIcon = PointerIcon.fromKeyword("col-resize")
actual val RowResizeIcon: PointerIcon = PointerIcon.fromKeyword("row-resize")
