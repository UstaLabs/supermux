// Cluster G4: Android's scrcpy half — the H.264 decode + SurfaceView + touch/keyboard binding that
// sits behind `Platform.videoDecoder()` (see [AndroidVideoSurfaceFactory]). The shared
// `ui/display/DisplayPanel.kt` never names MediaCodec; it only asks the platform for this surface
// when the stream's transport is h264 and `Caps.scrcpy` is on. The chrome (status chip, control
// bar, hidden keyboard field) is the SHARED one — only the decode loop is Android's.
package dev.supermux.android.display

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import dev.supermux.display.VncInput
import dev.supermux.net.ScrcpyClient
import dev.supermux.ui.display.DisplayControlBar
import dev.supermux.ui.display.DisplayStatusChip
import dev.supermux.ui.display.HiddenKeyboardField
import dev.supermux.ui.display.toDisplayState
import dev.supermux.ui.theme.Space
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Feeds Annex-B H.264 access units to a MediaCodec decoding straight to a Surface.
 * Configures lazily once SPS (NAL type 7) + PPS (type 8) + surface + dims are known.
 * All entry points are synchronized; feed() is called from the WS collect coroutine.
 */
class H264SurfaceDecoder {
    private var codec: MediaCodec? = null
    @Volatile private var configured = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var sawKey = false
    private var ptsUs = 0L
    private val bufInfo = MediaCodec.BufferInfo()

    @Synchronized fun setDims(w: Int, h: Int) { width = w; height = h; tryConfigure() }

    @Synchronized fun setSurface(s: Surface?) {
        if (s == null) { releaseLocked(); surface = null; return }
        surface = s; tryConfigure()
    }

    @Synchronized fun feed(isKey: Boolean, data: ByteArray) {
        if (!configured) {
            scanCsd(data)
            tryConfigure()
            if (!configured) return
        }
        if (!sawKey) {
            if (!isKey) return
            sawKey = true
        }
        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val ib = c.getInputBuffer(inIdx) ?: return
                ib.clear()
                ib.put(data)
                c.queueInputBuffer(inIdx, 0, data.size, ptsUs, 0)
                ptsUs += 33_333
            }
            var outIdx = c.dequeueOutputBuffer(bufInfo, 0)
            while (outIdx >= 0) {
                c.releaseOutputBuffer(outIdx, true)
                outIdx = c.dequeueOutputBuffer(bufInfo, 0)
            }
        } catch (_: Exception) {}
    }

    @Synchronized fun release() { releaseLocked(); surface = null; sps = null; pps = null }

    private fun releaseLocked() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null; configured = false; sawKey = false
    }

    private fun tryConfigure() {
        if (configured) return
        val s = surface ?: return
        val sp = sps ?: return
        val pp = pps ?: return
        if (width == 0 || height == 0) return
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sp))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pp))
            val c = MediaCodec.createDecoderByType("video/avc")
            c.configure(fmt, s, null, 0)
            c.start()
            codec = c
            configured = true
            sawKey = false
        } catch (_: Exception) { codec = null; configured = false }
    }

    /** Scan an Annex-B buffer for SPS (type 7) / PPS (type 8); store each with a 4-byte start code. */
    private fun scanCsd(data: ByteArray) {
        val n = data.size
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < n) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) { starts.add(i); i += 3; continue }
                if (data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) { starts.add(i); i += 4; continue }
            }
            i++
        }
        for (k in starts.indices) {
            val sc = starts[k]
            val hdr = if (sc + 2 < n && data[sc + 2].toInt() == 1) sc + 3 else sc + 4
            if (hdr >= n) continue
            val type = data[hdr].toInt() and 0x1F
            val end = if (k + 1 < starts.size) starts[k + 1] else n
            if (type == 7 && sps == null) sps = withStartCode(data, hdr, end)
            if (type == 8 && pps == null) pps = withStartCode(data, hdr, end)
        }
    }

    private fun withStartCode(data: ByteArray, nalStart: Int, end: Int): ByteArray {
        val body = data.copyOfRange(nalStart, end)
        val out = ByteArray(4 + body.size)
        out[3] = 1
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }
}

/**
 * Live H.264 decode + touch/keyboard surface for a single display [streamId].
 *
 * Runs the [ScrcpyClient] connect loop, pipes frames into a [H264SurfaceDecoder]
 * rendering to a SurfaceView, maps touches from the letterboxed (aspect-fit) view rect
 * back into the remote screen's pixels, and forwards keyboard text/keys as scrcpy JSON.
 */
@Composable
internal fun ScrcpyView(
    streamId: String,
    connect: (String) -> ScrcpyClient,
    modifier: Modifier = Modifier,
) {
    val client = remember(streamId) { connect(streamId) }
    val decoder = remember(streamId) { H264SurfaceDecoder() }

    val status by client.status.collectAsState()
    val dims by client.dims.collectAsState()

    LaunchedEffect(client) { client.run() }
    LaunchedEffect(dims) { dims?.let { decoder.setDims(it.first, it.second) } }
    LaunchedEffect(client) { client.frames.collect { decoder.feed(it.isKey, it.data) } }
    // Warm-display: with keepAlivePanel the panel stays composed across tab toggles,
    // so onDispose fires only on real teardown (leaving the session) — the right point.
    DisposableEffect(client) { onDispose { client.stop(); decoder.release() } }

    val scope = rememberCoroutineScope()
    // The touch listener is installed once but must always read the latest stream
    // dims; rememberUpdatedState keeps a stable ref whose value tracks recomposition.
    val dimsRef by rememberUpdatedState(dims)

    val focusRequester = remember { FocusRequester() }
    var keyboardActive by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("scrcpy_surface"),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            decoder.setSurface(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            decoder.setSurface(null)
                        }
                    })
                    setOnTouchListener { v, e ->
                        val action = when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> 0
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1
                            MotionEvent.ACTION_MOVE -> 2
                            else -> return@setOnTouchListener true
                        }
                        val d = dimsRef
                        if (d != null) {
                            val (w, h) = d
                            val (sx, sy) = VncInput.mapToRemote(e.x, e.y, v.width, v.height, w, h)
                            scope.launch {
                                client.sendInput(
                                    "{\"type\":\"touch\",\"action\":$action,\"x\":$sx,\"y\":$sy,\"width\":$w,\"height\":$h}",
                                )
                            }
                        }
                        true
                    }
                }
            },
        )

        DisplayStatusChip(
            state = status.toDisplayState(),
            modifier = Modifier.align(Alignment.TopEnd).padding(Space.sm),
        )
        DisplayControlBar(
            keyboardActive = keyboardActive,
            onToggleKeyboard = { keyboardActive = !keyboardActive },
            modifier = Modifier.align(Alignment.BottomStart).padding(Space.md),
        )
        HiddenKeyboardField(
            focusRequester = focusRequester,
            enabled = keyboardActive,
            onChar = { ch ->
                scope.launch { client.sendInput("{\"type\":\"text\",\"text\":${jsonStr(ch.toString())}}") }
            },
            onSpecial = { sp ->
                val name = VncInput.scrcpyKeyName(sp)
                scope.launch {
                    client.sendInput("{\"type\":\"key\",\"key\":${jsonStr(name)},\"action\":0}")
                    client.sendInput("{\"type\":\"key\",\"key\":${jsonStr(name)},\"action\":1}")
                }
            },
        )
    }
}

/** Minimal JSON string escaping for scrcpy text/key payloads (quotes/backslash/control). */
private fun jsonStr(s: String): String {
    val sb = StringBuilder("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    return sb.append("\"").toString()
}
