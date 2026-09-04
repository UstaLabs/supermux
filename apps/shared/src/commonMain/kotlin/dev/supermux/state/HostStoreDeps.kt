package dev.supermux.state

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock

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
