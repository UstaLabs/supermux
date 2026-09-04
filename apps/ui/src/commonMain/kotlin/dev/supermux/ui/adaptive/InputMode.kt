package dev.supermux.ui.adaptive

import androidx.compose.runtime.compositionLocalOf

/**
 * How the user drives the UI. Screens use it for affordance sizing/behaviour that width alone
 * does not settle: hover states, right-click menus and dense hit targets are [Pointer]-only;
 * swipe actions, long-press menus and 48dp targets are [Touch].
 */
enum class InputMode { Touch, Pointer }

/**
 * Current input mode. Defaults to [InputMode.Pointer] (desktop, previews, tests). Android's
 * entry point provides [InputMode.Touch] unless a hardware keyboard or mouse/touchpad is
 * attached (DeX, Chromebook, docked tablet) — see `android/platform/InputModeDetector.kt`.
 */
val LocalInputMode = compositionLocalOf { InputMode.Pointer }
