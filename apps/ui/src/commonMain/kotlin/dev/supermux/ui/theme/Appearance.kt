package dev.supermux.ui.theme

/**
 * Light/dark/follow-system choice, persisted by each app (Android: SharedPreferences,
 * desktop: `ui-state.json`) and handed to [SupermuxTheme].
 */
enum class AppearanceMode { SYSTEM, LIGHT, DARK }
