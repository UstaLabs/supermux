package dev.supermux.work

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * What the system shows of what plays (#118 cars 3b/3c): the lock screen and Control Center on iOS, the Mac's Now
 * Playing. [title] is the item's, [artist] the chat it came from. [durationSec] is null while the length is unknown or
 * the sound cannot seek; [rate] is 0 while paused, the speed while it plays. [seekable]: the system may scrub and skip.
 */
data class NowPlayingInfo(
    val title: String,
    val artist: String?,
    val durationSec: Double?,
    val elapsedSec: Double,
    val rate: Double,
    val playing: Boolean,
    val seekable: Boolean,
)

/** The player's state as the system shows it; null (nothing to show) while idle or once the item has failed. */
fun nowPlayingInfo(now: NowPlaying?): NowPlayingInfo? {
    if (now == null || now.failed != null) return null
    val seekable = !now.stopOnly
    return NowPlayingInfo(
        title = now.item.title,
        artist = now.item.chat,
        durationSec = now.durationMs.takeIf { seekable && it > 0 }?.let { it / 1000.0 },
        elapsedSec = if (seekable) now.positionMs / 1000.0 else 0.0,
        rate = if (now.playing) now.speed.toDouble() else 0.0,
        playing = now.playing,
        seekable = seekable,
    )
}

/** What the system asks of the player: the lock screen, a headset, the keyboard's media keys. */
sealed interface MediaCommand {
    data object Play : MediaCommand
    data object Pause : MediaCommand
    data object TogglePlayPause : MediaCommand

    /** To [ms] into the item (the scrubber). */
    data class SeekTo(val ms: Long) : MediaCommand

    /** [ms] on (or back, negative) from where it is: the ±15 s buttons. */
    data class SkipBy(val ms: Long) : MediaCommand

    companion object {
        const val SKIP_MS = 15_000L
    }
}

/**
 * Do [command] to what plays. A sound the player cannot drive ([NowPlaying.stopOnly]: the OS voice reading a
 * message) stops on a pause, and cannot seek. False when there is nothing it applies to: the system says so.
 */
fun AudioPlayer.perform(command: MediaCommand): Boolean {
    val now = state.value ?: return false
    if (now.failed != null) return false
    if (now.stopOnly) {
        return when (command) {
            MediaCommand.Play -> true
            MediaCommand.Pause, MediaCommand.TogglePlayPause -> {
                stop()
                true
            }
            is MediaCommand.SeekTo, is MediaCommand.SkipBy -> false
        }
    }
    when (command) {
        MediaCommand.Play -> if (!now.playing) toggle()
        MediaCommand.Pause -> if (now.playing) toggle()
        MediaCommand.TogglePlayPause -> toggle()
        is MediaCommand.SeekTo -> seek(command.ms)
        is MediaCommand.SkipBy -> seek(now.positionMs + command.ms)
    }
    return true
}

/**
 * The system's Now Playing, behind a seam: MPNowPlayingInfoCenter + MPRemoteCommandCenter on iOS and macOS, the media
 * keys on Windows. Commands are taken only while something shows, so an idle supermux holds no one's media keys.
 */
interface SystemNowPlaying {
    /** Show [info]. The first show after a [clear] (or ever) starts sending the system's commands to [commands]. */
    fun show(info: NowPlayingInfo, commands: (MediaCommand) -> Boolean)

    /** Nothing plays: show nothing, take no commands. */
    fun clear()
}

/**
 * Keeps [system] showing what [player] plays and hands its commands to the player. The info is pushed when anything but
 * the elapsed time changes, or when the elapsed time leaves where the system would have it by now (a seek) by more
 * than [DRIFT_SEC]: the system runs the clock itself from the elapsed time and the rate.
 */
class SystemMediaControls(
    private val player: AudioPlayer,
    private val system: SystemNowPlaying,
    private val clockMs: () -> Long = monotonicClock(),
) {
    private var shown: NowPlayingInfo? = null
    private var shownAtMs = 0L

    /** Follow the player on [scope] until it ends. */
    fun start(scope: CoroutineScope): Job = scope.launch { player.state.collect { update(it) } }

    /** The player's state is [now]. */
    fun update(now: NowPlaying?) {
        val info = nowPlayingInfo(now)
        val last = shown
        if (info == null) {
            if (last != null) {
                shown = null
                system.clear()
            }
            return
        }
        val at = clockMs()
        if (last != null && last.copy(elapsedSec = info.elapsedSec) == info) {
            val expected = last.elapsedSec + (at - shownAtMs) / 1000.0 * last.rate
            if (abs(info.elapsedSec - expected) <= DRIFT_SEC) return
        }
        shown = info
        shownAtMs = at
        system.show(info, ::command)
    }

    private fun command(command: MediaCommand): Boolean = player.perform(command)

    companion object {
        const val DRIFT_SEC = 1.0

        private fun monotonicClock(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}
