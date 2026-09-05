// Camera-capture FileProvider URIs. Split out of the deleted `chat/ChatPanel.kt` (cluster D4 moved
// the panel itself to `:ui`); these two are Android-only plumbing for the capture seam.
package dev.supermux.android.chat

import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Create a FileProvider URI for a fresh camera capture in cacheDir/attachments (the path already
 * declared in file_paths.xml + reused by openAttachment). The system camera app writes the JPEG
 * here, then the picker host reads it back and uploads it.
 */
internal fun createImageUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Create a FileProvider URI for a fresh camera video capture in cacheDir/attachments (the same
 * path createImageUri + openAttachment already use, so no file_paths.xml change is needed). The
 * system camera app writes the MP4 here; the picker host then reads it back —
 * contentResolver.getType() maps the .mp4 extension to video/mp4 — and uploads it with kind=null
 * so the broker infers "video".
 */
internal fun createVideoUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}.mp4")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
