package dev.supermux.ui.nav

/**
 * The pages of Settings (#97, structure C), in rail order. [Machine] is the active machine's own page: opened from
 * a machine card or the footer's Devices icon, never a rail row. A route's `section` of null means "just
 * Settings" (the index on a phone, General on a wide window) — see [Route.Settings].
 */
enum class SettingsSection(val label: String) {
    General("General"),
    Providers("Providers & accounts"),
    Voice("Voice"),
    Coding("Coding"),
    Memory("Memory"),
    PrivacyLabs("Privacy & labs"),
    Machine("Machine"),
}
