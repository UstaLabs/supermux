package dev.supermux.desktop.host

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * The supervisor's own log (`<stateDir>/desktop-host.log`): an app launched from Finder has no
 * stdout. Rotates at [maxBytes], keeping one previous file (`.1`). Never throws.
 */
class HostLogFile(private val file: Path, private val maxBytes: Long = 1_048_576) {
    @Synchronized
    fun append(message: String) {
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            if (Files.exists(file) && Files.size(file) >= maxBytes) {
                Files.move(file, file.resolveSibling(file.fileName.toString() + ".1"), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.writeString(
                file, "${Instant.now()} $message\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        }
    }
}
