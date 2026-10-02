package dev.supermux.ui.panes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun rememberWindowScreenBounds(): () -> IntRect? {
    val window = rememberUpdatedState(LocalAwtWindow.current)
    return remember {
        {
            window.value?.let { w ->
                // AWT bounds are in points; Compose lays out in device pixels.
                val s = w.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
                IntRect(
                    (w.x * s).roundToInt(),
                    (w.y * s).roundToInt(),
                    ((w.x + w.width) * s).roundToInt(),
                    ((w.y + w.height) * s).roundToInt(),
                )
            }
        }
    }
}
