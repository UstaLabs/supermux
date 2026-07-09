// Desktop launcher prefs/draft persistence: written to `launcher-state.json` next to the token /
// ui-state / editor-settings stores (WorkspaceStateStore/EditorPrefsStore precedent). Mirrors the
// Android `session/LauncherState.kt` DTOs (dev.supermux.android.session.LauncherPrefs/LauncherDraft)
// field-for-field — keep the two in sync when either changes.
//
// One file, not two: [LauncherPrefs] (sticky agent/model/effort choices) and [LauncherDraft]
// (in-progress new-session text) are wrapped together in [LauncherStateBlob], the same shape
// WorkspaceStateStore uses for its (layout, selectedId) pair. clearDraft() resets only the draft
// half of the blob and re-persists — prefs in the same file are untouched.
//
// Unlike EditorPrefsStore/WorkspaceStateStore (plain writeString — their content isn't considered
// worth crash-safety), this store atomic-writes (temp file + ATOMIC_MOVE, falling back to a plain
// move) following DesktopTokenStore's pattern: a draft in progress is more failure-sensitive to a
// half-written file (a crash mid-save would otherwise risk truncating the user's typed message).
package dev.supermux.desktop.session

import dev.supermux.desktop.auth.DesktopTokenStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Sticky New Session launcher preferences — the agent + its last-used model, keyed per agent.
 *  Desktop copy of `dev.supermux.android.session.LauncherPrefs`. */
@Serializable
data class LauncherPrefs(
    val agent: String = "claude",
    val models: Map<String, String> = emptyMap(),
    val reasoningLevels: Map<String, String> = emptyMap(),
)

/** In-progress New Session launcher draft — cleared once a session is actually created.
 *  Desktop copy of `dev.supermux.android.session.LauncherDraft`. `workdir` is null when nothing
 *  was explicitly restored (so the screen's own most-recent-session fallback still applies). */
@Serializable
data class LauncherDraft(
    val workdir: String? = null,
    val useWorktree: Boolean = true,
    val baseBranch: String = "",
    val text: String = "",
)

@Serializable
private data class LauncherStateBlob(
    val prefs: LauncherPrefs = LauncherPrefs(),
    val draft: LauncherDraft = LauncherDraft(),
)

class LauncherStore(val path: Path = defaultPath()) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun read(): LauncherStateBlob =
        runCatching { json.decodeFromString<LauncherStateBlob>(Files.readString(path)) }
            // A missing file is the normal first-run path (NoSuchFileException lands here too);
            // anything else is a corrupt/unreadable launcher-state.json — log it, fall back to defaults.
            .onFailure {
                if (it !is java.nio.file.NoSuchFileException) {
                    println("[LauncherStore] corrupt launcher-state.json ignored: $it")
                }
            }
            .getOrDefault(LauncherStateBlob())

    private fun write(blob: LauncherStateBlob) {
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, "launcher", ".tmp")
        try {
            Files.writeString(tmp, json.encodeToString(LauncherStateBlob.serializer(), blob))
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) } // only present if the move failed
        }
    }

    fun loadPrefs(): LauncherPrefs = read().prefs
    fun savePrefs(prefs: LauncherPrefs) = write(read().copy(prefs = prefs))

    fun loadDraft(): LauncherDraft = read().draft
    fun saveDraft(draft: LauncherDraft) = write(read().copy(draft = draft))

    /** Resets the draft to its defaults while leaving [loadPrefs] untouched — called after a
     *  successful session submit. */
    fun clearDraft() = write(read().copy(draft = LauncherDraft()))

    companion object {
        fun defaultPath(): Path = DesktopTokenStore.defaultPath().parent.resolve("launcher-state.json")
    }
}
