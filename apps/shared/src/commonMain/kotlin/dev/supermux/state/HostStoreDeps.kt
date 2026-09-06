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
    const val LAUNCHER_DRAFT = "launcher:draft"
    const val LAUNCHER_PREFS = "launcher:prefs"
    const val HOST_FILTER = "host:filter"

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
