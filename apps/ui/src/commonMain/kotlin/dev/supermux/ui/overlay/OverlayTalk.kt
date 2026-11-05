// Sending a finished talk from the assistant overlay (#62): what was said, with the screen if it went along.
package dev.supermux.ui.overlay

import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.net.ChunkSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The chat's upload: a file's bytes, name, type and kind; its file id, or null when it didn't go up. */
typealias TalkUpload = suspend (ChunkSource, String, String, String?, (Long, Long) -> Unit) -> String?

/**
 * Sends a finished talk: the screen (if it went along) goes up first, then the words with it; if the screen
 * won't go up, the words still go. It runs in [scope], which must outlive whatever saw the talk finish:
 * the overlay's effect restarts the moment it marks the talk done, and an upload still in flight in it was
 * cancelled with it, so a talk with the screen was never sent. [log] says what happened at each step.
 */
fun finishTalk(
    scope: CoroutineScope,
    text: String,
    screenName: String,
    screen: Deferred<ByteArray?>?,
    upload: TalkUpload?,
    send: (String, List<String>) -> Unit,
    log: (String) -> Unit = {},
): Job = scope.launch {
    val png = screen?.await()
    val fileId = if (png != null && upload != null) {
        log("uploading the screen (${png.size / 1024} KB)")
        try {
            upload(ByteArrayChunkSource(png), screenName, "image/png", null) { _, _ -> }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("the screen didn't go up (${e.message}); sending the words alone")
            null
        }
    } else {
        if (screen != null) log("the screen wasn't ready; sending the words alone")
        null
    }
    send(text, listOfNotNull(fileId))
    log("sent ${text.length} chars" + if (fileId != null) " with the screen ($fileId)" else " without the screen")
}
