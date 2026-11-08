package dev.supermux.ui.work

import dev.supermux.ui.platform.FileAction
import dev.supermux.ui.platform.Platform
import dev.supermux.work.Download
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadState
import dev.supermux.work.DownloadedFile
import dev.supermux.work.isAvailable

/**
 * Hand a finished download over the way this host does it (R3), after checking the file is still there: the
 * media store lives in OS-reclaimable cache folders and Storage › Clear empties it, so a missing file turns the
 * item into a retryable failure rather than a dead button.
 *
 * Nothing suspends before the platform's own call when the file is a browser Blob (the web), so a bar action
 * started inside the user's gesture is still inside it when the browser looks.
 */
internal suspend fun handOver(platform: Platform, downloads: DownloadCenter, item: Download, action: FileAction): Boolean {
    val file = stillThere(platform, downloads, item) ?: return false
    return when (action) {
        FileAction.Open -> open(platform, item, file)
        FileAction.Save -> platform.files.saveFile(file) != null
        FileAction.Share -> platform.files.shareFile(file)
    }
}

/**
 * R1: what a tap did before #95, with the downloaded FILE — pointer: Save, then open what was saved; touch: Open,
 * else Share (iOS). The same rule `saveOrOpenAttachment` applies to bytes.
 */
internal suspend fun handOverAsTapped(platform: Platform, pointer: Boolean, downloads: DownloadCenter, item: Download): Boolean {
    val file = stillThere(platform, downloads, item) ?: return false
    val actions = platform.files.fileActions
    return when {
        pointer && FileAction.Save in actions -> {
            // A dismissed dialog is no error, so nothing is said — as with the bytes.
            val saved = platform.files.saveFile(file) ?: return false
            platform.files.openSaved(saved)
            true
        }
        FileAction.Open in actions -> open(platform, item, file)
        FileAction.Share in actions -> platform.files.shareFile(file)
        else -> false
    }
}

/** [item]'s file while it is done and still there. A vanished one fails, so its Retry fetches it again. */
private suspend fun stillThere(platform: Platform, downloads: DownloadCenter, item: Download): DownloadedFile? {
    val file = (item.state as? DownloadState.Done)?.file ?: return null
    if (file.isAvailable()) return file
    downloads.markMissing(item.key)
    platform.notices.show("${item.name} was removed — retry to download it again")
    return null
}

private suspend fun open(platform: Platform, item: Download, file: DownloadedFile): Boolean {
    val opened = platform.files.openFile(file)
    if (!opened) platform.notices.show("Couldn't open ${item.name}")
    return opened
}
