// `JsAny`/`js(...)` interop is still behind the wasm opt-in in Kotlin 2.3.
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.supermux.web.seams

import dev.supermux.net.BlobChunkSource
import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.LiveTranscript
import dev.supermux.ui.platform.MicCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.files.Blob
import kotlin.coroutines.resume

/** Does this browser have a mic API at all? (`getUserMedia` needs a secure context, so an http://
 *  origin that is not localhost answers false here and the mic button never appears.) */
private fun micApiAvailableJs(): Boolean =
    js("!!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && (window.AudioContext || window.webkitAudioContext))")

@Suppress("UNUSED_PARAMETER")
private fun getUserMediaJs(onOk: (JsAny) -> Unit, onErr: () -> Unit): Unit = js(
    """{
      navigator.mediaDevices.getUserMedia({ audio: true })
        .then(function (s) { onOk(s); })
        .catch(function () { onErr(); });
    }""",
)

/**
 * Raw PCM capture: the mic stream through a `ScriptProcessorNode`, downsampled as it arrives to
 * [rate] Hz mono 16-bit and kept as `Int16Array` chunks on the returned handle. Null when the
 * browser has no Web Audio.
 *
 * Why not `MediaRecorder`: it only produces compressed containers (webm/opus, mp4/AAC), and the
 * broker reads WAV only — every client sends 16 kHz PCM WAV so the host needs no ffmpeg.
 * `ScriptProcessorNode` rather than an `AudioWorklet` because it starts synchronously (a worklet
 * module loads asynchronously, and [WebMic.start] must answer at once) and runs on every browser
 * we ship to. The context runs at the device's own rate (Firefox refuses to connect a mic to a
 * context at another rate), so the 16 kHz conversion happens here: each output sample is the
 * average of its input window, which also keeps speech from aliasing.
 */
@Suppress("UNUSED_PARAMETER")
private fun startCaptureJs(stream: JsAny, rate: Int, bufferSize: Int): JsAny? = js(
    """{
      try {
        var Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return null;
        var ctx = new Ctx();
        var src = ctx.createMediaStreamSource(stream);
        var proc = ctx.createScriptProcessor(bufferSize, 1, 1);
        // A processor only runs while connected to the output; a muted gain keeps it silent.
        var mute = ctx.createGain();
        mute.gain.value = 0;
        var ratio = ctx.sampleRate > rate ? ctx.sampleRate / rate : 1;
        var cap = { ctx: ctx, src: src, proc: proc, mute: mute, chunks: [], rate: ratio > 1 ? rate : ctx.sampleRate, acc: 0, n: 0, pos: 0 };
        proc.onaudioprocess = function (e) {
          var input = e.inputBuffer.getChannelData(0);
          var out = new Int16Array(Math.ceil(input.length / ratio) + 1);
          var k = 0;
          for (var i = 0; i < input.length; i++) {
            cap.acc += input[i]; cap.n++; cap.pos += 1;
            if (cap.pos >= ratio) {
              out[k++] = Math.max(-32768, Math.min(32767, Math.round(cap.acc / cap.n * 32768)));
              cap.pos -= ratio; cap.acc = 0; cap.n = 0;
            }
          }
          if (k > 0) cap.chunks.push(out.subarray(0, k));
        };
        src.connect(proc);
        proc.connect(mute);
        mute.connect(ctx.destination);
        if (ctx.state === 'suspended' && ctx.resume) ctx.resume();
        return cap;
      } catch (err) { return null; }
    }""",
)

/** Everything captured so far as a 16-bit mono WAV blob, without stopping. Null when empty. */
@Suppress("UNUSED_PARAMETER")
private fun captureWavJs(cap: JsAny): Blob? = js(
    """{
      var total = 0;
      for (var i = 0; i < cap.chunks.length; i++) total += cap.chunks[i].length;
      if (total === 0) return null;
      var buf = new ArrayBuffer(44 + total * 2);
      var v = new DataView(buf);
      var str = function (at, s) { for (var j = 0; j < s.length; j++) v.setUint8(at + j, s.charCodeAt(j)); };
      str(0, 'RIFF'); v.setUint32(4, 36 + total * 2, true); str(8, 'WAVE');
      str(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
      v.setUint32(24, cap.rate, true); v.setUint32(28, cap.rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
      str(36, 'data'); v.setUint32(40, total * 2, true);
      var pcm = new Int16Array(buf, 44);
      var at = 0;
      for (var i = 0; i < cap.chunks.length; i++) { pcm.set(cap.chunks[i], at); at += cap.chunks[i].length; }
      return new Blob([buf], { type: 'audio/wav' });
    }""",
)

/**
 * The samples captured after the first [byteOffset] bytes, as a binary string (one char per byte,
 * 16-bit little-endian), WITHOUT stopping. Empty when nothing new has arrived. A string because it
 * crosses the wasm boundary in one call; the Kotlin side takes each char's low byte.
 */
@Suppress("UNUSED_PARAMETER")
private fun capturePcmSinceJs(cap: JsAny, byteOffset: Double): String = js(
    """{
      var start = Math.floor(byteOffset / 2);
      var total = 0;
      for (var i = 0; i < cap.chunks.length; i++) total += cap.chunks[i].length;
      if (start >= total) return "";
      var out = new Int16Array(total - start);
      var at = 0, pos = 0;
      for (var i = 0; i < cap.chunks.length; i++) {
        var c = cap.chunks[i];
        if (pos + c.length > start) {
          var from = Math.max(0, start - pos);
          out.set(from > 0 ? c.subarray(from) : c, at);
          at += c.length - from;
        }
        pos += c.length;
      }
      var bytes = new Uint8Array(out.buffer);
      var s = "";
      for (var j = 0; j < bytes.length; j += 8192) s += String.fromCharCode.apply(null, bytes.subarray(j, j + 8192));
      return s;
    }""",
)

/** Disconnect the capture graph and close its context. Idempotent. */
@Suppress("UNUSED_PARAMETER")
private fun stopCaptureJs(cap: JsAny): Unit = js(
    """{
      try { cap.proc.onaudioprocess = null; } catch (e) {}
      try { cap.src.disconnect(); } catch (e) {}
      try { cap.proc.disconnect(); } catch (e) {}
      try { cap.mute.disconnect(); } catch (e) {}
      try { if (cap.ctx.state !== 'closed') cap.ctx.close(); } catch (e) {}
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun captureIsRunningJs(cap: JsAny): Boolean = js("cap.ctx.state !== 'closed' && cap.proc.onaudioprocess !== null")

/** One processor buffer's worth of wall time, so [WebMic.flush] waits for the samples still in it. */
@Suppress("UNUSED_PARAMETER")
private fun captureBufferMsJs(cap: JsAny, bufferSize: Int): Int = js("Math.ceil(bufferSize / cap.ctx.sampleRate * 1000)")

@Suppress("UNUSED_PARAMETER")
private fun setTimeoutJs(ms: Int, onDone: () -> Unit): Unit = js("{ setTimeout(function () { onDone(); }, ms); }")

/**
 * An `AnalyserNode` on the mic stream that reports its RMS loudness (0..1) to [onLevel] every
 * [intervalMs]. Returns a handle for [stopMeterJs], or null when the browser has no Web Audio.
 */
@Suppress("UNUSED_PARAMETER")
private fun startMeterJs(stream: JsAny, intervalMs: Int, onLevel: (Double) -> Unit): JsAny? = js(
    """{
      try {
        var Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return null;
        var ctx = new Ctx();
        var src = ctx.createMediaStreamSource(stream);
        var an = ctx.createAnalyser();
        an.fftSize = 1024;
        src.connect(an);
        var buf = new Float32Array(an.fftSize);
        var timer = setInterval(function () {
          an.getFloatTimeDomainData(buf);
          var sum = 0;
          for (var i = 0; i < buf.length; i++) sum += buf[i] * buf[i];
          onLevel(Math.sqrt(sum / buf.length));
        }, intervalMs);
        return { ctx: ctx, src: src, timer: timer };
      } catch (e) { return null; }
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun stopMeterJs(meter: JsAny): Unit = js(
    """{
      try { clearInterval(meter.timer); } catch (e) {}
      try { meter.src.disconnect(); } catch (e) {}
      try { meter.ctx.close(); } catch (e) {}
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun stopTracksJs(stream: JsAny): Unit =
    js("{ try { stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {} }")

/** `audio/webm;codecs=opus` → `webm`. [WebMic] only ever records `audio/wav`; the mapping stays
 *  general because the filename is what the broker logs and what whisper-family APIs sniff. */
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
 * Browser dictation: `getUserMedia` + Web Audio PCM capture, behind the shared [MicCapture] seam.
 * The recording is 16 kHz mono 16-bit WAV — the same format Android, iOS and desktop send — built
 * from the captured samples at [snapshot]/[stop]. See [startCaptureJs] for why not `MediaRecorder`.
 *
 * **The tail.** Samples reach the capture one processor buffer at a time (~85 ms at 48 kHz), so
 * the dictation controller's [flush] before [stop] waits one buffer for the last of them.
 *
 * The `MediaStream` is released as soon as a recording ends ([stop]/[cancel]) so the browser's
 * recording indicator does not stay lit for the life of the tab; the next dictation re-acquires it
 * in [requestPermission], which does not re-prompt on an origin the user already granted.
 */
object WebMic : MicCapture {
    private var stream: JsAny? = null
    private var capture: JsAny? = null
    private var meter: JsAny? = null
    private val _level = MutableStateFlow(0f)

    /** RMS loudness from an `AnalyserNode` on the same stream the capture reads. */
    override val level: StateFlow<Float> get() = _level

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
        // An adopted capture must actually be running: a stale, already-stopped one would make
        // this report success and then produce no bytes at all.
        if (capture?.let { captureIsRunningJs(it) } == true) return true
        capture?.let { stopCaptureJs(it) }
        capture = null
        val s = stream ?: return false
        capture = startCaptureJs(s, SAMPLE_RATE, BUFFER_SIZE) ?: return false
        _level.value = 0f
        meter = startMeterJs(s, METER_INTERVAL_MS) { v -> _level.value = v.toFloat() }
        return true
    }

    /** The recording so far, without stopping it. Same size cap and byte read as [stop]. */
    override suspend fun snapshot(): CapturedAudio? {
        capture ?: return null
        flush()
        val cap = capture ?: return null
        return captureWavJs(cap)?.let(::readClip)
    }

    /** The raw PCM after [byteOffset] bytes, for a live dictation stream; null when not capturing. */
    override fun pcmSince(byteOffset: Long): ByteArray? {
        val cap = capture ?: return null
        val s = capturePcmSinceJs(cap, byteOffset.toDouble())
        return ByteArray(s.length) { i -> (s[i].code and 0xFF).toByte() }
    }

    private fun readClip(blob: Blob): CapturedAudio? {
        val size = blob.size.toDouble()
        if (size <= 0.0) return null
        if (size > MAX_CLIP_BYTES) {
            // Loud, because silently returning null here looks exactly like "the mic never
            // worked" — and reading it would freeze the tab on a synchronous XHR instead.
            println("[WebMic] discarding a ${size.toLong()} byte recording (cap $MAX_CLIP_BYTES)")
            return null
        }
        // Sync XHR over an object URL — the only synchronous byte read a browser main thread
        // has. A dictation clip is minutes at most, so the whole blob is one read.
        val bytes = runCatching { BlobChunkSource(blob).read(0, size.toInt()) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return CapturedAudio(bytes = bytes, filename = "dictation.wav", mime = MIME)
    }

    /** Wait one processor buffer, so the samples still inside it reach the capture. */
    override suspend fun flush() {
        val cap = capture ?: return
        val ms = captureBufferMsJs(cap, BUFFER_SIZE)
        suspendCancellableCoroutine { cont -> setTimeoutJs(ms) { if (cont.isActive) cont.resume(Unit) } }
    }

    override fun stop(): CapturedAudio? {
        val cap = capture ?: return null
        capture = null
        try {
            stopCaptureJs(cap)
            return captureWavJs(cap)?.let(::readClip)
        } finally {
            releaseStream()
        }
    }

    override fun cancel() {
        capture?.let { stopCaptureJs(it) }
        capture = null
        releaseStream()
    }

    /**
     * Drop the `MediaStream`, which is what turns the browser's recording indicator off. Safe to
     * call after every recording: [requestPermission] re-acquires one, and an origin the user has
     * already granted does not prompt again.
     */
    fun releaseStream() {
        meter?.let { stopMeterJs(it) }
        meter = null
        _level.value = 0f
        stream?.let { stopTracksJs(it) }
        stream = null
    }

    /** What every client sends and the broker's STT engines read. */
    private const val SAMPLE_RATE = 16_000
    private const val MIME = "audio/wav"

    /** ~85 ms at 48 kHz: small enough that [flush] is a blink, large enough not to glitch. */
    private const val BUFFER_SIZE = 4096

    /** How often the meter reports — the dictation controller samples its level every 50 ms. */
    private const val METER_INTERVAL_MS = 50

    /** 32 MB is ~17 minutes of 16 kHz WAV; more than that is a stuck mic, not a message, and
     *  refusing beats freezing the tab on a synchronous read of it. */
    private const val MAX_CLIP_BYTES = 32.0 * 1024 * 1024
}
