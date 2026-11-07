package dev.supermux.desktop.platform

import java.nio.file.Files
import java.nio.file.Path

/**
 * The command [name] on [path] (a PATH value), found in-process: no `which` or `where` process. Windows has no which.exe
 * (read-aloud never found ffplay or mpv there), some Linux base images lack it (systemctl then read as absent), and
 * Windows' `where` searches the current folder first. Only absolute PATH entries are searched, so the current folder
 * never plants a command. On Windows ([windows]) PATH is `;`-separated and, unless [name] already ends in one of them,
 * each [pathExt] extension is tried in turn (PATHEXT, `.COM;.EXE;.BAT;.CMD` when unset), folder by folder, as Windows
 * looks a command up. Elsewhere PATH is `:`-separated and the file must be executable.
 */
internal fun findOnPath(name: String, path: String?, windows: Boolean, pathExt: String?): Path? {
    if (name.isEmpty() || path.isNullOrEmpty()) return null
    val names = if (windows) {
        val extensions = (pathExt?.takeIf { it.isNotBlank() } ?: DEFAULT_PATHEXT).split(';').map { it.trim() }.filter { it.startsWith('.') }
        if (extensions.any { name.endsWith(it, ignoreCase = true) }) listOf(name) else extensions.map { name + it.lowercase() }
    } else {
        listOf(name)
    }
    for (entry in path.split(if (windows) ';' else ':')) {
        val dir = entry.trim().removeSurrounding("\"")
        if (dir.isEmpty()) continue
        val base = runCatching { Path.of(dir) }.getOrNull()?.takeIf { it.isAbsolute } ?: continue
        for (candidate in names) {
            val file = runCatching { base.resolve(candidate) }.getOrNull() ?: continue
            if (Files.isRegularFile(file) && (windows || Files.isExecutable(file))) return file
        }
    }
    return null
}

private const val DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD"

/** [findOnPath] with this process's PATH, PATHEXT and OS. */
internal fun findCommand(name: String): Path? =
    findOnPath(name, System.getenv("PATH"), isWindowsOs(), System.getenv("PATHEXT"))
