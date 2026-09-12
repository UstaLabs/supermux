// `JsAny`/`js(...)` interop is still behind the wasm opt-in in Kotlin 2.3.
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.supermux.web.seams

import dev.supermux.ui.platform.TtsEngine
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toInt8Array
import kotlin.coroutines.resume

@Suppress("UNUSED_PARAMETER")
private fun speakJs(text: String): Unit =
    js("{ if (window.speechSynthesis) { window.speechSynthesis.cancel(); window.speechSynthesis.speak(new SpeechSynthesisUtterance(text)); } }")

private fun cancelJs(): Unit = js("{ if (window.speechSynthesis) window.speechSynthesis.cancel(); }")

/** One AudioContext for the tab. Null when the browser has none (no `AudioContext` at all). */
private fun newAudioContextJs(): JsAny? =
    js("{ var C = window.AudioContext || window.webkitAudioContext; return C ? new C() : null; }")

/**
 * Decode one COMPLETE encoded file (the broker's `/speak` stream sends a whole MP3 per NDJSON
 * line) and play it, calling [onEnd] when the node finishes — including when it finishes because
 * [stopSourceJs] stopped it. [onErr] fires when the bytes would not decode.
 *
 * `resume()` first: a context created outside a user gesture starts `suspended` under the autoplay
 * policy and would otherwise decode, "play", and produce silence.
 */
@Suppress("UNUSED_PARAMETER")
private fun playChunkJs(ctx: JsAny, data: Int8Array, onEnd: () -> Unit, onErr: () -> Unit): Unit = js(
    """{
      try { if (ctx.state === 'suspended') ctx.resume(); } catch (e) {}
      var buf = data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
      var started = false;
      try {
        ctx.decodeAudioData(buf, function (decoded) {
          try {
            var src = ctx.createBufferSource();
            src.buffer = decoded;
            src.connect(ctx.destination);
            src.onended = function () { if (ctx.__smxSource === src) ctx.__smxSource = null; onEnd(); };
            ctx.__smxSource = src;
            started = true;
            src.start();
          } catch (e2) { onErr(); }
        }, function () { if (!started) onErr(); });
      } catch (e) { onErr(); }
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun stopSourceJs(ctx: JsAny): Unit = js(
    """{
      var src = ctx.__smxSource;
      ctx.__smxSource = null;
      // `onended` is deliberately LEFT IN PLACE: stopping a node fires it, and that is the only
      // thing that resumes the coroutine suspended inside playAudioChunk.
      if (src) { try { src.stop(); } catch (e) {} }
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun closeContextJs(ctx: JsAny): Unit = js("{ try { ctx.close(); } catch (e) {} }")

/**
 * Read-aloud in the browser: `speechSynthesis` for the platform voice, an `AudioContext` for the
 * MP3 chunks the broker streams back for the non-platform (codex) voice.
 *
 * [playAudioChunk] suspends until the node's `onended`, which is what makes the caller's queue
 * gapless: `MessageTts` fetches chunk n+1 while n is still playing and hands it over the instant
 * this returns. A chunk that will not decode resolves immediately rather than throwing — a codec
 * the browser dislikes must skip a sentence, not kill the read-aloud loop.
 *
 * [stop] stops the node under way; stopping fires the same `onended` a natural finish does, so
 * the suspended caller unwinds promptly instead of waiting out a chunk nobody can hear.
 */
object WebTts : TtsEngine {
    private var ctx: JsAny? = null

    override suspend fun speak(text: String) = speakJs(text)

    override suspend fun playAudioChunk(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val c = ctx ?: newAudioContextJs()?.also { ctx = it } ?: return
        suspendCancellableCoroutine { cont ->
            var done = false
            fun finish() {
                if (done) return
                done = true
                cont.resume(Unit)
            }
            // A cancelled read-aloud coroutine must not leave this continuation pinned on a
            // callback that may never arrive (a decode that neither resolves nor rejects).
            cont.invokeOnCancellation { finish() }
            playChunkJs(c, bytes.toInt8Array(), onEnd = { finish() }, onErr = { finish() })
        }
    }

    override fun stop() {
        cancelJs()
        ctx?.let { stopSourceJs(it) }
    }

    override fun shutdown() {
        cancelJs()
        ctx?.let { stopSourceJs(it); closeContextJs(it) }
        ctx = null
    }
}
