package dev.supermux.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The OS asks for less motion (macOS "Reduce motion", GNOME with animations off, Android's animator scale at 0,
 * iOS "Reduce Motion"). False unless a platform root provides it. Card #76: the live reply then shows whole words at
 * the steady pace, with no ink edge, and the transcript jumps instead of gliding.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }
