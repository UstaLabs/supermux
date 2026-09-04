// The UI's own persisted preferences, on top of the shared [SettingsStore].
//
// Before this file each app had its own store for the same four values: Android's
// `cmux-editor-settings` / `cmux-chat-detail` preference files and desktop's
// `editor-settings.json` / its `chat_detail` user-preferences node. They now go
// through `:shared`'s SettingsStore (DataStore on Android, settings.json on desktop) under the
// `SettingsKeys.EDITOR_*` / `CHAT_DETAIL_*` keys, so a screen moved into `:ui` reads its own
// preferences without knowing which platform it is on.
//
// NO MIGRATION by design (spec): the old stores are orphaned and every value below falls back to
// its default once, on first launch after the upgrade.
package dev.supermux.ui.prefs

import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.state.SettingsKeys
import dev.supermux.state.SettingsStore
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.ui.sanitizeSetLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Editor font-size bounds — the cm6 bundle's own range, shared by both editor engines. */
const val EDITOR_FONT_MIN = 10
const val EDITOR_FONT_MAX = 24
const val EDITOR_FONT_DEFAULT = 13

/** Soft wrap is on by default (both apps agreed). */
const val EDITOR_LINE_WRAP_DEFAULT = true

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
