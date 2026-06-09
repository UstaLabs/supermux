package dev.supermux.android.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/** Keep a panel composed but hidden when [visible] is false (web-style v-show). */
@Stable
fun Modifier.keepAlivePanel(visible: Boolean): Modifier = this
    .fillMaxSize()
    .zIndex(if (visible) 1f else 0f)
    .alpha(if (visible) 1f else 0f)
    .then(
        if (!visible) {
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            }
        } else {
            Modifier
        },
    )
