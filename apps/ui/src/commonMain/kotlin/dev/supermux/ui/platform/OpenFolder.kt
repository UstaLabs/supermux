package dev.supermux.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized

/** The system file manager [Platform.openFolder] opens a folder in (#149), named the way its OS names it. */
enum class FileManager { Finder, Explorer, Files }

/**
 * Opens a session's folders in [manager] (#149) — for a session whose host is this computer ([onThisComputer]: the
 * desktop's own broker), whose paths are paths on this machine. The shell provides it where the device has a file
 * manager; elsewhere (phone, web, a screen under test) there is none, and nothing offers to open a folder.
 */
class FolderOpener(
    val manager: FileManager,
    private val onThisComputer: (sessionId: String) -> Boolean,
    private val open: (path: String) -> Boolean,
) {
    fun forSession(sessionId: String?, path: String?): (() -> Unit)? {
        if (sessionId == null || path.isNullOrBlank() || !onThisComputer(sessionId)) return null
        return { open(path) }
    }
}

val LocalFolderOpener = staticCompositionLocalOf<FolderOpener?> { null }

/** "Show in Finder" (its label) and the open itself, for [path] of session [sessionId]; null when it can't be opened here. */
class OpenFolder(val label: String, val open: () -> Unit)

@Composable
fun rememberOpenFolder(sessionId: String?, path: String?): OpenFolder? {
    val opener = LocalFolderOpener.current ?: return null
    val open = opener.forSession(sessionId, path) ?: return null
    return OpenFolder(openFolderLabel(opener.manager), open)
}

/** "Show in Finder" / "Show in Explorer" / "Open folder": the entry that opens a folder in [manager]. */
@Composable
fun openFolderLabel(manager: FileManager): String = localized(
    when (manager) {
        FileManager.Finder -> Str.open_folder_finder
        FileManager.Explorer -> Str.open_folder_explorer
        FileManager.Files -> Str.open_folder_files
    },
)
