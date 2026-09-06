// Cluster G1: Android's actual behind `Platform.videoDecoder()`. The decode + surface + input
// binding is [ScrcpyView] (MediaCodec → SurfaceView) — this is only the seam that lets a SHARED
// display panel mount it without naming `android.media`.
package dev.supermux.android.display

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.supermux.net.ScrcpyClient
import dev.supermux.ui.display.VideoSurfaceFactory

/** MediaCodec H.264 as a [VideoSurfaceFactory]; desktop has no counterpart and reads null. */
object AndroidVideoSurfaceFactory : VideoSurfaceFactory {
    @Composable
    override fun VideoSurface(
        streamId: String,
        connect: (String) -> ScrcpyClient,
        modifier: Modifier,
    ) = ScrcpyView(streamId = streamId, connect = connect, modifier = modifier)
}
