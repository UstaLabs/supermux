package dev.supermux.android.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.supermux.android.platform.rememberAndroidPlatform
import dev.supermux.android.platform.rememberInputMode
import dev.supermux.android.platform.rememberPointerAvailable
import dev.supermux.ui.adaptive.rememberWindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.HostTheme

/**
 * Android's thin wrapper over the shared theme: edge-to-edge system-bar icon contrast
 * plus the platform services ([dev.supermux.android.platform.AndroidPlatform], including haptics).
 *
 * Everything below the bar-contrast effect is `:ui`'s [HostTheme] — the provider stack (platform,
 * haptics, width class, input mode, UI preferences) plus the shared theme, shared with the iOS host
 * rather than copied per app. Android supplies the two answers only it can give: `Touch` unless a
 * hardware keyboard or mouse is attached, and `screenWidthDp` as the width for the first frame.
 *
 * Dynamic color (Material You) is OFF and has no code path any more — the brand OKLCH palette is
 * the only palette on every platform (2026-07-04 decision, `ThemeDefaults.DYNAMIC_COLOR_ENABLED`
 * is `false`). Settings → Appearance still shows the "Material You" switch and still persists it,
 * but it is now a **no-op**: nothing reads the stored flag to build a scheme.
 */
@Composable
fun AndroidTheme(
    appearance: AppearanceMode = AppearanceMode.SYSTEM,
    textScale: Float = 1f,
    uiPrefs: UiPrefs? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (appearance) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }
    // Status/nav-bar icon contrast follows the app theme (dark icons on a light app).
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    // The whole platform seam (pickers + clipboard + links + haptics) is installed here, so
    // MainActivity and the debug preview activities all get it from one place.
    val platform = rememberAndroidPlatform()
    HostTheme(
        platform = platform,
        appearance = appearance,
        textScale = textScale,
        uiPrefs = uiPrefs,
        inputMode = rememberInputMode(),
        pointerAvailable = rememberPointerAvailable(),
        // `containerSize` is 0 during the very first composition, and the shared helper maps 0 →
        // Expanded — right for desktop, wrong for a phone. `screenWidthDp` covers that one frame.
        // NOT the other way round: `screenWidthDp` excludes the system bars before API 35, so the
        // window's own bounds are the measurement that agrees with desktop and iOS.
        widthClass = rememberWindowWidthClass(LocalConfiguration.current.screenWidthDp),
        content = content,
    )
}
