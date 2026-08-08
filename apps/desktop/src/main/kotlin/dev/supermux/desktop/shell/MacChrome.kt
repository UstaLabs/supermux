// macOS window chrome constants for the Compose Desktop client when the title bar is
// transparent + full-size (see Main.kt Window rootPane client properties). Content paints
// edge-to-edge; only a LEFT inset under the traffic lights stays free of critical controls.
package dev.supermux.desktop.shell

import androidx.compose.ui.unit.dp

/**
 * Height of the standard macOS title bar (28pt). Traffic lights are vertically centered in this
 * band; our sidebar toggle sits in the same row next to them.
 */
val MacTitleBarHeight = 28.dp

/**
 * Horizontal room reserved for the close / minimize / zoom buttons under full-size content.
 * Toolbar items (sidebar toggle) start after this inset so they never sit under the traffic lights.
 */
val MacTrafficLightsWidth = 78.dp
