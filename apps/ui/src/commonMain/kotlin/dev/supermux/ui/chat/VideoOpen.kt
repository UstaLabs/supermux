package dev.supermux.ui.chat

import kotlin.coroutines.CoroutineContext

/**
 * Where a clip's player is opened ([MountedVideoPlayer]). The backends disagree: Android's
 * ExoPlayer must be called on the thread that made it, the main one, or it throws "accessed on
 * the wrong thread", and the clip fails before it starts; the macOS desktop backend must NOT be
 * opened on the main thread, because it reports a failure with `runBlocking { withContext(Main) }`
 * and parks the UI thread waiting for itself.
 */
internal expect val VideoOpenContext: CoroutineContext
