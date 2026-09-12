// `JsAny`/`js(...)` interop is still behind the wasm opt-in in Kotlin 2.3.
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.supermux.web.seams

import dev.supermux.net.BlobChunkSource
import dev.supermux.ui.platform.ClipboardAccess
import dev.supermux.ui.platform.PickedFile
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.files.Blob
import kotlin.coroutines.resume

/** The async Clipboard API's read half — Chrome/Edge/Safari have it, Firefox only behind a flag. */
internal fun clipboardReadAvailableJs(): Boolean =
    js("!!(navigator.clipboard && navigator.clipboard.read)")

/**
 * `navigator.clipboard.read()` — STARTED SYNCHRONOUSLY (the promise must be created inside the
 * user's paste gesture or Safari rejects it with a NotAllowedError), then awaited. Each `image/…`
 * flavour of each item is reported through [onItem]; [onDone] fires once, afterwards, whatever
 * happened — a denied permission, a text-only clip and an empty clipboard are all "no images", not
 * errors (the seam's contract is an empty list).
 */
@Suppress("UNUSED_PARAMETER")
private fun readClipboardImagesJs(onItem: (Blob, String) -> Unit, onDone: () -> Unit): Unit = js(
    """{
      var p;
      try { p = navigator.clipboard.read(); } catch (e) { onDone(); return; }
      p.then(function (items) {
        var jobs = [];
        for (var i = 0; i < items.length; i++) {
          var item = items[i];
          var types = item.types || [];
          for (var j = 0; j < types.length; j++) {
            var type = types[j];
            if (type.indexOf('image/') !== 0) continue;
            jobs.push(item.getType(type).then(
              (function (t) { return function (b) { if (b) onItem(b, b.type || t); }; })(type),
              function () {}
            ));
          }
        }
        Promise.all(jobs).then(function () { onDone(); }, function () { onDone(); });
      }, function () { onDone(); });
    }""",
)

/** `image/png` → `png`. Whatever the clipboard reports is the truth about the bytes; the extension
 *  only has to agree with it so the broker's thumbnailer picks the right decoder. */
internal fun pastedExtensionFor(mime: String): String {
    val sub = mime.substringBefore(';').substringAfter('/').trim().lowercase()
    return when (sub) {
        "jpeg", "jpg" -> "jpg"
        "svg+xml" -> "svg"
        "" -> "png"
        else -> sub.substringAfterLast('+').filter { it.isLetterOrDigit() }.ifBlank { "png" }
    }
}

/**
 * Pasted images, through the async Clipboard API.
 *
 * [hasImage] is a CAPABILITY probe, not a content probe, and that is correct here: it is only ever
 * consulted after a Ctrl/Cmd+V already matched or inside the open attach menu, and the API offers
 * no way to look at the clipboard without asking for it (which would prompt). A text-only paste
 * therefore reaches [readImages] and comes back empty — the composer's own "nothing to attach"
 * path — instead of prompting for permission on every keystroke.
 *
 * The bytes are never copied into the wasm heap here: each flavour becomes a [BlobChunkSource]
 * over the clipboard's own `Blob`, which the resumable uploader reads a chunk at a time.
 */
object WebClipboard : ClipboardAccess {
    override fun hasImage(): Boolean = clipboardReadAvailableJs()

    override suspend fun readImages(): List<PickedFile> {
        if (!clipboardReadAvailableJs()) return emptyList()
        val picked = mutableListOf<PickedFile>()
        var n = 0
        return suspendCancellableCoroutine { cont ->
            readClipboardImagesJs(
                onItem = { blob, type ->
                    val mime = type.ifBlank { "image/png" }
                    n += 1
                    val name = if (n == 1) "pasted.${pastedExtensionFor(mime)}"
                    else "pasted-$n.${pastedExtensionFor(mime)}"
                    runCatching { BlobChunkSource(blob) }.getOrNull()
                        ?.let { picked.add(PickedFile(name = name, mime = mime, source = it)) }
                },
                onDone = { if (cont.isActive) cont.resume(picked.toList()) },
            )
        }
    }
}
