package dev.supermux.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.UiPrefs

/**
 * The composition locals every host of [dev.supermux.ui.shell.SupermuxApp] must provide, plus
 * [SupermuxTheme] — in one place, so a new host cannot forget one.
 *
 * Each app's theme wrapper had grown its own copy of this provider stack, and the copies were the
 * kind that drift silently: forgetting `LocalHaptics` costs no compile error and simply makes every
 * haptic in the app a no-op, and forgetting `LocalUiPrefs` throws only once the user opens a screen
 * that reads a preference. What is genuinely per-host — Android's system-bar icon contrast, iOS's
 * snackbar overlay and hardware-input detection, desktop's context-menu representation — stays in
 * the host wrapper, above or below this call.
 *
 * [LocalHaptics] is provided from `platform.haptics` rather than taken as its own parameter,
 * because the two must not be able to disagree: `rememberHaptics()` and `LocalPlatform.current
 * .haptics` are read by different call sites for the same actuator.
 *
 * No typography is passed. The shared theme derives it from [widthClass] alone, which is why that
 * is provided ABOVE `SupermuxTheme` here and not beside it.
 *
 * @param uiPrefs the persisted UI preferences. `null` installs a process-local store that behaves
 *   identically and persists nothing — right for previews and debug entry points, never for an app.
 */
@Composable
fun HostTheme(
    platform: Platform,
    appearance: AppearanceMode = AppearanceMode.SYSTEM,
    textScale: Float = 1f,
    uiPrefs: UiPrefs? = null,
    inputMode: InputMode = InputMode.Pointer,
    pointerAvailable: Boolean = true,
    widthClass: WindowWidthClass = WindowWidthClass.Expanded,
    content: @Composable () -> Unit,
) {
    val prefs = uiPrefs ?: remember { UiPrefs(InMemorySettingsStore()) }
    CompositionLocalProvider(
        LocalPlatform provides platform,
        LocalHaptics provides platform.haptics,
        LocalWindowWidthClass provides widthClass,
        LocalInputMode provides inputMode,
        // Hit-target sizing asks for a real mouse/touchpad, never the keyboard — a phone or tablet
        // with a Bluetooth keyboard is still a thumb device. See ui/adaptive/InputMode.kt.
        LocalPointerAvailable provides pointerAvailable,
        LocalUiPrefs provides prefs,
    ) {
        SupermuxTheme(appearance = appearance, textScale = textScale, content = content)
    }
}
