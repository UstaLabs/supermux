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
      // Decoding is asynchronous, so a `stop()` can land between the request and the callback.
      // The generation this chunk belongs to is captured here and re-checked there: a stopped
      // read-aloud must never start a node, or the chunk the user silenced plays anyway.
      var gen = ctx.__smxGen || 0;
      // The decode has no cancel, so `stop()` resumes the caller through this instead of leaving
      // it suspended until the decode happens to come back.
      ctx.__smxPending = onErr;
      try {
        ctx.decodeAudioData(buf, function (decoded) {
          if (ctx.__smxPending === onErr) ctx.__smxPending = null;
          if ((ctx.__smxGen || 0) !== gen) { onErr(); return; }
          try {
            var src = ctx.createBufferSource();
            src.buffer = decoded;
            src.connect(ctx.destination);
            src.onended = function () { if (ctx.__smxSource === src) ctx.__smxSource = null; onEnd(); };
            ctx.__smxSource = src;
            started = true;
            src.start();
          } catch (e2) { onErr(); }
        }, function () { if (ctx.__smxPending === onErr) ctx.__smxPending = null; if (!started) onErr(); });
      } catch (e) { ctx.__smxPending = null; onErr(); }
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun stopSourceJs(ctx: JsAny): Unit = js(
    """{
      ctx.__smxGen = (ctx.__smxGen || 0) + 1;
      var pending = ctx.__smxPending;
      ctx.__smxPending = null;
      if (pending) pending();
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
 * The tab's one `<audio>` element for the progressive-MP3 path, created and PLAYED inside the tap.
 * iOS Safari lets an element play programmatically only once it has been played from a user
 * gesture; the tap is long gone by the time `/speak/start` answers, so the element is unlocked
 * here with a silent clip and reused for the real URL afterwards.
 */
private fun primeAudioJs(): Unit = js(
    """{
      var a = window.__smxReadAloud;
      if (!a) { a = new Audio(); a.preload = 'auto'; window.__smxReadAloud = a; }
      if (a.__smxPrimed) return;
      a.__smxPrimed = true;
      // 0.05 s of silence (mono 8 kHz WAV).
      a.src = 'data:audio/wav;base64,UklGRrQBAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YZABAACAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICA';
      try { var p = a.play(); if (p && p.catch) p.catch(function () { a.__smxPrimed = false; }); } catch (e) { a.__smxPrimed = false; }
    }""",
)

/**
 * Point the shared element at [url] and play it; [onEnd] fires exactly once — on `ended`, on
 * `error`, on a rejected `play()`, or when [stopAudioJs] silences it.
 */
@Suppress("UNUSED_PARAMETER")
private fun playUrlJs(url: String, onEnd: () -> Unit): Unit = js(
    """{
      var a = window.__smxReadAloud;
      if (!a) { a = new Audio(); window.__smxReadAloud = a; }
      if (a.__smxDone) { var prev = a.__smxDone; a.__smxDone = null; prev(); }
      var fired = false;
      var done = function () {
        if (fired) return; fired = true;
        a.onended = null; a.onerror = null;
        if (a.__smxDone === done) a.__smxDone = null;
        onEnd();
      };
      a.__smxDone = done;
      a.onended = done;
      a.onerror = done;
      a.src = url;
      try { var p = a.play(); if (p && p.catch) p.catch(done); } catch (e) { done(); }
    }""",
)

private fun stopAudioJs(): Unit = js(
    """{
      var a = window.__smxReadAloud;
      if (!a) return;
      try { a.pause(); } catch (e) {}
      // Dropping the src closes the HTTP stream, which lets the broker stop synthesizing.
      try { a.removeAttribute('src'); a.load(); } catch (e) {}
      if (a.__smxDone) { var d = a.__smxDone; a.__smxDone = null; d(); }
    }""",
)

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
 * the suspended caller unwinds promptly instead of waiting out a chunk nobody can hear. It also
 * bumps a generation counter and calls the pending chunk's resume hook, so a chunk still inside
 * `decodeAudioData` when [stop] lands resumes its caller AT ONCE and never starts a node.
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

    override val canPlayUrl: Boolean get() = true

    override fun prepareForTap() = primeAudioJs()

    override suspend fun playUrl(url: String) {
        suspendCancellableCoroutine { cont ->
            var done = false
            fun finish() {
                if (done) return
                done = true
                cont.resume(Unit)
            }
            cont.invokeOnCancellation { finish() }
            playUrlJs(url) { finish() }
        }
    }

    override fun stop() {
        cancelJs()
        stopAudioJs()
        ctx?.let { stopSourceJs(it) }
    }

    override fun shutdown() {
        cancelJs()
        stopAudioJs()
        ctx?.let { stopSourceJs(it); closeContextJs(it) }
        ctx = null
    }
}
