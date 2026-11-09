package dev.supermux.work

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Something to play (#118): its [title], where to read it from ([source]: a file path or a URL the platform's player
 * opens), the chat message it came from ([origin]) with that chat's name ([chat]), and the download [key] it was
 * fetched under, so the card it came from knows it is the one playing. [durationMs] is what the sender said it lasts,
 * until the player knows.
 */
data class AudioItem(
    val title: String,
    val source: String,
    val origin: DownloadOrigin? = null,
    val chat: String? = null,
    val key: String? = null,
    val durationMs: Long? = null,
)

/** What plays now: [item], whether it is [playing], where it is ([positionMs] of [durationMs]) and at what [speed]. */
data class NowPlaying(
    val item: AudioItem,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
)

/**
 * The noise itself: one item opened in the platform's player. The [AudioPlayer]'s state machine talks to it and
 * nothing else, so it is tested against a fake. Called on the UI thread.
 */
interface AudioOutput {
    fun open(uri: String)

    fun play()

    fun pause()

    fun seekTo(ms: Long)

    fun setRate(rate: Float)

    /** Done with this item: stop, and say nothing more. */
    fun release()

    /** What an output says back, from whatever thread it runs on. */
    interface Events {
        /** Where it is, and how long the item is once known (0 until then). */
        fun onProgress(positionMs: Long, durationMs: Long)

        /** It started or stopped on its own (a system pause, the end of buffering). */
        fun onPlayingChanged(playing: Boolean)

        fun onEnded()

        fun onError(message: String)
    }
}

/** Makes the output for each item; [close] lets go of whatever it holds for all of them (the app is closing). */
fun interface AudioOutputs {
    fun create(events: AudioOutput.Events): AudioOutput

    fun close() {}
}

/**
 * The app's audio player (#118): one item at a time, owned by the fleet like its downloads, so what plays outlives
 * the chat and the screen that started it — leaving a chat, a rotation, a layout change — and stops only with [stop],
 * its end, or the app closing ([close]). A new [play] replaces the old item.
 *
 * Compose-free: [state] is what the UI draws; [outputs] (installed by the shell, [useOutputs]) makes the sound.
 */
class AudioPlayer(private var outputs: AudioOutputs? = null) {
    private val lock = SynchronizedObject()
    private val now = MutableStateFlow<NowPlaying?>(null)
    private var output: AudioOutput? = null
    private var lastError: String? = null

    /** What plays now, or null while idle. */
    val state: StateFlow<NowPlaying?> = now.asStateFlow()

    /** Why the last item stopped on its own with an error, until the next [play]. */
    val error: String? get() = lastError

    /** Whether the shell has installed the outputs: a recreated shell (an Android rotation) keeps the ones it has. */
    val hasOutputs: Boolean get() = synchronized(lock) { outputs != null }

    /** The platform's outputs, from the shell. An item already playing keeps the output it has. */
    fun useOutputs(outputs: AudioOutputs) {
        synchronized(lock) { this.outputs = outputs }
    }

    /** Play [item] from the start, at the speed the last one had; whatever played stops. Nothing without outputs. */
    fun play(item: AudioItem) {
        val events = Events()
        val (old, out, speed) = synchronized(lock) {
            val old = output
            val speed = now.value?.speed ?: 1f
            lastError = null
            val out = outputs?.create(events)?.also { events.mine = it }
            output = out
            now.value = out?.let { NowPlaying(item, playing = true, positionMs = 0, durationMs = item.durationMs ?: 0, speed = speed) }
            Triple(old, out, speed)
        }
        old?.release()
        out ?: return
        out.open(item.source)
        if (speed != 1f) out.setRate(speed)
        out.play()
    }

    /** ❚❚ / ▶. */
    fun toggle() {
        val (out, playing) = synchronized(lock) {
            val current = now.value ?: return
            val out = output ?: return
            now.value = current.copy(playing = !current.playing)
            out to !current.playing
        }
        if (playing) out.play() else out.pause()
    }

    /** To [ms] into the item, kept within it. */
    fun seek(ms: Long) {
        val (out, to) = synchronized(lock) {
            val current = now.value ?: return
            val out = output ?: return
            val end = current.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
            val to = ms.coerceIn(0, end)
            now.value = current.copy(positionMs = to)
            out to to
        }
        out.seekTo(to)
    }

    fun setSpeed(speed: Float) {
        val out = synchronized(lock) {
            val current = now.value ?: return
            now.value = current.copy(speed = speed)
            output
        }
        out?.setRate(speed)
    }

    /** The dock's speed: 1× → 1.5× → 2× → 1×. */
    fun nextSpeed() {
        val speed = now.value?.speed ?: return
        setSpeed(SPEEDS.firstOrNull { it > speed + 0.01f } ?: SPEEDS.first())
    }

    /** ✕: stop and forget the item. */
    fun stop() {
        val out = synchronized(lock) {
            val out = output
            output = null
            now.value = null
            out
        }
        out?.release()
    }

    /** The app is closing. */
    fun close() {
        stop()
        synchronized(lock) { outputs }?.close()
    }

    /** One output's words, heard only while it is still the current one: a replaced item says nothing more. */
    private inner class Events : AudioOutput.Events {
        var mine: AudioOutput? = null

        private fun current(): Boolean = mine != null && output === mine

        override fun onProgress(positionMs: Long, durationMs: Long) {
            synchronized(lock) {
                if (!current()) return
                now.update { it?.copy(positionMs = positionMs.coerceAtLeast(0), durationMs = if (durationMs > 0) durationMs else it.durationMs) }
            }
        }

        override fun onPlayingChanged(playing: Boolean) {
            synchronized(lock) {
                if (!current()) return
                now.update { it?.copy(playing = playing) }
            }
        }

        override fun onEnded() {
            if (synchronized(lock) { current() }) stop()
        }

        override fun onError(message: String) {
            if (synchronized(lock) { current().also { if (it) lastError = message } }) stop()
        }
    }

    companion object {
        val SPEEDS = listOf(1f, 1.5f, 2f)
    }
}
