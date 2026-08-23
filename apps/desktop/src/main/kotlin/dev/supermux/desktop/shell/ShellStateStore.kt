// Desktop UI-state persistence: the sidebar chrome ([SidebarSnapshot]) + the selected session,
// written to `ui-state.json` next to the token store. There is no `rememberSaveable` process-death
// persistence on desktop (that's an Android/config-change concept), so AppShell hydrates from
// this store at startup and debounce-persists to it as the sidebar changes.
//
// Unlike DesktopTokenStore, this is NOT a secret — a plain (non-atomic) writeString is fine here.
package dev.supermux.desktop.shell

import dev.supermux.desktop.auth.DesktopTokenStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Sidebar chrome, persisted. This used to be the whole `ShellSnapshot` — the old shell's fixed
 * chat|work|display fractions and per-session pane flags — but that layout model went with
 * SessionDetail: what is on screen inside the detail pane is now the workspace's own tree, stored
 * on the broker. Only the sidebar is still the client's to remember.
 *
 * Both fields default, and the store decodes with `ignoreUnknownKeys`, so a ui-state.json written
 * by the old shell still restores its sidebar and simply drops the pane state it also carried.
 */
@Serializable
data class SidebarSnapshot(
    val sidebarCollapsed: Boolean = false,
    val sidebarWidthDp: Float = 320f,
    /** Project-group keys (workdir / group key) the user has collapsed in the sidebar. */
    val collapsedProjectPaths: List<String> = emptyList(),
)

/** The persisted UI state: the sidebar snapshot + the last-selected session id. */
@Serializable
data class PersistedUiState(
    // Field name kept as `layout` so an existing ui-state.json still hydrates.
    val layout: SidebarSnapshot? = null,
    val selectedId: String? = null,
    /**
     * AppearanceMode name (`DARK` / `LIGHT` / `SYSTEM`). Local-only — not a broker setting.
     * Null on files written before this field existed; hydrate as DARK.
     */
    val appearance: String? = null,
)

class ShellStateStore(val path: Path = defaultPath()) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun load(): PersistedUiState =
        runCatching { json.decodeFromString<PersistedUiState>(Files.readString(path)) }
            // A missing file is the normal first-run path (NoSuchFileException lands here too);
            // anything else is a corrupt/unreadable ui-state.json — log it, fall back to empty.
            .onFailure {
                if (it !is java.nio.file.NoSuchFileException) {
                    println("[ShellStateStore] corrupt ui-state.json ignored: $it")
                }
            }
            .getOrDefault(PersistedUiState())

    fun save(state: PersistedUiState) {
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, json.encodeToString(PersistedUiState.serializer(), state))
        }
    }

    companion object {
        fun defaultPath(): Path = DesktopTokenStore.defaultPath().parent.resolve("ui-state.json")
    }
}
