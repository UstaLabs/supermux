package dev.supermux.ui.panes

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntRect

// No edge-dragged windows here: rotation, a fold or a browser resize reshapes
// the whole screen, and proportional scaling is the right answer for that.
@Composable
actual fun rememberWindowScreenBounds(): () -> IntRect? = { null }
