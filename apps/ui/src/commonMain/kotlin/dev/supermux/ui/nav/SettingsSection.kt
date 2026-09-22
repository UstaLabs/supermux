package dev.supermux.ui.nav

/**
 * Sections of the Settings hub, in rail order. Moved verbatim out of desktop's `shell/AppShell.kt`
 * — Android reaches most of these through its own top-level routes today ([Route.Usage],
 * [Route.Devices], [Route.Proxies], [Route.Appearance]) and both models stay valid: a section is
 * *where inside Settings* you land, a route is *what full screen* is on the stack.
 */
enum class SettingsSection(val label: String) {
    Agents("Agents"),
    Devices("Devices"),
    System("System"),
    GitHosting("Git hosting"),
    Proxies("Proxies"),
    /** PA name + soul.md — distinct from [PersonalAssistants] fleet and [Curator]. */
    Assistant("Identity"),
    /** Nightly ~/.mux curator schedule + run-now. */
    Curator("Curator"),
    Voice("Voice"),
    EditorLsp("Editor / LSP"),
    PersonalAssistants("Personal assistants"),
}
