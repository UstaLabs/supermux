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

private fun nowMsJs(): Double = js("Date.now()")

/**
 * A document-level `paste` listener, installed once, that hands over every image file on the
 * event's own `clipboardData` — the ONE path that works in Safari.
 *
 * Safari grants clipboard access only for the task that is handling the user's paste. The composer
 * reaches [ClipboardAccess.readImages] through `scope.launch { withContext(Default) { … } }`, an
 * event-loop turn or two later, by which time `navigator.clipboard.read()` is refused. The paste
 * EVENT carries the data with it, so stashing it here and letting `readImages` collect the stash
 * makes the paste work without asking for permission at all.
 *
 * Capture phase, and nothing is prevented: this only observes.
 */
@Suppress("UNUSED_PARAMETER")
private fun installPasteListenerJs(onFile: (Blob, String) -> Unit, onBatch: () -> Unit): Unit = js(
    """{
      if (window.__smxPasteHook) return;
      window.__smxPasteHook = true;
      document.addEventListener('paste', function (e) {
        var dt = e.clipboardData;
        if (!dt) return;
        var files = dt.files || [];
        var any = false;
        for (var i = 0; i < files.length; i++) {
          var f = files[i];
          if (f && f.type && f.type.indexOf('image/') === 0) { onFile(f, f.type); any = true; }
        }
        if (any) onBatch();
      }, true);
    }""",
)

/**
 * `navigator.clipboard.read()` — STARTED SYNCHRONOUSLY, then awaited. ONE image per clipboard item
 * (its first `image/…` flavour; the same picture offered as png and jpeg is one paste, not two).
 * [onDone] fires once, afterwards, whatever happened — a denied permission, a text-only clip and
 * an empty clipboard are all "no images", not errors (the seam's contract is an empty list).
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
            break;
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
 * Pasted images, from the `paste` event first and the async Clipboard API second.
 *
 * [hasImage] is a CAPABILITY probe, not a content probe, and that is correct here: it is only ever
 * consulted after a Ctrl/Cmd+V already matched or inside the open attach menu, and the API offers
 * no way to look at the clipboard without asking for it (which would prompt). A text-only paste
 * therefore reaches [readImages] and comes back empty — the composer's own "nothing to attach"
 * path — instead of prompting for permission on every keystroke.
 *
 * **Ordering assumption.** The `paste` listener is on the document in the CAPTURE phase, so it has
 * already run — in the same interaction task as the keystroke — by the time the composer's
 * `scope.launch { withContext(Default) { readImages() } }` hop resumes. The stash is therefore
 * always filled before it is read, and [STASH_TTL_MS] is what keeps a stale one from being
 * attached to a LATER "Paste image" menu click rather than what makes the handoff work.
 *
 * [readImages] prefers the stash a real `paste` event just filled (fresh = within [STASH_TTL_MS],
 * which covers the dispatch hop the composer makes through `Dispatchers.Default`), because that is
 * the only path Safari allows. The `navigator.clipboard.read()` fallback covers the attach menu's
 * "Paste" entry, where there is no paste event at all — **on Safari that fallback is refused**
 * (its permission is granted per user gesture, and the read happens a task too late), so Safari
 * users paste with the keyboard.
 *
 * The bytes are never copied into the wasm heap here: each image becomes a [BlobChunkSource] over
 * the clipboard's own `Blob`, which the resumable uploader reads a chunk at a time.
 */
object WebClipboard : ClipboardAccess {
    /** The most recent `paste` event's images, and when it happened. */
    private var stash: List<PickedFile> = emptyList()
    private var stashedAtMs: Double = 0.0
    private var pending = mutableListOf<PickedFile>()
    private var hookInstalled = false

    /** Install the `paste` hook. Called from `main()` — it has to be listening BEFORE the user
     *  pastes, and `readImages` would be far too late. Idempotent on both sides. */
    fun install() {
        if (hookInstalled) return
        hookInstalled = true
        installPasteListenerJs(
            onFile = { blob, type -> pending.add(pickedFile(pending.size + 1, blob, type)) },
            onBatch = {
                stash = pending.toList()
                stashedAtMs = nowMsJs()
                pending = mutableListOf()
            },
        )
    }

    /** The CAPABILITY probe only — never "a paste event stashed something". A browser without
     *  `clipboard.read` (Firefox) can serve no images at all, and answering true there would make
     *  the composer consume Ctrl/Cmd+V and break plain-text paste. */
    override fun hasImage(): Boolean = clipboardReadAvailableJs()

    override suspend fun readImages(): List<PickedFile> {
        takeFreshStash()?.let { return it }
        if (!clipboardReadAvailableJs()) return emptyList()
        val picked = mutableListOf<PickedFile>()
        return suspendCancellableCoroutine { cont ->
            readClipboardImagesJs(
                onItem = { blob, type -> picked.add(pickedFile(picked.size + 1, blob, type)) },
                onDone = { if (cont.isActive) cont.resume(picked.toList()) },
            )
        }
    }

    /** The stash, consumed, when a `paste` event filled it moments ago; null otherwise. */
    private fun takeFreshStash(): List<PickedFile>? {
        if (stash.isEmpty()) return null
        val fresh = nowMsJs() - stashedAtMs < STASH_TTL_MS
        val out = stash
        stash = emptyList()
        return if (fresh) out else null
    }

    private fun pickedFile(index: Int, blob: Blob, type: String): PickedFile {
        val mime = type.ifBlank { "image/png" }
        val ext = pastedExtensionFor(mime)
        val name = if (index <= 1) "pasted.$ext" else "pasted-$index.$ext"
        return PickedFile(name = name, mime = mime, source = BlobChunkSource(blob))
    }

    /** Long enough for the composer's `launch { withContext(Default) { … } }` hop, short enough
     *  that a stale paste can never be attached to a LATER "Paste image" menu click. */
    private const val STASH_TTL_MS = 2_000.0
}
