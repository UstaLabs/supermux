package dev.supermux.android.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Semantically-named haptic kinds.
 *
 *  - [Tick]    — light click for low-weight actions (session open, picker selection, mic start/stop)
 *  - [Confirm] — firm confirm for send and other affirmative completions
 *  - [Heavy]   — strong weight for destructive actions (kill confirm)
 */
enum class HapticKind { Tick, Confirm, Heavy }

/**
 * Returns a stable lambda `(HapticKind) -> Unit` that fires platform haptics.
 *
 * API level guards:
 *  - [HapticKind.Tick]    → CONTEXT_CLICK (API 23+) — always available on our minSdk 26.
 *  - [HapticKind.Confirm] → CONFIRM (API 30+), fallback KEYBOARD_TAP.
 *  - [HapticKind.Heavy]   → REJECT (API 30+), fallback LONG_PRESS.
 */
@Composable
fun rememberHaptics(): (HapticKind) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { kind ->
            val constant = when (kind) {
                HapticKind.Tick -> HapticFeedbackConstants.CONTEXT_CLICK
                HapticKind.Confirm ->
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.KEYBOARD_TAP
                HapticKind.Heavy ->
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
                    else HapticFeedbackConstants.LONG_PRESS
            }
            view.performHapticFeedback(constant)
        }
    }
}
