package dev.supermux.android.display

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.supermux.android.theme.LocalPanes
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.android.theme.Radii
import dev.supermux.android.theme.Space
import dev.supermux.net.ScrcpyStatus
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
 * Native Display panel for a session's mirrored device/desktop screen.
 *
 * Resolves the session's active display via [listDisplays] (preferring an h264
 * transport), then mirrors it live: an H.264 websocket stream is decoded by
 * MediaCodec straight to a SurfaceView and touch events are mapped back into the
 * remote screen's coordinate space. VNC streams aren't decoded natively here.
 *
 * [connect] builds a fresh [dev.supermux.net.ScrcpyClient] bound to a stream id.
 */
@Composable
fun DisplayPanel(
    sessionName: String,
    listDisplays: suspend () -> List<dev.supermux.net.DisplayStream>,
    connect: (String) -> dev.supermux.net.ScrcpyClient,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme

    var loading by remember { mutableStateOf(true) }
    var stream by remember { mutableStateOf<dev.supermux.net.DisplayStream?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        val list = listDisplays()
        stream = list.firstOrNull { it.sessionName == sessionName && it.status == "running" && it.transport == "h264" }
            ?: list.firstOrNull { it.sessionName == sessionName && it.status == "running" }
        loading = false
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center,
    ) {
        val s = stream
        when {
            loading -> CircularProgressIndicator(color = cs.primary)

            s != null && s.transport == "h264" -> ScrcpyView(s.id, connect)

            s != null && s.transport == "vnc" -> Text(
                "VNC display — open it in the web app",
                color = cs.onSurfaceVariant,
                fontFamily = MonoFontFamily,
                fontSize = 13.sp,
            )

            else -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Text(
                    "No active display for this session",
                    color = cs.onSurfaceVariant,
                    fontFamily = MonoFontFamily,
                    fontSize = 13.sp,
                )
                TextButton(onClick = { refreshKey++ }) {
                    Text(
                        "Refresh",
                        color = cs.primary,
                        fontFamily = MonoFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * Live H.264 decode + touch surface for a single display [streamId].
 *
 * Runs the [dev.supermux.net.ScrcpyClient] connect loop, pipes frames into a
 * [H264SurfaceDecoder] rendering to a SurfaceView, and maps touches from the
 * letterboxed (aspect-fit) view rect back into the remote screen's pixels.
 */
@Composable
private fun ScrcpyView(streamId: String, connect: (String) -> dev.supermux.net.ScrcpyClient) {
    val client = remember(streamId) { connect(streamId) }
    val decoder = remember(streamId) { H264SurfaceDecoder() }

    val status by client.status.collectAsState()
    val dims by client.dims.collectAsState()

    LaunchedEffect(client) { client.run() }
    LaunchedEffect(dims) { dims?.let { decoder.setDims(it.first, it.second) } }
    LaunchedEffect(client) { client.frames.collect { decoder.feed(it.isKey, it.data) } }
    DisposableEffect(client) { onDispose { client.stop(); decoder.release() } }

    val scope = rememberCoroutineScope()
    // The touch listener is installed once but must always read the latest stream
    // dims; rememberUpdatedState keeps a stable ref whose value tracks recomposition.
    val dimsRef by rememberUpdatedState(dims)

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
                            // Video is aspect-fit (letterboxed) within the view.
                            val scale = minOf(v.width.toFloat() / w, v.height.toFloat() / h)
                            val dispW = w * scale
                            val dispH = h * scale
                            val offX = (v.width - dispW) / 2f
                            val offY = (v.height - dispH) / 2f
                            val sx = ((e.x - offX) / scale).toInt().coerceIn(0, w)
                            val sy = ((e.y - offY) / scale).toInt().coerceIn(0, h)
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

        StatusChip(
            status = status,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Space.sm),
        )
    }
}

/** Subtle top-right indicator tinted by the stream connection [status]. */
@Composable
private fun StatusChip(status: ScrcpyStatus, modifier: Modifier = Modifier) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val (label, tint) = when (status) {
        ScrcpyStatus.CONNECTING -> "Connecting…" to cs.primary
        ScrcpyStatus.CONNECTED -> "Connected" to Color(c.warning)
        ScrcpyStatus.DISCONNECTED -> "Disconnected" to cs.onSurfaceVariant
    }
    Row(
        modifier
            .background(cs.surfaceContainer.copy(alpha = 0.85f), RoundedCornerShape(Radii.pill))
            .padding(horizontal = Space.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(tint, RoundedCornerShape(Radii.pill)),
        )
        Text(
            label,
            color = tint,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
