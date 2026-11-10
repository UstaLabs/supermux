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
    /** The `/` commands last picked or sent to [agent] (newest first, one name per line). */
    fun slashRecents(agent: String) = "slash:recents:$agent"
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
    /** JSON array of `"<agent>/<modelId>"` — the models starred in the composer's run-settings panel. */
    const val STARRED_MODELS = "models:starred"

    // Editor + chat-detail UI preferences (read/written through `dev.supermux.ui.prefs.UiPrefs`).
    // They replaced the per-app stores (Android SharedPreferences `cmux-editor-settings` /
    // `cmux-chat-detail`, desktop `editor-settings.json` / the `dev/supermux/desktop/chat_detail`
    // java.util.prefs node). No migration by design — the values reset once.
    /** "true"/"false" — soft wrap in the code editor. Default true. */
    const val EDITOR_LINE_WRAP = "editor:lineWrap"
    /** Editor font size in px, clamped 10..24. Default 13. */
    const val EDITOR_FONT_SIZE = "editor:fontSize"
    /** Terminal font size in sp, clamped 8..32. Default 13. Cmd/Ctrl +/−/0 and a pinch change it. */
    const val TERMINAL_FONT_SIZE = "terminal:fontSize"
    /** `TerminalAppearance.name` ("FOLLOW_APP"/"DARK"/"LIGHT"). Unset = FOLLOW_APP. */
    const val TERMINAL_APPEARANCE = "terminal:appearance"
    /** "true"/"false" — desktop Changes pane: nested tree (true) vs flat list. Default true. */
    const val EDITOR_DIFF_TREE_VIEW = "editor:diffTreeView"
    /** "true"/"false" — the Changes pane's per-file diff side by side (true) vs inline. Default false (M5). */
    const val EDITOR_DIFF_SIDE_BY_SIDE = "editor:diffSideBySide"
    /** "true"/"false" — the native editor's accessory bar above a soft keyboard. Default true (M5). */
    const val EDITOR_ACCESSORY_BAR = "editor:accessoryBar"
    /** "true"/"false" — the Files tree follows (reveals) the active editor file. Default true. */
    const val FILES_REVEAL_ACTIVE = "files:revealActive"
    /** `ChatDetailLevel.wire` ("low"/"medium"/"high"). Default "medium". */
    const val CHAT_DETAIL_LEVEL = "chatDetail:level"
    /** Card #76: "false" = don't ask for replies while they're written (show finished messages only). Unset = on:
     *  the default writes nothing, so this device's settings file only changes when someone turns it off. */
    const val LIVE_REPLIES = "chat:liveReplies"
    /** `DictationMode.wire` ("broker"/"device") — where THIS device's dictation is recognised.
     *  Device-local on purpose: only some hosts have an on-device recogniser. Unset = the
     *  default rule in `dev.supermux.ui.chat.resolveDictationMode`. */
    const val DICTATION_MODE = "dictation:mode"

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
    /** "true"/"false": the assistant overlay (#62, experimental), the default Agent floating over
     *  other apps. Per device, never synced; unset = off. */
    const val ASSISTANT_OVERLAY = "appearance:assistantOverlay"
    /** The Agent the assistant overlay shows on this device (a session id), picked in its chat's
     *  header. Per device, never synced; unset = the default Agent. */
    const val ASSISTANT_OVERLAY_AGENT = "appearance:assistantOverlayAgent"
    /** "true"/"false": your screen goes with what you say when you hold the overlay to talk (Android asks
     *  once per session). Per device, never synced; unset = on. */
    const val ASSISTANT_OVERLAY_SCREEN = "appearance:assistantOverlayScreen"
    /** The desktop overlay's hold-to-talk key (#62): a `TalkKey` name; none = the default combo. */
    const val ASSISTANT_OVERLAY_TALK_KEY = "appearance:assistantOverlayTalkKey"
    /** Where the desktop overlay's head rests (#62): its spot and screen, as the desktop writes them down. */
    const val ASSISTANT_OVERLAY_SPOT = "appearance:assistantOverlaySpot"
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
    /** The process's attachment cache, shared by every host. Null = no caching (tests, old builds). */
    val media: dev.supermux.media.MediaCache? = null,
    /** Which app this is, told to each broker on connect (TELEMETRY.md). Null = say nothing. */
    val clientInfo: dev.supermux.net.ClientInfo? = null,
    /** The web's downloads (#95), which have no files to land in. Null everywhere else. */
    val browserDownload: BrowserDownload? = null,
) {
    fun nowIso(): String = clock.now().toString()
    fun nowMs(): Long = clock.now().toEpochMilliseconds()
}

/**
 * The web's download: the browser fetches [url] with [headers] straight into a Blob it holds (#95) — the bytes
 * never enter the Wasm heap. Answers the Blob (opaque here) and its size, or null on failure.
 */
fun interface BrowserDownload {
    suspend fun fetch(url: String, headers: Map<String, String>, progress: (received: Long, total: Long?) -> Unit): Pair<Any, Long>?
}

/**
 * The total a [BrowserDownload]'s progress can promise, from the response's `Content-Length` — unless the body came
 * encoded (`Content-Encoding: gzip`, `br`, …): the browser hands the reader the DECODED bytes, so that length counts
 * bytes the progress never sees and the bar would run past 100 %. Null when there is no honest total.
 */
fun browserDownloadTotal(contentLength: String?, contentEncoding: String?): Long? {
    val encoding = contentEncoding?.trim().orEmpty()
    if (encoding.isNotEmpty() && !encoding.equals("identity", ignoreCase = true)) return null
    return contentLength?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
}

/** Card #76: the stored "Show replies as they're written" answer; on unless it says "false". */
fun liveRepliesOn(stored: String?): Boolean = stored?.toBooleanStrictOrNull() ?: true
