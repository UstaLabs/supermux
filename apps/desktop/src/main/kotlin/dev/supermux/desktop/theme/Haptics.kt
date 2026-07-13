package dev.supermux.desktop.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Semantically-named haptic kinds.
 *
 *  - [Tick]    — light click for low-weight actions (session open, picker selection, mic start/stop)
 *  - [Confirm] — firm confirm for send and other affirmative completions
 *  - [Heavy]   — strong weight for destructive actions (kill confirm)
 *
 * Mirrors the Android `theme/Haptics.kt` API surface so ported call sites compile unchanged.
 * Desktop has no haptic actuator, so every kind is a no-op.
 */
enum class HapticKind { Tick, Confirm, Heavy }

/**
 * Returns a stable lambda `(HapticKind) -> Unit`. No-op on desktop — there is no haptic
 * actuator to drive — kept so ported call sites (`rememberHaptics()(HapticKind.Tick)`, etc.)
 * compile unchanged.
 */
@Composable
fun rememberHaptics(): (HapticKind) -> Unit {
    return remember { { _: HapticKind -> } }
}
