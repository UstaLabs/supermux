// Desktop UI-state persistence: the workspace layout ([WorkspaceSnapshot]) + the selected session,
// written to `ui-state.json` next to the token store. There is no `rememberSaveable` process-death
// persistence on desktop (that's an Android/config-change concept), so WorkspaceRoot hydrates from
// this store at startup and debounce-persists to it as the layout changes.
//
// Unlike DesktopTokenStore, this is NOT a secret — a plain (non-atomic) writeString is fine here.
package dev.supermux.desktop.workspace

import dev.supermux.desktop.auth.DesktopTokenStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** The persisted UI state: the workspace [layout] snapshot + the last-selected session id. */
@Serializable
data class PersistedUiState(
    val layout: WorkspaceSnapshot? = null,
    val selectedId: String? = null,
)

class WorkspaceStateStore(val path: Path = defaultPath()) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun load(): PersistedUiState =
        runCatching { json.decodeFromString<PersistedUiState>(Files.readString(path)) }
            // A missing file is the normal first-run path (NoSuchFileException lands here too);
            // anything else is a corrupt/unreadable ui-state.json — log it, fall back to empty.
            .onFailure {
                if (it !is java.nio.file.NoSuchFileException) {
                    println("[WorkspaceStateStore] corrupt ui-state.json ignored: $it")
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
