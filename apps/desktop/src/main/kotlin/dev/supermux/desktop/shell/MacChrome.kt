// macOS window chrome constants for the Compose Desktop client when the title bar is
// transparent + full-size (see Main.kt Window rootPane client properties). Content paints
// edge-to-edge; only a LEFT inset under the traffic lights stays free of critical controls.
package dev.supermux.desktop.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height of the standard macOS title bar (28pt). Traffic lights are vertically centered in this
 * band; our sidebar toggle sits in the same row next to them.
 */
val MacTitleBarHeight = 28.dp

/**
 * Fallback horizontal room for close / minimize / zoom when JBR's [CustomTitleBar.getLeftInset]
 * is unavailable. Windowed macOS keeps the lights in a ~78pt cluster; native fullscreen hides
 * them, and [macTrafficLightsStartPadding] drops this to a small gutter.
 */
val MacTrafficLightsWidth = 78.dp

/** Gutter used when traffic lights are gone (native fullscreen) so the toggle isn't flush. */
val MacTrafficLightsHiddenGutter = 8.dp

/**
 * Start padding for title-bar chrome (sidebar collapse). Prefer JBR's live left inset — it
 * tracks traffic-light size/visibility. Zero inset (fullscreen) and the no-JBR fullscreen
 * fallback both use [MacTrafficLightsHiddenGutter].
 */
fun macTrafficLightsStartPadding(nativeLeftInset: Float?, fullscreen: Boolean): Dp {
    if (nativeLeftInset != null) {
        return if (nativeLeftInset <= 0f) MacTrafficLightsHiddenGutter else nativeLeftInset.dp
    }
    return if (fullscreen) MacTrafficLightsHiddenGutter else MacTrafficLightsWidth
}

/**
 * Live start padding for macOS title-bar controls. Provided from [rememberMacWindowChrome]
 * while the window is composed; defaults to the windowed fallback.
 */
val LocalMacTrafficLightsInset = staticCompositionLocalOf { MacTrafficLightsWidth }
