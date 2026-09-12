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

/** Stop the recorder and return everything delivered so far as one blob — [flushRecorderJs] has
 *  already awaited the tail. Null when nothing was captured. */
@Suppress("UNUSED_PARAMETER")
private fun stopRecorderJs(rec: JsAny, mime: String): Blob? = js(
    """{
      try { if (rec.state !== 'inactive') rec.stop(); } catch (e) {}
      var chunks = rec.__smxChunks || [];
      rec.__smxChunks = [];
      if (chunks.length === 0) return null;
      return new Blob(chunks, { type: mime || (chunks[0] && chunks[0].type) || 'audio/webm' });
    }""",
)

/**
 * Ask for the fragment still inside the encoder and call [onDone] when the `dataavailable` that
 * carries it has been handled — or after [timeoutMs], so a recorder that answers nothing (or is
 * already inactive) can never wedge the dictation.
 *
 * This is the whole reason [dev.supermux.ui.platform.MicCapture.flush] exists: `requestData()`
 * dispatches its event in a LATER task, so calling it inline before a synchronous `stop()` would
 * deliver the tail to a recorder nobody reads again.
 */
@Suppress("UNUSED_PARAMETER")
private fun flushRecorderJs(rec: JsAny, timeoutMs: Int, onDone: () -> Unit): Unit = js(
    """{
      var fired = false;
      var finish = function () { if (!fired) { fired = true; onDone(); } };
      if (rec.state !== 'recording') { finish(); return; }
      var prev = rec.ondataavailable;
      // Every exit restores the original handler: a recorder left with this wrapper installed
      // would keep calling a finished flush's callback for the rest of the recording.
      var restore = function () { if (rec.ondataavailable !== prev) rec.ondataavailable = prev; };
      rec.ondataavailable = function (e) {
        if (prev) prev(e);
        restore();
        finish();
      };
      setTimeout(function () { restore(); finish(); }, timeoutMs);
      try { rec.requestData(); } catch (err) { restore(); finish(); }
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
private fun recorderIsRecordingJs(rec: JsAny): Boolean = js("rec.state === 'recording'")

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
 * **The tail.** [MicCapture.stop] is synchronous by contract, but `MediaRecorder` only ever hands
 * its bytes over through an ASYNCHRONOUS `dataavailable` event — neither `requestData()` nor
 * `stop()` produces a blob in the same tick. That is what [flush] is for: the dictation controller
 * awaits it immediately before [stop], and it asks for the still-buffered fragment and waits (up
 * to 150 ms) for the event that delivers it. Between the 250 ms timeslice and that flush, nothing
 * of the recording is dropped; without the flush the last ≤250 ms would be.
 *
 * The `MediaStream` is released as soon as a recording ends ([stop]/[cancel]) so the browser's
 * recording indicator does not stay lit for the life of the tab; the next dictation re-acquires it
 * in [requestPermission], which does not re-prompt on an origin the user already granted.
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
        // An adopted recorder must actually be recording: a stale, already-stopped one would make
        // this report success and then produce no bytes at all.
        if (recorder?.let { recorderIsRecordingJs(it) } == true) return true
        recorder?.let { abortRecorderJs(it) }
        recorder = null
        val s = stream ?: return false
        val mime = pickMimeJs() ?: return false
        val rec = startRecorderJs(s, mime, TIMESLICE_MS) ?: return false
        recorder = rec
        recordingMime = mime
        return true
    }

    /** Await the fragment still inside the encoder. See [flushRecorderJs]. */
    override suspend fun flush() {
        val rec = recorder ?: return
        suspendCancellableCoroutine { cont ->
            flushRecorderJs(rec, FLUSH_TIMEOUT_MS) { if (cont.isActive) cont.resume(Unit) }
        }
    }

    override fun stop(): CapturedAudio? {
        val rec = recorder ?: return null
        recorder = null
        try {
            val blob = stopRecorderJs(rec, recordingMime) ?: return null
            val size = blob.size.toDouble()
            if (size <= 0.0) return null
            if (size > MAX_CLIP_BYTES) {
                // Loud, because silently returning null here looks exactly like "the mic never
                // worked" — and reading it would freeze the tab on a synchronous XHR instead.
                println("[WebMic] discarding a ${size.toLong()} byte recording (cap $MAX_CLIP_BYTES)")
                return null
            }
            // Sync XHR over an object URL — the only synchronous byte read a browser main thread
            // has. A dictation clip is seconds long, so the whole blob is one read.
            val bytes = runCatching { BlobChunkSource(blob).read(0, size.toInt()) }.getOrNull() ?: return null
            if (bytes.isEmpty()) return null
            // The blob knows what it actually holds; `recordingMime` is only what we ASKED for, and
            // is empty when the browser picked its own default.
            val mime = blob.type.ifBlank { recordingMime }.ifBlank { "audio/webm" }
            return CapturedAudio(bytes = bytes, filename = "dictation.${dictationExtensionFor(mime)}", mime = mime)
        } finally {
            releaseStream()
        }
    }

    override fun cancel() {
        recorder?.let { abortRecorderJs(it) }
        recorder = null
        releaseStream()
    }

    /**
     * Drop the `MediaStream`, which is what turns the browser's recording indicator off. Safe to
     * call after every recording: [requestPermission] re-acquires one, and an origin the user has
     * already granted does not prompt again.
     */
    fun releaseStream() {
        stream?.let { stopTracksJs(it) }
        stream = null
    }

    private const val TIMESLICE_MS = 250

    /** Long enough for a `requestData()` round trip (one task), short enough that a recorder that
     *  answers nothing costs the user a blink rather than a hang. */
    private const val FLUSH_TIMEOUT_MS = 150

    /** 10 MB is minutes of opus; more than that is a stuck mic, not a message, and refusing beats
     *  freezing the tab on a synchronous read of it. */
    private const val MAX_CLIP_BYTES = 10.0 * 1024 * 1024
}
