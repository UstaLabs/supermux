package dev.supermux.desktop.platform

import dev.supermux.desktop.host.SystemOsEnv
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The desktop's open and save dialogs for files a user hands in or takes out (#157): a page's file input, a download's
 * Save. On Windows they are Windows' own (IFileOpenDialog / IFileSaveDialog, [WinFileDialogs]): a filter by type, the
 * whole name with its extension. Elsewhere, and where Windows' fail, AWT's FileDialog, which is the native one on macOS
 * and Linux. Each starts in the folder the user last chose one in, else their Downloads.
 *
 * Windows' dialogs are waited for off the UI thread, so the app's window keeps drawing; AWT's run modal on Swing's.
 */
internal object DesktopFileDialogs {
    /** The folder the user last opened from or saved to, this run. */
    @Volatile private var lastFolder: String? = null

    /** Where [name] goes: a file the user chose, or null when they cancelled. */
    suspend fun save(name: String): File? {
        val title = localized(Str.files_save_title)
        val folder = startFolder()
        if (DesktopOs.current == DesktopOs.WINDOWS) {
            val dot = name.lastIndexOf('.')
            val extension = if (dot > 0 && dot < name.length - 1) name.substring(dot + 1) else null
            val types = listOfNotNull(
                extension?.let { WinFileDialogs.FileType(localized(Str.files_type_named, it.uppercase()), "*.$it") },
                WinFileDialogs.FileType(localized(Str.files_type_all), "*.*"),
            )
            when (val outcome = withContext(Dispatchers.IO) { WinFileDialogs.save(title, folder, name, types, extension) }) {
                is WinFileDialogs.Outcome.Picked -> return File(outcome.paths.single()).also(::remember)
                WinFileDialogs.Outcome.Cancelled -> return null
                is WinFileDialogs.Outcome.Failed -> println("[files] Windows' save dialog failed (${outcome.why}): AWT's instead")
            }
        }
        return withContext(Dispatchers.Swing) {
            val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
            dialog.directory = folder
            dialog.file = name
            dialog.isVisible = true
            val dir = dialog.directory ?: return@withContext null
            val chosen = dialog.file ?: return@withContext null
            File(dir, chosen).also(::remember)
        }
    }

    /**
     * Files to hand a web page: one, or several when [multiple]. [accept] is the input's list (MIME types and extensions);
     * [accepts] says whether a name matches it, for AWT's dialog where it filters by name.
     */
    suspend fun open(title: String, accept: List<String>, multiple: Boolean, accepts: (String) -> Boolean): List<String>? {
        val folder = startFolder()
        if (DesktopOs.current == DesktopOs.WINDOWS) {
            val all = WinFileDialogs.FileType(localized(Str.files_type_all), "*.*")
            val types = windowsPattern(accept)?.let { listOf(WinFileDialogs.FileType(localized(Str.page_pick_accepted), it), all) }
                ?: listOf(all)
            when (val outcome = withContext(Dispatchers.IO) { WinFileDialogs.open(title, folder, types, multiple) }) {
                is WinFileDialogs.Outcome.Picked -> return outcome.paths.also { remember(File(it.first())) }
                WinFileDialogs.Outcome.Cancelled -> return null
                is WinFileDialogs.Outcome.Failed -> println("[files] Windows' open dialog failed (${outcome.why}): AWT's instead")
            }
        }
        return withContext(Dispatchers.Swing) {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isMultipleMode = multiple
            dialog.directory = folder
            if (accept.any { it.isNotBlank() }) dialog.setFilenameFilter { _, name -> accepts(name) }
            dialog.isVisible = true
            val files = dialog.files?.takeIf { it.isNotEmpty() } ?: return@withContext null
            remember(files.first())
            files.map { it.absolutePath }
        }
    }

    private fun startFolder(): String = lastFolder?.takeIf { File(it).isDirectory } ?: downloadsFolder()

    private fun remember(file: File) {
        file.absoluteFile.parentFile?.takeIf { it.isDirectory }?.let { lastFolder = it.path }
    }

    /** The user's Downloads, else their home. */
    private fun downloadsFolder(): String {
        val home = SystemOsEnv.home.toFile()
        return File(home, "Downloads").takeIf { it.isDirectory }?.path ?: home.path
    }
}
