// Cluster G1: the hardware-video seam. `:ui` names no MediaCodec and no SurfaceView — the shared
// display panel (cluster G4) asks Platform.videoDecoder() for a surface and falls back to VNC when
// the host has none.
package dev.supermux.ui.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import dev.supermux.net.ScrcpyClient

/**
 * Decodes a scrcpy display's Annex-B H.264 stream straight to a native surface.
 *
 * Android returns one (`MediaCodec` → `SurfaceView`); desktop returns **null** from
 * [dev.supermux.ui.platform.Platform.videoDecoder] because it has no hardware decoder bound — its
 * displays are VNC, and a shared panel therefore renders the VNC framebuffer whenever this seam is
 * null OR [dev.supermux.ui.platform.Caps.scrcpy] is false, exactly as the transport switch already
 * did on Android.
 */
@Stable
interface VideoSurfaceFactory {
    /**
     * Mount the decoding surface for one running display [streamId], connecting through
     * [connect]. The surface owns the whole loop: run the client, feed key/delta frames to the
     * decoder, map touches back into remote pixels, forward keyboard text/keys, and release the
     * codec on dispose.
     */
    @Composable
    fun VideoSurface(
        streamId: String,
        connect: (String) -> ScrcpyClient,
        modifier: Modifier,
    )
}
