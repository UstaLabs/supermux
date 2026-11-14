package dev.supermux.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized

/** The system file manager [Platform.openFolder] opens a folder in (#149), named the way its OS names it. */
enum class FileManager { Finder, Explorer, Files }

/**
 * Whether a session's host is this computer (#149): the desktop's own broker. Its folders are folders on this machine,
 * so they can be opened here. False wherever the shell doesn't say (phone, web, tests).
 */
val LocalSessionOnThisComputer = staticCompositionLocalOf<(sessionId: String) -> Boolean> { { false } }

/**
 * Opens [path], a folder of session [sessionId], in the system file manager — or null when this device has none, or
 * the session's host isn't this computer (a remote host's path means nothing here).
 */
@Composable
fun rememberOpenFolder(sessionId: String?, path: String?): (() -> Unit)? {
    val platform = LocalPlatform.current
    val onThisComputer = LocalSessionOnThisComputer.current
    if (platform.fileManager == null || sessionId == null || path.isNullOrBlank() || !onThisComputer(sessionId)) return null
    return { platform.openFolder(path) }
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
