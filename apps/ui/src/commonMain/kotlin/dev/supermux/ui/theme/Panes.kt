package dev.supermux.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.ui.supermuxDark

/**
 * Slimmed pane palette. Only the genuinely-fixed app tones should be read from here
 * (`code`, `terminal`, `terminalForeground`, `warning`) — everything else now comes
 * through `MaterialTheme.colorScheme` so it follows light/dark.
 *
 * Provided by [SupermuxTheme]; defaults to the dark tones.
 */
val LocalPanes = staticCompositionLocalOf { supermuxDark() }
