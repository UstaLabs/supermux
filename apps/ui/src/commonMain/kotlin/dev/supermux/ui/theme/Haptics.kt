package dev.supermux.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Semantically-named haptic kinds.
 *
 *  - [Tick]    — light click for low-weight actions (session open, picker selection, mic start/stop)
 *  - [Confirm] — firm confirm for send and other affirmative completions
 *  - [Heavy]   — strong weight for destructive actions (kill confirm)
 *
 * The union of both apps' kinds: Android maps them onto `HapticFeedbackConstants`, desktop has no
 * actuator and stays on [NoHaptics].
 */
enum class HapticKind { Tick, Confirm, Heavy }

/** Fires platform haptics. The implementation is supplied by the app through [LocalHaptics]. */
interface Haptics {
    fun perform(kind: HapticKind)
}

/** The default: every kind is a no-op (desktop, previews, tests). */
object NoHaptics : Haptics {
    override fun perform(kind: HapticKind) = Unit
}

/**
 * Read via [rememberHaptics]. Provided by the app theme wrapper (`AndroidTheme` installs
 * `AndroidHaptics`); defaults to [NoHaptics] so an unprovided call site never throws.
 */
val LocalHaptics = staticCompositionLocalOf<Haptics> { NoHaptics }

/** The haptics implementation for this composition. `haptics.perform(HapticKind.Tick)`. */
@Composable
fun rememberHaptics(): Haptics = LocalHaptics.current
