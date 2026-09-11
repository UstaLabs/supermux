package dev.supermux.web.seams

import dev.supermux.chat.mimeForFileName
import dev.supermux.net.BlobChunkSource
import dev.supermux.ui.platform.FileAccess
import dev.supermux.ui.platform.PickKind
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.platform.SavedFile
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toInt8Array
import org.w3c.dom.AddEventListenerOptions
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.get
import kotlin.coroutines.resume

/**
 * `Blob([typedArray], {type})` straight from JS: building the `JsArray<JsAny?>` the typed
 * constructor wants costs more code than the one-line literal and buys nothing here.
 */
@Suppress("UNUSED_PARAMETER")
private fun makeBlob(data: Int8Array, mime: String): Blob = js("new Blob([data], { type: mime })")

private fun blobOf(bytes: ByteArray, mime: String): Blob = makeBlob(bytes.toInt8Array(), mime)

/**
 * "Save as…" is a download; "open with" is a new tab on a blob URL. Nothing touches a real path.
 *
 * BOTH are gesture-bound: a browser only honours a synthetic download click or a `window.open`
 * when it runs in the SAME TICK as the user gesture that caused it. These are `suspend` functions,
 * so a caller that awaits anything (a fetch, a decode) before calling them resumes on a later tick
 * and the popup blocker eats the result silently — no exception, no download. Callers must do
 * their awaiting BEFORE the gesture hands over. The same applies to `WebPlatform.openUrl`.
 */
object WebFiles : FileAccess {
    override suspend fun saveAs(name: String, mime: String, bytes: ByteArray): SavedFile? {
        val url = URL.createObjectURL(blobOf(bytes, mime))
        val a = document.createElement("a") as HTMLAnchorElement
        a.href = url
        a.download = name
        a.click()
        window.setTimeout({ URL.revokeObjectURL(url); null }, 10_000)
        return SavedFile(name = name, location = "Downloads")
    }

    /** A download is the browser's, not ours: there is no path to hand back to the OS. */
    override suspend fun openSaved(saved: SavedFile): Boolean = false

    override suspend fun openExternally(name: String, mime: String, bytes: ByteArray): Boolean {
        val url = URL.createObjectURL(blobOf(bytes, mime))
        window.open(url, "_blank", "noopener")
        window.setTimeout({ URL.revokeObjectURL(url); null }, 60_000)
        return true
    }

    override fun probeMime(name: String): String = mimeForFileName(name) ?: "application/octet-stream"

    /** No temp directory in a browser tab; the inline video player falls back to its own path. */
    override suspend fun stageTemp(name: String, bytes: ByteArray): String? = null
}

/** A hidden `<input type=file>` per pick; resolves with the chosen files or empty on cancel. */
suspend fun pickFilesViaInput(kind: PickKind): List<PickedFile> = suspendCancellableCoroutine { cont ->
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"
    input.multiple = true
    input.accept = when (kind) {
        PickKind.Any -> ""
        PickKind.Images -> "image/*"
        PickKind.Media -> "image/*,video/*"
    }
    input.style.display = "none"
    document.body?.appendChild(input)

    var done = false
    var dropFocusWatch: () -> Unit = {}
    fun finish(files: List<PickedFile>) {
        if (done) return
        done = true
        dropFocusWatch()
        input.parentNode?.removeChild(input)
        cont.resume(files)
    }

    input.addEventListener("change", {
        val list = input.files
        finish(
            (0 until (list?.length ?: 0)).mapNotNull { list?.get(it) }.map { f ->
                PickedFile(
                    name = f.name,
                    mime = f.type.ifBlank { mimeForFileName(f.name) ?: "application/octet-stream" },
                    source = BlobChunkSource(f),
                )
            },
        )
    })

    input.click()

    // There is no cancel event, so a cancel is inferred from "the window got focus back and the
    // input is still empty". Three things keep that heuristic from eating a REAL pick, because
    // [finish] is one-shot and a wrong empty answer would drop the user's files silently:
    //  - the watch is armed on the next tick, AFTER `click()`, so the focus the click itself
    //    steals does not arm-and-fire it immediately;
    //  - it waits a full second, which covers a slow picker dismissal (iOS/Safari, a transcode);
    //  - it only concludes "cancelled" when `input.files` is still empty. The file list is
    //    populated BEFORE `change` dispatches, so a pick that is merely late to fire its event is
    //    still visible here and the timer stands down.
    window.setTimeout({
        val onFocus: (Event) -> Unit = {
            window.setTimeout({ if ((input.files?.length ?: 0) == 0) finish(emptyList()); null }, 1000)
            Unit
        }
        dropFocusWatch = { window.removeEventListener("focus", onFocus) }
        window.addEventListener("focus", onFocus, AddEventListenerOptions(once = true))
        null
    }, 0)

    // `done` first: the input is about to go away, and a `change` that lands between the removal
    // and the flag would otherwise resume a cancelled continuation.
    cont.invokeOnCancellation {
        done = true
        input.parentNode?.removeChild(input)
    }
}
