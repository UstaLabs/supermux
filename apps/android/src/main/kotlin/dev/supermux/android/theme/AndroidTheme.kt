package dev.supermux.android.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.supermux.android.platform.AndroidHaptics
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.LocalHaptics
import dev.supermux.ui.theme.SupermuxTheme

/**
 * Android's thin wrapper over the shared [SupermuxTheme]: edge-to-edge system-bar icon contrast
 * plus the platform haptics implementation.
 *
 * No typography is passed — the shared theme picks the touch or pointer scale from
 * `LocalWindowWidthClass`/`LocalInputMode`, both provided at the `MainActivity` root.
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
    CompositionLocalProvider(LocalHaptics provides haptics) {
        SupermuxTheme(
            appearance = appearance,
            textScale = textScale,
            content = content,
        )
    }
}
