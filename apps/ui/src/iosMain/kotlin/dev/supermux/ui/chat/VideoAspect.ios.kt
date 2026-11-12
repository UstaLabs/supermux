package dev.supermux.ui.chat

import io.github.kdroidfilter.composemediaplayer.DefaultVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState

/** The library's observable `videoAspectRatio` (set from the track's natural size), not its frozen `aspectRatio`. */
internal actual fun liveVideoAspect(player: VideoPlayerState): Float? =
    (player as? DefaultVideoPlayerState)?.videoAspectRatio?.toFloat()?.takeIf { it > 0f && it.isFinite() }
