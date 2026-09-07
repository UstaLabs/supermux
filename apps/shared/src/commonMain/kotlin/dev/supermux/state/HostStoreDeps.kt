package dev.supermux.state

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

/** Async key-value settings. One actual per platform (DataStore on Android, JSON file on desktop). */
interface SettingsStore {
    fun string(key: String): Flow<String?>
    suspend fun putString(key: String, value: String?)
}

/** Every key both apps persist, in one place so they store the same thing. */
object SettingsKeys {
    fun draft(sessionId: String) = "draft:$sessionId"
    /** Serialized [LauncherPrefs] — the launcher's sticky agent/model/effort choices.
     *  Read/written through `dev.supermux.ui.prefs.UiPrefs` (one owner, both platforms). */
    const val LAUNCHER_PREFS = "launcher:prefs"
    /** Serialized [LauncherDraft] — the in-progress new-session draft. Absent = no draft. */
    const val LAUNCHER_DRAFT = "launcher:draft"
    const val HOST_FILTER = "host:filter"

    /** JSON array of the session list's collapsed project-group keys (the PA group uses the
     *  "__pas__" sentinel). Replaced Android's `cmux-session-list` SharedPreferences and desktop's
     *  `ui-state.json` `collapsedProjectPaths`; both migrate once (cluster F1). */
    const val SESSION_LIST_COLLAPSED_PATHS = "sessionList:collapsedPaths"

    // Editor + chat-detail UI preferences (read/written through `dev.supermux.ui.prefs.UiPrefs`).
    // They replaced the per-app stores (Android SharedPreferences `cmux-editor-settings` /
    // `cmux-chat-detail`, desktop `editor-settings.json` / the `dev/supermux/desktop/chat_detail`
    // java.util.prefs node). No migration by design — the values reset once.
    /** "true"/"false" — soft wrap in the code editor. Default true. */
    const val EDITOR_LINE_WRAP = "editor:lineWrap"
    /** Editor font size in px, clamped 10..24. Default 13. */
    const val EDITOR_FONT_SIZE = "editor:fontSize"
    /** "true"/"false" — desktop Changes pane: nested tree (true) vs flat list. Default true. */
    const val EDITOR_DIFF_TREE_VIEW = "editor:diffTreeView"
    /** `ChatDetailLevel.wire` ("low"/"medium"/"high"). Default "medium". */
    const val CHAT_DETAIL_LEVEL = "chatDetail:level"

    // Appearance (cluster E7) — the ONE source of truth for the app's own look on every platform.
    // The shared `ui/settings/AppearanceSettingsScreen.kt` writes these; each app's theme wrapper
    // reads them (Android's MainActivity, desktop's Main + the sidebar theme toggle), so a change
    // made in Settings and a change made by desktop's toggle are the same stored value.
    //
    // Unlike the EDITOR_* keys above these DO migrate: Android's `cmux-editor-settings`
    // SharedPreferences (`AppearancePrefsMigration`) and desktop's `ui-state.json` `appearance`
    // field are read once, on first launch after the upgrade, when the key here is still unset.
    /** `AppearanceMode.name` ("SYSTEM"/"LIGHT"/"DARK"). Unset = the app's own default. */
    const val APPEARANCE = "appearance:mode"
    /** "true"/"false" — Material You opt-in. Persisted everywhere, honoured nowhere (brand palette). */
    const val DYNAMIC_COLOR = "appearance:dynamicColor"
    /** App-wide text-size multiplier, clamped 0.9..1.3. Default 1.0. */
    const val TEXT_SCALE = "appearance:textScale"
    /**
     * The first-run intro version this device has already seen, as a decimal int ("1"). Absent
     * = never seen. Read/written through `dev.supermux.ui.prefs.UiPrefs`; bumping
     * `dev.supermux.ui.intro.INTRO_VERSION` re-shows a redesigned intro to existing users.
     *
     * Replaced desktop's `intro-seen` marker file next to `auth.json` (cluster G6), which is
     * drained once, synchronously, on first launch after the upgrade. Android had no flag at
     * all — its intro was gated on "not paired yet" — so it starts empty and simply writes one.
     */
    const val INTRO_SEEN = "intro:seen"
    // The shell's own screen-level state (cluster G8). It replaced desktop's `ui-state.json`
    // fields — that file now carries only the detached-window bounds, which are desktop's alone —
    // and Android's `rememberSaveable`-only sidebar chrome, which never survived a restart.
    // Desktop drains the old JSON once, on first launch after the upgrade.
    /** "true"/"false" — the workspace sidebar is collapsed to its avatar rail. Default false. */
    const val SHELL_SIDEBAR_COLLAPSED = "shell:sidebarCollapsed"
    /** Sidebar width in dp, clamped 220..560. Default 320. */
    const val SHELL_SIDEBAR_WIDTH = "shell:sidebarWidthDp"
    /** The session the shell reopens on. Absent = open on the list. Written only by a host that
     *  restores its selection (desktop); a phone deliberately opens on the session list. */
    const val SHELL_SELECTED_SESSION = "shell:selectedSession"

    const val VOICE_STT = "voice:stt"
    const val VOICE_CLEANUP = "voice:cleanup"
    const val VOICE_TTS = "voice:tts"
}

/** The whole platform surface a HostStore / FleetStore needs. */
class HostStoreDeps(
    /** `timeoutMs == null` → engine default; `120_000` for the long dictation POST. WebSockets plugin always installed. */
    val httpFactory: (timeoutMs: Long?) -> HttpClient,
    val settings: SettingsStore,
    val clock: Clock = Clock.System,
) {
    fun nowIso(): String = clock.now().toString()
    fun nowMs(): Long = clock.now().toEpochMilliseconds()
}
