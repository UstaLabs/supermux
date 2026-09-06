// The UI's own persisted preferences, on top of the shared [SettingsStore].
//
// Before this file each app had its own store for the same four values: Android's
// `cmux-editor-settings` / `cmux-chat-detail` preference files and desktop's
// `editor-settings.json` / its `chat_detail` user-preferences node. They now go
// through `:shared`'s SettingsStore (DataStore on Android, settings.json on desktop) under the
// `SettingsKeys.EDITOR_*` / `CHAT_DETAIL_*` keys, so a screen moved into `:ui` reads its own
// preferences without knowing which platform it is on.
//
// NO MIGRATION by design (spec) for the editor/chat values: the old stores are orphaned and each
// falls back to its default once, on first launch after the upgrade. The APPEARANCE values added
// in cluster E7 are the exception — losing someone's theme is visible on every screen, so each app
// seeds the key from its old store once (Android `AppearancePrefsMigration`, desktop's
// `ui-state.json` `appearance` field) when nothing is stored here yet.
package dev.supermux.ui.prefs

import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.state.LauncherDraft
import dev.supermux.state.LauncherPrefs
import dev.supermux.state.SettingsKeys
import dev.supermux.state.SettingsStore
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.ui.ThemeDefaults
import dev.supermux.ui.sanitizeSetLevel
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.TEXT_SCALE_MAX
import dev.supermux.ui.theme.TEXT_SCALE_MIN
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Editor font-size bounds — the cm6 bundle's own range, shared by both editor engines. */
const val EDITOR_FONT_MIN = 10
const val EDITOR_FONT_MAX = 24
const val EDITOR_FONT_DEFAULT = 13

/** Soft wrap is on by default (both apps agreed). */
const val EDITOR_LINE_WRAP_DEFAULT = true

/** Neutral text size — the Appearance slider's centre. */
const val TEXT_SCALE_DEFAULT = 1f

/** Desktop Changes pane starts as a nested folder tree. */
const val EDITOR_DIFF_TREE_VIEW_DEFAULT = true

/**
 * Typed accessors over the persisted UI preferences. One `Flow` read + one `suspend put` per
 * value; the reads carry the same defaults the two apps used before the move, so nothing changes
 * for a user with no stored value.
 *
 * Reads are asynchronous (they are the store's flow): a composable collecting one sees the default
 * for the first frame and the stored value immediately after. That matches how the rest of the
 * shared store is already consumed (drafts, launcher prefs).
 */
class UiPrefs(private val settings: SettingsStore) {

    /** Soft wrap in the code editor. */
    val editorLineWrap: Flow<Boolean> =
        settings.string(SettingsKeys.EDITOR_LINE_WRAP).map { it?.toBooleanStrictOrNull() ?: EDITOR_LINE_WRAP_DEFAULT }

    suspend fun putEditorLineWrap(value: Boolean) =
        settings.putString(SettingsKeys.EDITOR_LINE_WRAP, value.toString())

    /** Editor font size in px, always clamped into [EDITOR_FONT_MIN]..[EDITOR_FONT_MAX]. */
    val editorFontSize: Flow<Int> =
        settings.string(SettingsKeys.EDITOR_FONT_SIZE).map { raw ->
            (raw?.toIntOrNull() ?: EDITOR_FONT_DEFAULT).coerceIn(EDITOR_FONT_MIN, EDITOR_FONT_MAX)
        }

    suspend fun putEditorFontSize(px: Int) =
        settings.putString(SettingsKeys.EDITOR_FONT_SIZE, px.coerceIn(EDITOR_FONT_MIN, EDITOR_FONT_MAX).toString())

    /** Changes pane: nested folder tree (true) vs flat path list (false). */
    val editorDiffTreeView: Flow<Boolean> =
        settings.string(SettingsKeys.EDITOR_DIFF_TREE_VIEW).map { it?.toBooleanStrictOrNull() ?: EDITOR_DIFF_TREE_VIEW_DEFAULT }

    suspend fun putEditorDiffTreeView(value: Boolean) =
        settings.putString(SettingsKeys.EDITOR_DIFF_TREE_VIEW, value.toString())

    /** Chat transcript density (web `cmux:chat-detail` parity). Unknown/absent → MEDIUM. */
    val chatDetailLevel: Flow<ChatDetailLevel> =
        settings.string(SettingsKeys.CHAT_DETAIL_LEVEL).map { ChatDetailLevel.parse(it) }

    /** No-ops for an unimplemented level (`sanitizeSetLevel`), exactly as the old stores did. */
    suspend fun putChatDetailLevel(level: ChatDetailLevel) {
        val next = sanitizeSetLevel(level) ?: return
        settings.putString(SettingsKeys.CHAT_DETAIL_LEVEL, next.wire)
    }

    // ── Appearance (cluster E7) ────────────────────────────────────────────────────────────────
    // One stored value per setting for BOTH apps: the shared Appearance screen writes it, each
    // app's root theme reads it. Desktop's sidebar theme toggle writes the same [appearance] key,
    // so "one source of truth" holds across the two ways to change it.

    /**
     * The chosen theme mode, or `null` when the user has never chosen one.
     *
     * Null is meaningful because the two apps disagree about the fallback (Android follows the
     * system, desktop opens dark), so the DEFAULT belongs to the caller — see [appearance].
     */
    val appearanceMode: Flow<AppearanceMode?> =
        settings.string(SettingsKeys.APPEARANCE).map { raw ->
            raw?.let { name -> AppearanceMode.entries.firstOrNull { it.name == name } }
        }

    /** [appearanceMode] with the caller's platform default substituted for "never chosen". */
    fun appearance(default: AppearanceMode): Flow<AppearanceMode> =
        appearanceMode.map { it ?: default }

    suspend fun putAppearance(mode: AppearanceMode) =
        settings.putString(SettingsKeys.APPEARANCE, mode.name)

    /**
     * Material You opt-in. A NO-OP as far as colour goes — the brand OKLCH palette is the only
     * palette on every platform (`ThemeDefaults.DYNAMIC_COLOR_ENABLED`) — but still stored so a
     * user who turned it on does not silently lose the preference.
     */
    val dynamicColor: Flow<Boolean> =
        settings.string(SettingsKeys.DYNAMIC_COLOR).map {
            ThemeDefaults.dynamicColorEnabled(it?.toBooleanStrictOrNull())
        }

    suspend fun putDynamicColor(value: Boolean) =
        settings.putString(SettingsKeys.DYNAMIC_COLOR, value.toString())

    /** App-wide text multiplier, always clamped into [TEXT_SCALE_MIN]..[TEXT_SCALE_MAX]. */
    val textScale: Flow<Float> =
        settings.string(SettingsKeys.TEXT_SCALE).map { raw ->
            (raw?.toFloatOrNull() ?: TEXT_SCALE_DEFAULT).coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
        }

    suspend fun putTextScale(value: Float) =
        settings.putString(
            SettingsKeys.TEXT_SCALE,
            value.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX).toString(),
        )

    // ── Launcher + session list (cluster F1) ───────────────────────────────────────────────────
    // Previously THREE stores for the same values: `HostStore`/`FleetStore` held the launcher pair
    // over these very keys, desktop wrote `launcher-state.json` and `ui-state.json`, and Android's
    // list wrote a `cmux-session-list` SharedPreferences file. One owner now; each host seeds its
    // old store through once (`seedLauncher` / `seedCollapsedProjectPaths`).

    /** Sticky launcher agent/model/effort choices. Unparsable JSON reads as the defaults. */
    val launcherPrefs: Flow<LauncherPrefs> =
        settings.string(SettingsKeys.LAUNCHER_PREFS).map { raw ->
            raw?.let { runCatching { prefsJson.decodeFromString<LauncherPrefs>(it) }.getOrNull() }
                ?: LauncherPrefs()
        }

    suspend fun putLauncherPrefs(prefs: LauncherPrefs) =
        settings.putString(SettingsKeys.LAUNCHER_PREFS, prefsJson.encodeToString(prefs))

    /** The in-progress new-session draft. Unparsable JSON reads as an empty draft. */
    val launcherDraft: Flow<LauncherDraft> =
        settings.string(SettingsKeys.LAUNCHER_DRAFT).map { raw ->
            raw?.let { runCatching { prefsJson.decodeFromString<LauncherDraft>(it) }.getOrNull() }
                ?: LauncherDraft()
        }

    /** An EMPTY draft clears the key rather than storing `{}` — both stores did this before. */
    suspend fun putLauncherDraft(draft: LauncherDraft) =
        settings.putString(
            SettingsKeys.LAUNCHER_DRAFT,
            if (draft == LauncherDraft()) null else prefsJson.encodeToString(draft),
        )

    /** Drop the draft — the launcher calls this once a session is actually created. */
    suspend fun clearLauncherDraft() = settings.putString(SettingsKeys.LAUNCHER_DRAFT, null)

    /**
     * Project-group keys collapsed in the session list, stored as a JSON array (a path may contain
     * anything, so a delimiter-joined string would not survive). Unparsable JSON reads as empty.
     */
    val collapsedProjectPaths: Flow<Set<String>> =
        settings.string(SettingsKeys.SESSION_LIST_COLLAPSED_PATHS).map { raw ->
            raw?.let {
                runCatching { prefsJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
            }?.toSet() ?: emptySet()
        }

    /** Stored SORTED so the same set never rewrites the file with a different byte string. */
    suspend fun putCollapsedProjectPaths(paths: Set<String>) =
        settings.putString(
            SettingsKeys.SESSION_LIST_COLLAPSED_PATHS,
            prefsJson.encodeToString(ListSerializer(String.serializer()), paths.sorted()),
        )

    // The RAW stored strings. The parsed flows above flatten "never stored" into the defaults,
    // which is exactly what a one-way seed must be able to tell apart.
    internal fun launcherPrefsRaw(): Flow<String?> = settings.string(SettingsKeys.LAUNCHER_PREFS)
    internal fun launcherDraftRaw(): Flow<String?> = settings.string(SettingsKeys.LAUNCHER_DRAFT)
    internal fun collapsedPathsRaw(): Flow<String?> =
        settings.string(SettingsKeys.SESSION_LIST_COLLAPSED_PATHS)

    private companion object {
        val prefsJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

/**
 * The launcher pair, seeded ONCE from a host's legacy store when nothing is stored here yet.
 *
 * One-way, idempotent and non-destructive, exactly like [seedAppearance]: a key the shared store
 * already holds wins (a user who typed a new draft post-upgrade keeps it), and re-running this on
 * every launch is a no-op. Desktop passes what `launcher-state.json` held; Android passes nulls —
 * its launcher pair was ALREADY on these keys (`FleetStore` wrote them), so there is nothing to
 * drain.
 */
suspend fun UiPrefs.seedLauncher(
    legacyPrefs: LauncherPrefs? = null,
    legacyDraft: LauncherDraft? = null,
) {
    if (legacyPrefs != null && legacyPrefs != LauncherPrefs() &&
        launcherPrefsRaw().first() == null
    ) {
        putLauncherPrefs(legacyPrefs)
    }
    if (legacyDraft != null && legacyDraft != LauncherDraft() &&
        launcherDraftRaw().first() == null
    ) {
        putLauncherDraft(legacyDraft)
    }
}

/**
 * The collapsed project-group keys, seeded once from [legacy] (Android's `cmux-session-list`
 * SharedPreferences, desktop's `ui-state.json`) and then READ — synchronously, by both hosts,
 * before the list's first frame, so no frame paints groups expanded that the user had collapsed.
 */
suspend fun UiPrefs.seedCollapsedProjectPaths(legacy: Set<String>?): Set<String> {
    if (!legacy.isNullOrEmpty() && collapsedPathsRaw().first() == null) {
        putCollapsedProjectPaths(legacy)
    }
    return collapsedProjectPaths.first()
}

/**
 * Process-local [SettingsStore] — for previews, tests and any entry point with no real store.
 * Nothing it holds survives the process.
 */
class InMemorySettingsStore : SettingsStore {
    private val map = MutableStateFlow<Map<String, String>>(emptyMap())
    override fun string(key: String): Flow<String?> = map.map { it[key] }
    override suspend fun putString(key: String, value: String?) {
        map.value = if (value == null) map.value - key else map.value + (key to value)
    }
}

/** Provided by each app's theme wrapper (`AndroidTheme` / `DesktopTheme`). */
val LocalUiPrefs = staticCompositionLocalOf<UiPrefs> { error("No UiPrefs provided") }

/**
 * The stored theme mode, seeding it from a host's LEGACY value the first time (and only the first
 * time) nothing is stored yet.
 *
 * Callers read this ONCE, synchronously, before their first frame — desktop's `Main.kt` from its
 * `ShellUiState` initialiser — because a theme resolved asynchronously means one or more frames
 * painted in the wrong one. `DesktopSettingsStore` holds its map in an eager `StateFlow`, so the
 * `first()` inside returns without ever suspending there.
 *
 * @param legacy what this host stored before the value moved here (desktop: `ui-state.json`'s
 *   `appearance` field). Written through so the old file is never consulted again; `null` when
 *   there is nothing to migrate.
 */
suspend fun UiPrefs.seedAppearance(default: AppearanceMode, legacy: AppearanceMode?): AppearanceMode {
    appearanceMode.first()?.let { return it }
    if (legacy == null) return default
    putAppearance(legacy)
    return legacy
}
