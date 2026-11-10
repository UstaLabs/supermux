// iOS's Now Playing (#118 car 3b): what plays shows on the lock screen and in Control Center, and their ▶❚❚, scrubber
// and ±15 s drive the app's audio player. The shared SystemMediaControls decides what to show and when; this only
// tells MediaPlayer, on the main thread.
package dev.supermux.ios

import dev.supermux.work.MediaCommand
import dev.supermux.work.NowPlayingInfo
import dev.supermux.work.SystemNowPlaying
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNumber
import platform.Foundation.numberWithDouble
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyDefaultPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatus
import platform.MediaPlayer.MPSkipIntervalCommandEvent

/**
 * The iOS app's [SystemNowPlaying]: MPNowPlayingInfoCenter for what shows, MPRemoteCommandCenter for what the lock
 * screen asks. The commands are taken on the first show and let go on [clear]; a sound that cannot seek (the OS voice)
 * takes no scrub or skip. Background audio itself is the app's `UIBackgroundModes: audio` and the playback session
 * the outputs set ([IosAudioOutputs]) and read-aloud sets (`MessageSpeech`).
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosNowPlaying private constructor() : SystemNowPlaying {
    /** Each command taken, with the target its handler made, to remove. */
    private val targets = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private var seekable: Boolean? = null
    private var commands: ((MediaCommand) -> Boolean)? = null

    override fun show(info: NowPlayingInfo, commands: (MediaCommand) -> Boolean) = onMainThread {
        this.commands = commands
        if (seekable != info.seekable) {
            letGo()
            take(info.seekable)
            seekable = info.seekable
        }
        val shown = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to info.title,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to NSNumber.numberWithDouble(info.elapsedSec),
            MPNowPlayingInfoPropertyPlaybackRate to NSNumber.numberWithDouble(info.rate),
            MPNowPlayingInfoPropertyDefaultPlaybackRate to NSNumber.numberWithDouble(1.0),
        )
        info.artist?.let { shown[MPMediaItemPropertyArtist] = it }
        info.durationSec?.let { shown[MPMediaItemPropertyPlaybackDuration] = NSNumber.numberWithDouble(it) }
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = shown
    }

    override fun clear() = onMainThread {
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        letGo()
        seekable = null
        commands = null
    }

    private fun take(seekable: Boolean) {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        on(center.playCommand) { MediaCommand.Play }
        on(center.pauseCommand) { MediaCommand.Pause }
        on(center.togglePlayPauseCommand) { MediaCommand.TogglePlayPause }
        if (!seekable) return
        on(center.changePlaybackPositionCommand) { event ->
            val at = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime ?: return@on null
            MediaCommand.SeekTo((at * 1000).toLong().coerceAtLeast(0))
        }
        val skip = listOf(NSNumber.numberWithDouble(MediaCommand.SKIP_MS / 1000.0))
        center.skipForwardCommand.preferredIntervals = skip
        center.skipBackwardCommand.preferredIntervals = skip
        on(center.skipForwardCommand) { event -> MediaCommand.SkipBy(skipMs(event)) }
        on(center.skipBackwardCommand) { event -> MediaCommand.SkipBy(-skipMs(event)) }
    }

    private fun skipMs(event: MPRemoteCommandEvent?): Long {
        val seconds = (event as? MPSkipIntervalCommandEvent)?.interval ?: 0.0
        return if (seconds > 0) (seconds * 1000).toLong() else MediaCommand.SKIP_MS
    }

    /** Take [command]: its event becomes [map]'s command, done by the player. */
    private fun on(command: MPRemoteCommand, map: (MPRemoteCommandEvent?) -> MediaCommand?) {
        command.enabled = true
        val target = command.addTargetWithHandler { event ->
            val done = map(event)?.let { commands?.invoke(it) } == true
            if (done) {
                MPRemoteCommandHandlerStatus.MPRemoteCommandHandlerStatusSuccess
            } else {
                MPRemoteCommandHandlerStatus.MPRemoteCommandHandlerStatusNoActionableNowPlayingItem
            }
        }
        targets += command to target
    }

    private fun letGo() {
        for ((command, target) in targets) {
            command.removeTarget(target)
            command.enabled = false
        }
        targets.clear()
    }

    companion object {
        /** One for the process: the command center is. */
        val shared: IosNowPlaying by lazy { IosNowPlaying() }
    }
}
