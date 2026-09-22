package dev.supermux.android.platform

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.Haptics

/**
 * Fires platform haptics through the hosting [View].
 *
 * API level guards:
 *  - [HapticKind.Tick]    → CONTEXT_CLICK (API 23+) — always available on our minSdk 26.
 *  - [HapticKind.Confirm] → CONFIRM (API 30+), fallback KEYBOARD_TAP.
 *  - [HapticKind.Heavy]   → REJECT (API 30+), fallback LONG_PRESS.
 *
 * Installed by `AndroidTheme` via `LocalHaptics`; cluster A task A4 moves it behind `Platform`.
 */
class AndroidHaptics(private val view: View) : Haptics {
    override fun perform(kind: HapticKind) {
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
