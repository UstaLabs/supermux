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

/**
 * Whether a real pointing device — a mouse or a touchpad — is driving the UI.
 *
 * Deliberately NOT the same question as [LocalInputMode]. That one folds a hardware keyboard in,
 * because a keyboard is what makes shortcut hints and Enter-to-submit worth showing; but a phone
 * with a Bluetooth keyboard is still a TOUCH device, and sizing a 28dp menu row off it would put
 * half-thumb targets on a handset. Anything that sizes a hit target asks THIS instead.
 *
 * Defaults to `true` (desktop, previews, tests). Android provides `hasPointerDevice()` — mouse or
 * touchpad only, never the keyboard.
 */
val LocalPointerAvailable = compositionLocalOf { true }
