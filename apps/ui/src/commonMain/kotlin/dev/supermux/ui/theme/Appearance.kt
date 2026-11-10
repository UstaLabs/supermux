package dev.supermux.ui.theme

/**
 * Light/dark/follow-system choice, persisted by each app (Android: SharedPreferences,
 * desktop: `ui-state.json`) and handed to [SupermuxTheme].
 */
enum class AppearanceMode { SYSTEM, LIGHT, DARK }

/**
 * What is on screen for a [stored] choice: nothing stored is the host's [default] (desktop Dark, the others System),
 * and System is whatever the platform's dark check says.
 */
fun paintedAppearance(stored: AppearanceMode?, default: AppearanceMode, systemDark: Boolean): AppearanceMode =
    when (val mode = stored ?: default) {
        AppearanceMode.SYSTEM -> if (systemDark) AppearanceMode.DARK else AppearanceMode.LIGHT
        else -> mode
    }

/**
 * The sidebar's theme shortcut: the opposite of what is PAINTED (#97 spec §10.6). Flipping the stored value instead
 * wrote Dark over a System that already painted dark, and the shortcut seemed to do nothing.
 */
fun nextAppearance(stored: AppearanceMode?, default: AppearanceMode, systemDark: Boolean): AppearanceMode =
    if (paintedAppearance(stored, default, systemDark) == AppearanceMode.DARK) AppearanceMode.LIGHT else AppearanceMode.DARK
