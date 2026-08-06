// Desktop editor settings persistence: soft-wrap + font-size, written to `editor-settings.json` next
// to the token / ui-state stores (ShellStateStore precedent). This is the desktop analog of the
// Android `cmux-editor-settings` SharedPreferences (EditorScreen.kt:145-173): the SAME two keys
// (`lineWrap`, `fontSize`) so the mental model matches across platforms. Not a secret — a plain
// writeString is fine (mirrors ShellStateStore).
//
// M3-T4 lands the plumbing: the persisted values are pushed on engine init (cmInit args) and the
// editor's own font-zoom (Ctrl+/−/0 / pinch) writes fontSize back. The chat-tap reveal that also
// rides this path is wired in T5.
package dev.supermux.desktop.editor

import dev.supermux.desktop.auth.DesktopTokenStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persisted editor settings. Keys mirror the mobile `cmux-editor-settings` store. [fontSize] is
 * clamped to the bundle's FONT_MIN..FONT_MAX by [clamped] before it is pushed to CodeMirror or saved.
 */
@Serializable
data class EditorPrefs(
    val lineWrap: Boolean = true,
    val fontSize: Int = EDITOR_FONT_DEFAULT,
) {
    /** A copy with [fontSize] coerced into the bundle's valid range (defensive against a hand-edited
     *  or drifted json — cm6 itself clamps too, but a clamped value keeps our persisted copy honest). */
    fun clamped(): EditorPrefs = copy(fontSize = fontSize.coerceIn(EDITOR_FONT_MIN, EDITOR_FONT_MAX))
}

class EditorPrefsStore(val path: Path = defaultPath()) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Load persisted settings; defaults on a missing file (normal first-run) and logs a corrupt one. */
    fun load(): EditorPrefs =
        runCatching { json.decodeFromString<EditorPrefs>(Files.readString(path)) }
            .onFailure {
                if (it !is java.nio.file.NoSuchFileException) {
                    println("[EditorPrefsStore] corrupt editor-settings.json ignored: $it")
                }
            }
            .getOrDefault(EditorPrefs())
            .clamped()

    /** Persist settings (font-size clamped first). Best-effort — a write failure is swallowed. */
    fun save(prefs: EditorPrefs) {
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(EditorPrefs.serializer(), prefs.clamped()))
        }
    }

    companion object {
        fun defaultPath(): Path = DesktopTokenStore.defaultPath().parent.resolve("editor-settings.json")
    }
}
