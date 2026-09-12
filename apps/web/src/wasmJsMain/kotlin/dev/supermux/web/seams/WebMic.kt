// `JsAny`/`js(...)` interop is still behind the wasm opt-in in Kotlin 2.3.
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.supermux.web.seams

import dev.supermux.net.BlobChunkSource
import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.LiveTranscript
import dev.supermux.ui.platform.MicCapture
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.files.Blob
import kotlin.coroutines.resume

/** Does this browser have a mic API at all? (`getUserMedia` needs a secure context, so an http://
 *  origin that is not localhost answers false here and the mic button never appears.) */
private fun micApiAvailableJs(): Boolean =
    js("!!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && typeof MediaRecorder !== 'undefined')")

/**
 * The first container this browser can actually encode, from the Vue app's preference list
 * (`src/web-app/src/composables/useMediaRecorder.ts`): opus-in-webm on Chrome/Firefox, mp4/AAC on
 * Safari. `""` means "none of them, let the browser choose its own default" and null means this
 * browser cannot record at all.
 */
private fun pickMimeJs(): String? = js(
    """{
      if (typeof MediaRecorder === 'undefined') return null;
      var prefs = ['audio/webm;codecs=opus','audio/webm','audio/mp4;codecs=mp4a.40.2','audio/mp4','audio/ogg;codecs=opus'];
      for (var i = 0; i < prefs.length; i++) { if (MediaRecorder.isTypeSupported(prefs[i])) return prefs[i]; }
      return '';
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun getUserMediaJs(onOk: (JsAny) -> Unit, onErr: () -> Unit): Unit = js(
    """{
      navigator.mediaDevices.getUserMedia({ audio: true })
        .then(function (s) { onOk(s); })
        .catch(function () { onErr(); });
    }""",
)

/** A recorder that pushes every `dataavailable` blob onto itself; [timesliceMs] makes the browser
 *  hand one over that often, which is what lets [stopRecorderJs] be synchronous. */
@Suppress("UNUSED_PARAMETER")
private fun startRecorderJs(stream: JsAny, mime: String, timesliceMs: Int): JsAny? = js(
    """{
      try {
        var rec = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream);
        rec.__smxChunks = [];
        rec.ondataavailable = function (e) { if (e.data && e.data.size > 0) rec.__smxChunks.push(e.data); };
        rec.start(timesliceMs);
        return rec;
      } catch (err) { return null; }
    }""",
)

/** `requestData()` then `stop()`, returning everything gathered SO FAR as one blob. Both of those
 *  deliver their last `dataavailable` asynchronously, so the final partial timeslice is not in it —
 *  see [WebMic.stop]. Null when nothing was captured. */
@Suppress("UNUSED_PARAMETER")
private fun stopRecorderJs(rec: JsAny, mime: String): Blob? = js(
    """{
      try { if (rec.state === 'recording') rec.requestData(); } catch (e) {}
      try { if (rec.state !== 'inactive') rec.stop(); } catch (e) {}
      var chunks = rec.__smxChunks || [];
      rec.__smxChunks = [];
      if (chunks.length === 0) return null;
      return new Blob(chunks, { type: mime || (chunks[0] && chunks[0].type) || 'audio/webm' });
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun abortRecorderJs(rec: JsAny): Unit = js(
    """{
      rec.ondataavailable = null;
      rec.__smxChunks = [];
      try { if (rec.state !== 'inactive') rec.stop(); } catch (e) {}
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun stopTracksJs(stream: JsAny): Unit =
    js("{ try { stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {} }")

/** `audio/webm;codecs=opus` → `webm`. The broker's ffmpeg pass reads the container, but the
 *  filename is what it logs and what whisper-family APIs sniff, so keep them consistent. */
internal fun dictationExtensionFor(mime: String): String {
    val sub = mime.substringBefore(';').substringAfter('/').trim().lowercase()
    return when (sub) {
        "webm" -> "webm"
        "mp4", "m4a", "aac", "x-m4a" -> "mp4"
        "ogg", "opus" -> "ogg"
        "wav", "wave", "x-wav" -> "wav"
        "mpeg", "mp3" -> "mp3"
        else -> "webm"
    }
}

/**
 * Browser dictation: `getUserMedia` + `MediaRecorder`, behind the shared [MicCapture] seam.
 *
 * **The ≤250 ms tail.** [MicCapture.stop] is synchronous by contract, but `MediaRecorder` only ever
 * hands its bytes over through an ASYNCHRONOUS `dataavailable` event — neither `requestData()` nor
 * `stop()` produces a blob in the same tick. So the recorder runs with a 250 ms timeslice and
 * [stop] returns everything delivered up to the last tick; the final, still-buffered fragment
 * (≤250 ms, and it ends after the user already released the button) is dropped. Making [stop]
 * suspend would fix that; it would also change the seam for all four hosts to buy a quarter of a
 * second of silence, which is not a trade worth making.
 *
 * One [stream] is kept alive between recordings (the permission prompt only appears once); it is
 * released on [cancel] only when nothing is recording, and never while a capture is in flight.
 */
object WebMic : MicCapture {
    private var stream: JsAny? = null
    private var recorder: JsAny? = null
    private var recordingMime: String = ""

    override val available: Boolean get() = micApiAvailableJs()

    /** No on-device ASR in a browser — the recording is POSTed to the broker's `/transcribe`. */
    override val liveTranscript: LiveTranscript? = null

    override suspend fun requestPermission(): Boolean {
        if (!available) return false
        stream?.let { return true }
        val granted: JsAny? = suspendCancellableCoroutine { cont ->
            getUserMediaJs(onOk = { s -> cont.resume(s) }, onErr = { cont.resume(null) })
        }
        stream = granted
        return granted != null
    }

    override fun start(): Boolean {
        if (recorder != null) return true
        val s = stream ?: return false
        val mime = pickMimeJs() ?: return false
        val rec = startRecorderJs(s, mime, TIMESLICE_MS) ?: return false
        recorder = rec
        recordingMime = mime
        return true
    }

    override fun stop(): CapturedAudio? {
        val rec = recorder ?: return null
        recorder = null
        val blob = stopRecorderJs(rec, recordingMime) ?: return null
        val size = blob.size.toDouble()
        if (size <= 0.0 || size > MAX_CLIP_BYTES) return null
        // Sync XHR over an object URL — the only synchronous byte read a browser main thread has.
        // A dictation clip is seconds long, so the whole blob is one read.
        val bytes = runCatching { BlobChunkSource(blob).read(0, size.toInt()) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val mime = recordingMime.ifBlank { "audio/webm" }
        return CapturedAudio(bytes = bytes, filename = "dictation.${dictationExtensionFor(mime)}", mime = mime)
    }

    override fun cancel() {
        recorder?.let { abortRecorderJs(it) }
        recorder = null
    }

    /** Release the mic itself (the browser's recording indicator). Not part of the seam — called
     *  from nowhere yet; kept so a future "leaving the app" hook has one line to call. */
    fun releaseStream() {
        if (recorder != null) return
        stream?.let { stopTracksJs(it) }
        stream = null
    }

    private const val TIMESLICE_MS = 250

    /** 50 MB of dictation is a stuck mic, not a message; refusing beats freezing the tab on a sync
     *  read of it. */
    private const val MAX_CLIP_BYTES = 50.0 * 1024 * 1024
}
