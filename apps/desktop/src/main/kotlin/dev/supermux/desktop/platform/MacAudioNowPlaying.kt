// macOS's Now Playing and media keys (#118 car 3c): MPNowPlayingInfoCenter shows what plays (the menu bar's Now
// Playing, the Touch Bar), and MPRemoteCommandCenter routes the keyboard's ▶❚❚ key to it, over JNA like the
// AVAudioPlayer the Mac plays through (MacAudioOutputs). The handlers are an Objective-C class made at run time with
// one method per command, since a block cannot be made from JNA: `addTarget:action:` takes the class's instance.
package dev.supermux.desktop.platform

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import dev.supermux.work.MediaCommand
import dev.supermux.work.NowPlayingInfo
import dev.supermux.work.SystemNowPlaying

/** The remote commands the Mac app takes: each the `MPRemoteCommandCenter` property it is. */
internal enum class MacRemote(val property: String) {
    PLAY("playCommand"),
    PAUSE("pauseCommand"),
    TOGGLE("togglePlayPauseCommand"),
    POSITION("changePlaybackPositionCommand"),
    FORWARD("skipForwardCommand"),
    BACKWARD("skipBackwardCommand"),
    ;

    /** The command, given the event's seconds: the position to go to, or the interval to skip. */
    fun command(seconds: Double): MediaCommand = when (this) {
        PLAY -> MediaCommand.Play
        PAUSE -> MediaCommand.Pause
        TOGGLE -> MediaCommand.TogglePlayPause
        POSITION -> MediaCommand.SeekTo((seconds * 1000).toLong().coerceAtLeast(0))
        FORWARD -> MediaCommand.SkipBy(skipMs(seconds))
        BACKWARD -> MediaCommand.SkipBy(-skipMs(seconds))
    }

    private fun skipMs(seconds: Double): Long =
        if (seconds > 0) (seconds * 1000).toLong() else MediaCommand.SKIP_MS
}

/** `MPNowPlayingPlaybackState`: what the Mac's Now Playing shows (and whether the media keys come to us). */
internal fun macPlaybackState(info: NowPlayingInfo?): Long = when {
    info == null -> MP_STATE_STOPPED
    info.playing -> MP_STATE_PLAYING
    else -> MP_STATE_PAUSED
}

internal const val MP_STATE_PLAYING = 1L
internal const val MP_STATE_PAUSED = 2L
internal const val MP_STATE_STOPPED = 3L

/** The MediaPlayer framework's calls, behind a seam: Linux CI has none, so the tests fake it. */
internal interface MpCenter {
    /** Show [info] (null: nothing) and its playback state. */
    fun setInfo(info: NowPlayingInfo?)

    /** Take [remotes]: each event to [handle] with its seconds; true when it was done. */
    fun enable(remotes: List<MacRemote>, handle: (MacRemote, Double) -> Boolean)

    /** Let them go. */
    fun disable()
}

/**
 * The Mac app's [SystemNowPlaying]: the commands are taken on the first show and let go on [clear], so the media keys
 * go back to Music when nothing plays here. A sound that cannot seek takes no scrub or skip.
 */
internal class MacAudioNowPlaying(native: () -> MpCenter = { JnaMpCenter }) : SystemNowPlaying {
    private val center by lazy(native)
    private var enabled: List<MacRemote>? = null

    @Synchronized
    override fun show(info: NowPlayingInfo, commands: (MediaCommand) -> Boolean) {
        runCatching {
            val remotes = if (info.seekable) MacRemote.entries else listOf(MacRemote.PLAY, MacRemote.PAUSE, MacRemote.TOGGLE)
            if (remotes != enabled) {
                if (enabled != null) center.disable()
                center.enable(remotes) { remote, seconds -> commands(remote.command(seconds)) }
                enabled = remotes
            }
            center.setInfo(info)
        }.onFailure { System.err.println("[audio] Now Playing: ${it.message}") }
    }

    @Synchronized
    override fun clear() {
        runCatching {
            center.setInfo(null)
            if (enabled != null) center.disable()
        }
        enabled = null
    }
}

/**
 * [MpCenter] on the real MediaPlayer framework, through `objc_msgSend` as [JnaAvAudio] does. Each call in an
 * autorelease pool of its own: the caller is not an AppKit thread.
 */
internal object JnaMpCenter : MpCenter {
    private val objc = NativeLibrary.getInstance("objc")
    private val mp = NativeLibrary.getInstance("MediaPlayer")

    private val getClass: Function = objc.getFunction("objc_getClass")
    private val registerName: Function = objc.getFunction("sel_registerName")
    private val msgSend: Function = objc.getFunction("objc_msgSend")
    private val poolPush: Function = objc.getFunction("objc_autoreleasePoolPush")
    private val poolPop: Function = objc.getFunction("objc_autoreleasePoolPop")
    private val allocateClassPair: Function = objc.getFunction("objc_allocateClassPair")
    private val registerClassPair: Function = objc.getFunction("objc_registerClassPair")
    private val addMethod: Function = objc.getFunction("class_addMethod")

    /** What a remote command calls: `- (MPRemoteCommandHandlerStatus)supermux_<name>:(MPRemoteCommandEvent *)event`. */
    interface Imp : Callback {
        fun invoke(self: Pointer?, cmd: Pointer?, event: Pointer?): Long
    }

    @Volatile private var handle: ((MacRemote, Double) -> Boolean)? = null

    /** Held for the process: the runtime calls them as long as the class lives. */
    private val imps: Map<MacRemote, Imp> = MacRemote.entries.associateWith { remote ->
        object : Imp {
            override fun invoke(self: Pointer?, cmd: Pointer?, event: Pointer?): Long = runCatching {
                val seconds = when (remote) {
                    MacRemote.POSITION -> event?.let { sendDouble(it, "positionTime") } ?: 0.0
                    MacRemote.FORWARD, MacRemote.BACKWARD -> event?.let { sendDouble(it, "interval") } ?: 0.0
                    else -> 0.0
                }
                if (handle?.invoke(remote, seconds) == true) STATUS_SUCCESS else STATUS_NO_ITEM
            }.getOrDefault(STATUS_FAILED)
        }
    }

    private fun selectorName(remote: MacRemote) = "supermux_${remote.name.lowercase()}:"

    /** The target the commands call: an instance of a class made here, once. */
    private val target: Pointer by lazy {
        val name = "SupermuxRemoteTarget"
        val existing = getClass.invokePointer(arrayOf(name))
        val klass = existing ?: run {
            val made = allocateClassPair.invokePointer(arrayOf(cls("NSObject"), name, 0L))
                ?: error("Couldn't make $name")
            for ((remote, imp) in imps) addMethod.invokeInt(arrayOf(made, sel(selectorName(remote)), imp, "q@:@"))
            registerClassPair.invokeVoid(arrayOf(made))
            made
        }
        val allocated = send(klass, "alloc") ?: error("Couldn't alloc $name")
        send(allocated, "init") ?: error("Couldn't init $name")
    }

    private fun cls(name: String): Pointer = getClass.invokePointer(arrayOf(name)) ?: error("No class $name")

    private fun sel(name: String): Pointer = registerName.invokePointer(arrayOf(name))

    private fun send(receiver: Pointer, selector: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(receiver, sel(selector), *args))

    private fun sendVoid(receiver: Pointer, selector: String, vararg args: Any?) {
        msgSend.invokeVoid(arrayOf(receiver, sel(selector), *args))
    }

    private fun sendDouble(receiver: Pointer, selector: String): Double =
        msgSend.invokeDouble(arrayOf(receiver, sel(selector)))

    private fun <T> pooled(block: () -> T): T {
        val pool = poolPush.invokePointer(emptyArray<Any>())
        try {
            return block()
        } finally {
            poolPop.invokeVoid(arrayOf(pool))
        }
    }

    private fun string(text: String): Pointer {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val c = Memory(bytes.size + 1L).apply {
            write(0, bytes, 0, bytes.size)
            setByte(bytes.size.toLong(), 0)
        }
        return send(cls("NSString"), "stringWithUTF8String:", c) ?: error("Couldn't make a string")
    }

    private fun number(value: Double): Pointer = send(cls("NSNumber"), "numberWithDouble:", value) ?: error("No number")

    /** One of the framework's `NSString *const` keys. */
    private fun key(symbol: String): Pointer = mp.getGlobalVariableAddress(symbol).getPointer(0)

    private fun commandCenter(): Pointer = send(cls("MPRemoteCommandCenter"), "sharedCommandCenter") ?: error("No command center")

    override fun setInfo(info: NowPlayingInfo?) = pooled {
        val center = send(cls("MPNowPlayingInfoCenter"), "defaultCenter") ?: error("No Now Playing center")
        if (info == null) {
            sendVoid(center, "setNowPlayingInfo:", null)
        } else {
            val dict = send(cls("NSMutableDictionary"), "dictionary") ?: error("No dictionary")
            fun put(symbol: String, value: Pointer) = sendVoid(dict, "setObject:forKey:", value, key(symbol))
            put("MPMediaItemPropertyTitle", string(info.title))
            info.artist?.let { put("MPMediaItemPropertyArtist", string(it)) }
            info.durationSec?.let { put("MPMediaItemPropertyPlaybackDuration", number(it)) }
            put("MPNowPlayingInfoPropertyElapsedPlaybackTime", number(info.elapsedSec))
            put("MPNowPlayingInfoPropertyPlaybackRate", number(info.rate))
            sendVoid(center, "setNowPlayingInfo:", dict)
        }
        sendVoid(center, "setPlaybackState:", macPlaybackState(info))
    }

    override fun enable(remotes: List<MacRemote>, handle: (MacRemote, Double) -> Boolean) = pooled {
        this.handle = handle
        val center = commandCenter()
        val skip = send(cls("NSArray"), "arrayWithObject:", number(MediaCommand.SKIP_MS / 1000.0))
        for (remote in remotes) {
            val command = send(center, remote.property) ?: continue
            if (remote == MacRemote.FORWARD || remote == MacRemote.BACKWARD) sendVoid(command, "setPreferredIntervals:", skip)
            sendVoid(command, "setEnabled:", 1)
            sendVoid(command, "addTarget:action:", target, sel(selectorName(remote)))
        }
    }

    override fun disable() = pooled {
        val center = commandCenter()
        for (remote in MacRemote.entries) {
            val command = send(center, remote.property) ?: continue
            sendVoid(command, "removeTarget:", target)
            sendVoid(command, "setEnabled:", 0)
        }
        handle = null
    }

    private const val STATUS_SUCCESS = 0L
    private const val STATUS_NO_ITEM = 110L
    private const val STATUS_FAILED = 200L
}
