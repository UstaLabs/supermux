package dev.supermux.android.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.view.WindowCompat
import dev.supermux.android.platform.AndroidHaptics
import dev.supermux.android.platform.rememberInputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.widthClassFor
import dev.supermux.ui.adaptive.widthClassForPx
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.LocalHaptics
import dev.supermux.ui.theme.SupermuxTheme

/**
 * Android's thin wrapper over the shared [SupermuxTheme]: edge-to-edge system-bar icon contrast
 * plus the platform haptics implementation.
 *
 * It also provides the adaptive locals ABOVE the shared theme, so every Android entry point —
 * `MainActivity` and the debug preview activities — gets them from one place and the theme can
 * pick its type scale from the width class:
 *  - `LocalWindowWidthClass` from the window's own bounds (`containerSize` ÷ density) — the same
 *    measurement desktop uses, and the same WindowMetrics-based one the old Material3 window
 *    size class helper used; NOT `Configuration.screenWidthDp`, which excludes the system bars
 *    before API 35.
 *  - `LocalInputMode` = `Touch` unless a hardware keyboard or mouse/touchpad is attached.
 *
 * No typography is passed: the shared theme derives it from the width class alone.
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
    val haptics = remember(view) { AndroidHaptics(view) }
    val widthPx = LocalWindowInfo.current.containerSize.width
    val density = LocalDensity.current.density
    // `containerSize` is 0 during the very first composition (composition precedes measure), and
    // the shared helper maps 0 → Expanded — right for desktop, wrong for a phone (one frame of the
    // desktop type scale). Fall back to the configuration width until the window has measured.
    val widthClass =
        if (widthPx > 0) widthClassForPx(widthPx, density)
        else widthClassFor(LocalConfiguration.current.screenWidthDp)
    CompositionLocalProvider(
        LocalHaptics provides haptics,
        LocalWindowWidthClass provides widthClass,
        LocalInputMode provides rememberInputMode(),
    ) {
        SupermuxTheme(
            appearance = appearance,
            textScale = textScale,
            content = content,
        )
    }
}
