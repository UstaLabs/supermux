// A file path tapped in chat is checked against the host before it opens, so a path the agent
// merely mentioned (or that was since deleted) says "File not found" instead of opening an empty
// editor.
package dev.supermux.ui.files

import dev.supermux.fs.FileSystemService
import dev.supermux.fs.FsStat
import dev.supermux.net.FsException

/**
 * True only when [stat] definitely says there is no such entry (HTTP 404). Any other failure — an
 * unreachable host, a permission error — is not proof the file is missing, so the caller opens as
 * before and lets the editor report what it finds.
 */
fun statSaysMissing(stat: Result<FsStat>): Boolean = (stat.exceptionOrNull() as? FsException)?.status == 404

/** Whether [absPath] is known to be missing on [fileSystem]'s host; false when there is no service. */
suspend fun tappedFileMissing(fileSystem: FileSystemService?, absPath: String): Boolean =
    fileSystem != null && statSaysMissing(fileSystem.stat(absPath))

fun fileNotFoundNotice(path: String): String = "File not found: $path"
