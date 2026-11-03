package dev.supermux.ui.adaptive

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The window's own controls, drawn by the OS over the app's content: the band at the top they sit
 * in, and how far in from each side they reach (the macOS traffic lights at the start, Windows'
 * caption buttons at the end). A full-window overlay, the chat's media viewer, lays its top bar in
 * that band and keeps clear of them. All zero where the OS draws its title bar outside the content.
 */
@Immutable
data class WindowControlsInsets(val band: Dp = 0.dp, val start: Dp = 0.dp, val end: Dp = 0.dp)

/** This window's [WindowControlsInsets]: the desktop app provides them; every other host has none. */
val LocalWindowControlsInsets = staticCompositionLocalOf { WindowControlsInsets() }
