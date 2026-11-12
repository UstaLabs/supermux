package dev.supermux.ui.chat

import io.github.kdroidfilter.composemediaplayer.VideoPlayerState

/**
 * The playing clip's real width/height where the player library reports it wrong, else null (the library's own
 * fitting is right there).
 *
 * compose-media-player 0.11.x on iOS declares `aspectRatio` as a value read ONCE at construction, while it is still its
 * 16:9 default, so its surface boxes every clip in a 16:9 band: a portrait clip came out a small box in the middle of
 * the viewer (#95 follow-up, Ahmet's iPhone). The live ratio it does keep is read here instead.
 */
internal expect fun liveVideoAspect(player: VideoPlayerState): Float?
