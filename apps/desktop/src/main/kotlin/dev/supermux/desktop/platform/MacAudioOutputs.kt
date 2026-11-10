// macOS's audio (#118): AVAudioPlayer through the Objective-C runtime, over JNA. The video library the other platforms
// play audio through cannot play an audio-only file on macOS (its backend wants a video size: "Failed to retrieve video
// size", after ~7 s of loading), so the Mac app plays its voice notes here.
package dev.supermux.desktop.platform

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import dev.supermux.work.AudioOutput
import dev.supermux.work.AudioOutputs
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Why AVAudioPlayer would not take a file: its own words. */
internal class AvAudioException(message: String) : Exception(message)

/** The AVAudioPlayer calls the outputs make, behind a seam: Linux CI has no AVFoundation, so the tests fake it. */
internal interface AvAudio {
    /** A player for the file at [path], prepared, its rate enabled; [AvAudioException] when it cannot be read. */
    fun load(path: String): Sound

    /** One `AVAudioPlayer`. */
    interface Sound {
        val playing: Boolean
        val currentTime: Double
        val duration: Double

        /** False when it would not start. */
        fun play(): Boolean
        fun pause()
        fun seek(seconds: Double)
        fun rate(rate: Float)

        /** Stop, and let the object go (`release`). */
        fun release()
    }
}

/** Where the outputs run: [run] in order, [every] a poll until its cancel is called. One thread in the app. */
internal interface AudioLoop {
    fun run(block: () -> Unit)

    fun every(ms: Long, block: () -> Unit): () -> Unit
}

/** One daemon thread for every call into AVAudioPlayer, made on first use: it tolerates a thread of its own. */
internal class ThreadLoop : AudioLoop {
    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "supermux-audio").apply { isDaemon = true } }
    }

    override fun run(block: () -> Unit) {
        executor.execute { runCatching(block) }
    }

    override fun every(ms: Long, block: () -> Unit): () -> Unit {
        val task = executor.scheduleWithFixedDelay({ runCatching(block) }, ms, ms, TimeUnit.MILLISECONDS)
        return { task.cancel(false) }
    }
}

/**
 * The Mac app's [AudioOutputs]: each item one AVAudioPlayer on [loop]'s thread, its position and length polled every
 * [POLL_MS] to the player, its end seen as a stop we did not ask for at the start or the end of the file. A local
 * file only ([canStreamUrl] false): read-aloud's streamed voice plays through the TTS engine instead.
 *
 * Shared by every [dev.supermux.work.AudioPlayer] that asks the platform for it: [close] leaves it usable.
 */
internal class MacAudioOutputs(
    native: () -> AvAudio = { JnaAvAudio },
    private val loop: AudioLoop = ThreadLoop(),
) : AudioOutputs {
    private val av by lazy(native)

    override val canStreamUrl: Boolean get() = false

    override fun create(events: AudioOutput.Events): AudioOutput = Item(events)

    private inner class Item(private val events: AudioOutput.Events) : AudioOutput {
        // Touched on the loop's thread only, but for released: read on the caller's as well.
        @Volatile private var released = false
        private var sound: AvAudio.Sound? = null
        private var cancelPoll: (() -> Unit)? = null

        /** What we last asked for or reported: playing or not. A change we did not cause is news. */
        private var said = false
        private var ended = false

        private fun later(block: () -> Unit) = loop.run { if (!released) block() }

        private fun failed(message: String) {
            if (released) return
            cancelPoll?.invoke()
            cancelPoll = null
            sound?.release()
            sound = null
            events.onError(message)
        }

        override fun open(uri: String) = later {
            val path = localPath(uri) ?: return@later failed("Not a local file: $uri")
            val loaded = try {
                av.load(path)
            } catch (e: Throwable) {
                return@later failed(e.message ?: "Couldn't open $path")
            }
            sound = loaded
            cancelPoll = loop.every(POLL_MS) { if (!released) poll() }
        }

        override fun play() = later {
            val s = sound ?: return@later
            if (!s.play()) return@later failed("AVAudioPlayer would not play")
            said = true
        }

        override fun pause() = later {
            val s = sound ?: return@later
            s.pause()
            said = false
        }

        override fun seekTo(ms: Long) = later { sound?.seek(ms / 1000.0) }

        override fun setRate(rate: Float) = later { sound?.rate(rate) }

        override fun release() {
            if (released) return
            loop.run {
                cancelPoll?.invoke()
                cancelPoll = null
                sound?.let {
                    it.pause()
                    it.release()
                }
                sound = null
            }
            released = true
        }

        private fun poll() {
            val s = sound ?: return
            if (ended) return
            val at = s.currentTime
            val length = s.duration
            val playing = s.playing
            events.onProgress((at * 1000).toLong(), (length * 1000).toLong())
            if (playing == said) return
            if (said && !playing && (at <= END_SLACK || at >= length - END_SLACK)) {
                // AVAudioPlayer at its end: it stops by itself, back at the start (or at the end).
                ended = true
                cancelPoll?.invoke()
                cancelPoll = null
                events.onEnded()
                return
            }
            said = playing
            events.onPlayingChanged(playing)
        }
    }

    private companion object {
        const val POLL_MS = 250L

        /** Seconds from either end at which a stop is the item's end. */
        const val END_SLACK = 0.05
    }
}

/** A file path, or a `file:` URI's path; null for anything AVAudioPlayer cannot open (an http stream). */
internal fun localPath(uri: String): String? = when {
    uri.startsWith("file:", ignoreCase = true) -> runCatching { URI(uri).path }.getOrElse { uri.removePrefix("file://") }
    uri.startsWith("/") -> uri // platform-ok: this Mac's own cache file (macOS only), not a host path
    else -> null
}

/** The desktop's audio outputs: AVAudioPlayer on macOS; null elsewhere, where the library plays (unverified there). */
internal fun desktopAudioOutputs(os: DesktopOs = DesktopOs.current): AudioOutputs? =
    if (os == DesktopOs.MACOS) MacAudioOutputs() else null

/**
 * [AvAudio] on the real AVAudioPlayer, through `objc_msgSend`. arm64's `objc_msgSend` takes its arguments as the
 * method declares them, which is how JNA passes them (doubles and floats in their registers, no varargs promotion).
 */
internal object JnaAvAudio : AvAudio {
    private val objc = NativeLibrary.getInstance("objc")

    init {
        // Loads the framework, so its classes are known to the runtime.
        NativeLibrary.getInstance("AVFoundation")
    }

    private val getClass: Function = objc.getFunction("objc_getClass")
    private val registerName: Function = objc.getFunction("sel_registerName")
    private val msgSend: Function = objc.getFunction("objc_msgSend")
    private val poolPush: Function = objc.getFunction("objc_autoreleasePoolPush")
    private val poolPop: Function = objc.getFunction("objc_autoreleasePoolPop")
    private val selectors = java.util.concurrent.ConcurrentHashMap<String, Pointer>()

    private fun cls(name: String): Pointer = getClass.invokePointer(arrayOf(name))
        ?: throw AvAudioException("No class $name")

    private fun sel(name: String): Pointer = selectors.getOrPut(name) { registerName.invokePointer(arrayOf(name)) }

    private fun send(receiver: Pointer, selector: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(receiver, sel(selector), *args))

    private fun sendBool(receiver: Pointer, selector: String): Boolean =
        msgSend.invokeInt(arrayOf(receiver, sel(selector))) and 0xFF != 0

    private fun sendDouble(receiver: Pointer, selector: String): Double =
        msgSend.invokeDouble(arrayOf(receiver, sel(selector)))

    private fun sendVoid(receiver: Pointer, selector: String, vararg args: Any?) {
        msgSend.invokeVoid(arrayOf(receiver, sel(selector), *args))
    }

    /** A C string of [text] in UTF-8, whatever JNA's default encoding. */
    private fun utf8(text: String): Memory {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return Memory(bytes.size + 1L).apply {
            write(0, bytes, 0, bytes.size)
            setByte(bytes.size.toLong(), 0)
        }
    }

    override fun load(path: String): AvAudio.Sound {
        val pool = poolPush.invokePointer(emptyArray<Any>())
        try {
            val string = send(cls("NSString"), "stringWithUTF8String:", utf8(path))
                ?: throw AvAudioException("Couldn't name $path")
            val url = send(cls("NSURL"), "fileURLWithPath:", string) ?: throw AvAudioException("Couldn't open $path")
            val error = PointerByReference()
            val allocated = send(cls("AVAudioPlayer"), "alloc") ?: throw AvAudioException("No AVAudioPlayer")
            val player = send(allocated, "initWithContentsOfURL:error:", url, error)
                ?: throw AvAudioException(describe(error.value) ?: "Couldn't play $path")
            sendVoid(player, "setEnableRate:", 1)
            sendBool(player, "prepareToPlay")
            return Sound(player)
        } finally {
            poolPop.invokeVoid(arrayOf(pool))
        }
    }

    /** An NSError's localized description. */
    private fun describe(error: Pointer?): String? {
        error ?: return null
        val text = send(error, "localizedDescription") ?: return null
        return send(text, "UTF8String")?.getString(0, "UTF-8")
    }

    private class Sound(private var player: Pointer?) : AvAudio.Sound {
        private fun p(): Pointer = player ?: throw IllegalStateException("released")

        override val playing: Boolean get() = player?.let { sendBool(it, "isPlaying") } ?: false
        override val currentTime: Double get() = player?.let { sendDouble(it, "currentTime") } ?: 0.0
        override val duration: Double get() = player?.let { sendDouble(it, "duration") } ?: 0.0

        override fun play(): Boolean = sendBool(p(), "play")

        override fun pause() = sendVoid(p(), "pause")

        override fun seek(seconds: Double) = sendVoid(p(), "setCurrentTime:", seconds)

        override fun rate(rate: Float) = sendVoid(p(), "setRate:", rate)

        override fun release() {
            val it = player ?: return
            player = null
            sendVoid(it, "stop")
            sendVoid(it, "release")
        }
    }
}
