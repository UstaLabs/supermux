// LEGACY desktop launcher state — `launcher-state.json`, READ-ONLY since cluster F1.
//
// The launcher's prefs/draft now live in the shared settings store under
// `SettingsKeys.LAUNCHER_PREFS` / `LAUNCHER_DRAFT` and are reached through
// `dev.supermux.ui.prefs.UiPrefs`, the same single owner Android reads — so the shared launcher
// does not need to know which platform persisted it.
//
// This class survives only as the MIGRATION SOURCE: `AppShell` reads it once per launch and
// `UiPrefs.seedLauncher` drains whatever it holds into the shared store for keys still unset
// (one-way, idempotent, non-destructive). Nothing writes this file any more, so its atomic-write
// path and `clearDraft` are gone with the writers; the file is dead weight after the first launch.
package dev.supermux.desktop.session

import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.state.LauncherDraft
import dev.supermux.state.LauncherPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

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

    fun loadPrefs(): LauncherPrefs = read().prefs
    fun loadDraft(): LauncherDraft = read().draft

    companion object {
        fun defaultPath(): Path = DesktopTokenStore.defaultPath().parent.resolve("launcher-state.json")
    }
}
