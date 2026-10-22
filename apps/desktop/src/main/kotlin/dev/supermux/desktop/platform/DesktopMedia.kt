package dev.supermux.desktop.platform

import dev.supermux.media.FileMediaStore
import dev.supermux.media.MediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** The desktop app's one attachment cache — process-wide, so detached windows share it. */
object DesktopMedia {
    val cache: MediaCache by lazy {
        val os = System.getProperty("os.name").orEmpty()
        val base = mediaCacheBase(os, System::getenv, System.getProperty("user.home").orEmpty())
        val root = if (os.lowercase().contains("win")) {
            Path.of(base, "supermux-desktop", "cache", "media")
        } else {
            Path.of(base, "supermux-desktop", "media")
        }
        runCatching { ownerOnlyDir(root) }.onFailure { println("[media] cannot prepare $root: $it") }
        MediaCache(
            FileMediaStore(root.toString(), fileUri = { Path.of(it).toUri().toString() }, io = Dispatchers.IO),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}

/**
 * The OS's per-user cache folder: `$XDG_CACHE_HOME` (else `~/.cache`) on Linux, `~/Library/Caches` on
 * macOS, `%LOCALAPPDATA%` on Windows. A cache, not app data — the OS and cleaners may reclaim it.
 */
internal fun mediaCacheBase(osName: String, env: (String) -> String?, home: String): String {
    val os = osName.lowercase()
    return when {
        os.contains("win") -> env("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Local"
        os.contains("mac") || os.contains("darwin") -> "$home/Library/Caches"
        else -> env("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.cache"
    }
}

/** Create [dir] and make it `0700` where POSIX permissions exist: other users must not read chat files. */
private fun ownerOnlyDir(dir: Path) {
    Files.createDirectories(dir)
    if (dir.fileSystem.supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
    }
}
