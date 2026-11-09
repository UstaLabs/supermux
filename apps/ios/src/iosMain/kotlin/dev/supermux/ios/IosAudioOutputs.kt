// iOS's audio (#118): AVAudioPlayer for the app's voice notes, the twin of the Mac app's (desktop MacAudioOutputs).
// The video library's AVPlayer backend wants a video track; AVAudioPlayer plays an audio file as it is.
package dev.supermux.ios

import dev.supermux.work.AudioOutput
import dev.supermux.work.AudioOutputs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSURL

/**
 * The iOS app's [AudioOutputs]: each item one AVAudioPlayer, every call on the main thread in order, its position and
 * length polled every [POLL_MS] to the player; its end is a stop we did not ask for at the start or the end of the
 * file. The audio session is set to playback while it plays, so the note is heard with the ringer switch on silent.
 * Local files only ([canStreamUrl] false): read-aloud's streamed voice plays through the TTS engine instead.
 *
 * Shared by every player that asks the platform for it: [close] leaves it usable.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAudioOutputs : AudioOutputs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val canStreamUrl: Boolean get() = false

    override fun create(events: AudioOutput.Events): AudioOutput = Item(events)

    private inner class Item(private val events: AudioOutput.Events) : AudioOutput {
        private var released = false
        private var player: AVAudioPlayer? = null
        private var poll: Job? = null

        /** What we last asked for or reported: playing or not. A change we did not cause is news. */
        private var said = false

        private fun later(block: () -> Unit) {
            scope.launch { if (!released) block() }
        }

        private fun failed(message: String) {
            if (released) return
            poll?.cancel()
            poll = null
            player?.stop()
            player = null
            events.onError(message)
        }

        override fun open(uri: String) = later {
            val url = when {
                uri.startsWith("file:", ignoreCase = true) -> NSURL.URLWithString(uri)
                uri.startsWith("/") -> NSURL.fileURLWithPath(uri) // platform-ok: this iPhone's own cache file, not a host path
                else -> null
            } ?: return@later failed("Not a local file: $uri")
            val (made, why) = load(url)
            val p = made ?: return@later failed(why ?: "Couldn't open $uri")
            p.enableRate = true
            p.prepareToPlay()
            player = p
            poll = scope.launch {
                while (isActive && !released) {
                    delay(POLL_MS)
                    if (!released) watch()
                }
            }
        }

        override fun play() = later {
            val p = player ?: return@later
            activateSession()
            if (!p.play()) return@later failed("AVAudioPlayer would not play")
            said = true
        }

        override fun pause() = later {
            val p = player ?: return@later
            p.pause()
            said = false
        }

        override fun seekTo(ms: Long) = later { player?.currentTime = ms / 1000.0 }

        override fun setRate(rate: Float) = later { player?.rate = rate }

        override fun release() {
            if (released) return
            released = true
            scope.launch {
                poll?.cancel()
                poll = null
                player?.stop()
                player = null
            }
        }

        private fun watch() {
            val p = player ?: return
            val at = p.currentTime
            val length = p.duration
            val playing = p.playing
            events.onProgress((at * 1000).toLong(), (length * 1000).toLong())
            if (playing == said) return
            if (said && !playing && (at <= END_SLACK || at >= length - END_SLACK)) {
                poll?.cancel()
                poll = null
                events.onEnded()
                return
            }
            said = playing
            events.onPlayingChanged(playing)
        }
    }

    /** The player for [url], or null with AVFoundation's reason. */
    private fun load(url: NSURL): Pair<AVAudioPlayer?, String?> = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val made = runCatching { AVAudioPlayer(contentsOfURL = url, error = error.ptr) }.getOrNull()
        val why = error.value?.localizedDescription
        if (why != null) null to why else made to null
    }

    /** Playback, so a note plays with the ringer switch on silent; a failure leaves the session as it was. */
    private fun activateSession() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
    }

    private companion object {
        const val POLL_MS = 250L

        /** Seconds from either end at which a stop is the item's end. */
        const val END_SLACK = 0.05
    }
}
