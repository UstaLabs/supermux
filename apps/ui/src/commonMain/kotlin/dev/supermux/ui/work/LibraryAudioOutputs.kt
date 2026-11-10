// The app's audio through the video player library the app already ships (#118).
package dev.supermux.ui.work

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.supermux.ui.chat.VideoOpenContext
import dev.supermux.work.AudioOutput
import dev.supermux.work.AudioOutputs
import dev.supermux.work.AudioPlayer
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The fleet's audio outputs on Compose Media Player — ExoPlayer on Android, AVPlayer on iOS and macOS, Media
 * Foundation / GStreamer on the desktop, an HTML5 element on the web — the library the chat's clips already play
 * through. ONE player state for the app, made outside any composition on first use and kept here, held by the
 * fleet's [AudioPlayer] (which Android's view model keeps through a rotation): no screen, navigation or recreation
 * drops it. Each item gets a thin [AudioOutput] over it; a released one says nothing more.
 *
 * Every call to the library runs in order on [VideoOpenContext] — the main thread on Android (ExoPlayer is bound to
 * the thread that made it), off the AWT thread on the desktop (macOS's error path blocks on it, and Linux's
 * position read is a blocking native call).
 *
 * An item the library cannot play says so ([AudioOutput.Events.onError]) however the library fails: an error it
 * sets (macOS's backend on an audio-only file, after ~7 s of loading: "Failed to retrieve video size"), an open that
 * throws, or a load that never ends ([loadTimeoutMs]).
 *
 * @param scope the fleet's: the commands and the position reads run there, and end with it.
 * @param make the library's player state, made on first use (a test's fake).
 */
internal class LibraryAudioOutputs(
    private val scope: CoroutineScope,
    private val make: () -> VideoPlayerState = { createVideoPlayerState() },
    private val loadTimeoutMs: Long = LOAD_TIMEOUT_MS,
) : AudioOutputs {
    private val held = MutableStateFlow<VideoPlayerState?>(null)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var worker: Job? = null

    /** The library's state while an item is open: the web's player element is drawn from it ([AudioSurfaceHost]). */
    val current: StateFlow<VideoPlayerState?> = held.asStateFlow()
    private var made: VideoPlayerState? = null

    override fun create(events: AudioOutput.Events): AudioOutput {
        if (worker == null) {
            worker = scope.launch(VideoOpenContext) {
                for (command in commands) {
                    try {
                        command()
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Throwable) {
                        // A backend that cannot load (no GStreamer) fails the command; the poll reports the error.
                    }
                }
            }
        }
        return Item(events)
    }

    override fun close() {
        commands.trySend {
            made?.dispose()
            made = null
            held.value = null
        }
        commands.close()
    }

    private fun state(): VideoPlayerState = made ?: make().also { made = it }

    private inner class Item(private val events: AudioOutput.Events) : AudioOutput {
        private var released = false
        private var poll: Job? = null

        private fun queue(command: suspend (VideoPlayerState) -> Unit) {
            commands.trySend { if (!released) command(state()) }
        }

        override fun open(uri: String) {
            queue { player ->
                player.onPlaybackEnded = { if (!released) events.onEnded() }
                held.value = player
                // The last item's error is not this one's.
                runCatching { player.clearError() }
                // Watched from before the open: an open that blocks until it fails is still heard.
                poll?.cancel()
                poll = scope.launch(VideoOpenContext) { watch(player) }
                try {
                    player.openUri(uri, InitialPlayerState.PLAY)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    fail(t.message ?: t.toString())
                }
            }
        }

        /** Said once, and the watching ends. */
        private fun fail(message: String) {
            if (released || failed) return
            failed = true
            poll?.cancel()
            events.onError(message)
        }

        private var failed = false

        override fun play() = queue { it.play() }

        override fun pause() = queue { it.pause() }

        override fun seekTo(ms: Long) = queue { player ->
            val length = player.duration
            if (length > 0) player.seekTo((ms / (length * 1000.0) * 1000.0).toFloat().coerceIn(0f, 1000f))
        }

        override fun setRate(rate: Float) = queue { player ->
            player.playbackSpeed = rate.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
        }

        override fun release() {
            released = true
            poll?.cancel()
            commands.trySend {
                val player = made ?: return@trySend
                player.onPlaybackEnded = {}
                player.stop()
                if (held.value === player) held.value = null
            }
        }

        /** The position, its length, a stop or an end on the player's own: read every [POLL_MS] while open. */
        private suspend fun watch(player: VideoPlayerState) {
            var said: Boolean? = null
            var waited = 0L
            while (currentCoroutineContext().isActive && !released && !failed) {
                delay(POLL_MS)
                if (released) return
                player.error?.let {
                    fail(it.toString())
                    return
                }
                if (player.isLoading || !player.hasMedia) {
                    waited += POLL_MS
                    if (waited >= loadTimeoutMs) {
                        fail("Still loading after ${loadTimeoutMs / 1000} s")
                        return
                    }
                    continue
                }
                val length = player.duration
                val at = player.currentTime
                events.onProgress((at * 1000).toLong(), (length * 1000).toLong())
                if (player.isPlaying != said) {
                    said = player.isPlaying
                    events.onPlayingChanged(player.isPlaying)
                }
            }
        }
    }

    private companion object {
        const val POLL_MS = 250L
        const val LOAD_TIMEOUT_MS = 30_000L
    }
}

/**
 * The web's player is an HTML element that its surface composable makes; elsewhere the library plays without one.
 * Mounted once at the app's root, unseen, while an item is open, so the sound survives every screen change.
 */
@Composable
internal fun AudioSurfaceHost(player: AudioPlayer, outputs: AudioOutputs?) {
    if (!AudioNeedsSurface) return
    val library = outputs as? LibraryAudioOutputs ?: return
    val now by player.state.collectAsState()
    val state by library.current.collectAsState()
    val open = state ?: return
    if (now == null) return
    VideoPlayerSurface(playerState = open, modifier = Modifier.size(1.dp).alpha(0f))
}

/** True where the library's player needs its surface in the composition to make any sound (the web). */
internal expect val AudioNeedsSurface: Boolean
