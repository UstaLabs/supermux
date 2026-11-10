// Android's audio (#118 car 3a): the app's audio player through a media session, so what plays goes on with the app
// in the background and shows the system's media notification and lock-screen controls.
package dev.supermux.android.platform

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.supermux.work.AudioItem
import dev.supermux.work.AudioOutput
import dev.supermux.work.AudioOutputs
import java.util.concurrent.atomic.AtomicReference

/**
 * One item as the media session shows it: [title] in the notification and on the lock screen, its chat as the
 * [artist] line under it. [id] tells this item's events from a replaced one's (the controller's are asynchronous).
 */
data class AudioMedia(val id: String, val uri: String, val title: String, val artist: String?)

internal fun audioMedia(item: AudioItem, id: String): AudioMedia =
    AudioMedia(id = id, uri = item.source, title = item.title, artist = item.chat)

/**
 * The media session's player as the outputs use it: a `MediaController` to [AudioPlaybackService]'s ExoPlayer on a
 * phone, a fake in the tests. Called on the main thread; its listener is told there too.
 */
internal interface SessionPlayer {
    /** The id of the item loaded now ([AudioMedia.id]), null when none is. */
    val mediaId: String?
    val positionMs: Long

    /** 0 while unknown. */
    val durationMs: Long

    fun listen(listener: Listener)
    fun load(media: AudioMedia)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)

    /** Stop and unload: the notification goes. */
    fun stop()

    /** What the player says, each with the id of the item it was about. */
    interface Listener {
        /** Whether it means to play: ▶/❚❚ from the notification, a lost audio focus, headphones out. */
        fun onPlayingChanged(mediaId: String?, playing: Boolean)
        fun onEnded(mediaId: String?)
        fun onError(mediaId: String?, message: String)

        /** The item the player holds now (null: none), whoever changed it: the service emptying it on its way out. */
        fun onItemChanged(mediaId: String?)

        /** The session is gone (the service stopped: supermux swiped away). */
        fun onDisconnected()
    }
}

/** Where the outputs run: [run] on the main thread in order, [every] a poll until its cancel is called. */
internal interface AndroidAudioLoop {
    fun run(block: () -> Unit)
    fun every(ms: Long, block: () -> Unit): () -> Unit
}

/** The main thread: a controller is bound to the thread that made it. */
internal object MainAudioLoop : AndroidAudioLoop {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun run(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    override fun every(ms: Long, block: () -> Unit): () -> Unit {
        var cancelled = false
        val tick = object : Runnable {
            override fun run() {
                if (cancelled) return
                block()
                if (!cancelled) handler.postDelayed(this, ms)
            }
        }
        handler.postDelayed(tick, ms)
        return {
            cancelled = true
            handler.removeCallbacks(tick)
        }
    }
}

/**
 * The Android app's [AudioOutputs]: every item plays on the one media session's player ([AudioPlaybackService]), so
 * Media3 posts the notification, keeps the service in the foreground while it plays, and the lock screen and a
 * headset drive it. The session is connected on first use and kept; commands wait for it. The item loaded last owns
 * the player: a released one stops it only while it still does, and a replaced one hears nothing more. ExoPlayer
 * streams a URL as it plays a file ([canStreamUrl]): read-aloud's codex voice plays here too.
 *
 * Shared by every player in the process ([AndroidAudio]): [close] leaves it usable.
 */
internal class AndroidAudioOutputs(
    private val connect: (onReady: (SessionPlayer) -> Unit, onFailed: (String) -> Unit) -> Unit,
    private val loop: AndroidAudioLoop = MainAudioLoop,
) : AudioOutputs, SessionPlayer.Listener {
    private var player: SessionPlayer? = null
    private var connecting = false
    private val waiting = mutableListOf<(SessionPlayer) -> Unit>()
    private var owner: Item? = null
    private var made = 0L

    override val canStreamUrl: Boolean get() = true

    override fun create(events: AudioOutput.Events): AudioOutput = Item(events, "supermux-audio-${++made}")

    /** [block] with the session's player: now, or once it is connected. */
    private fun withPlayer(block: (SessionPlayer) -> Unit) {
        player?.let { return block(it) }
        waiting += block
        if (connecting) return
        connecting = true
        connect(
            { ready ->
                loop.run {
                    connecting = false
                    player = ready
                    ready.listen(this)
                    val queued = waiting.toList()
                    waiting.clear()
                    queued.forEach { it(ready) }
                }
            },
            { why ->
                loop.run {
                    connecting = false
                    waiting.clear()
                    owner?.fail(why)
                }
            },
        )
    }

    private fun current(mediaId: String?): Item? = owner?.takeIf { mediaId != null && it.id == mediaId }

    override fun onPlayingChanged(mediaId: String?, playing: Boolean) {
        current(mediaId)?.events?.onPlayingChanged(playing)
    }

    override fun onEnded(mediaId: String?) {
        current(mediaId)?.end()
    }

    override fun onError(mediaId: String?, message: String) {
        current(mediaId)?.fail(message)
    }

    /**
     * The item gone from the player once it held it (the service emptied it, swiped from Recents): it has ended, so
     * the dock clears rather than show a dead item playing. Before the player has held it, a change is the last
     * item's unload trailing in.
     */
    override fun onItemChanged(mediaId: String?) {
        val item = owner ?: return
        if (mediaId == item.id) item.held = true else if (item.held) item.end()
    }

    override fun onDisconnected() {
        player = null
        owner?.end()
    }

    private inner class Item(val events: AudioOutput.Events, val id: String) : AudioOutput {
        @Volatile private var released = false

        /** The player has said it holds this item ([onItemChanged]). */
        var held = false
        private var cancelPoll: (() -> Unit)? = null

        private fun later(block: (SessionPlayer) -> Unit) = loop.run {
            if (!released) withPlayer { if (!released && owner === this) block(it) }
        }

        /** Without the item ([open] with it is what the player calls): its source as the title. */
        override fun open(uri: String) = open(AudioItem(title = uri, source = uri))

        override fun open(item: AudioItem) = loop.run {
            if (released) return@run
            // Owned from the open, so a failed connection is this item's to report.
            owner = this
            withPlayer { p ->
                if (released || owner !== this) return@withPlayer
                p.load(audioMedia(item, id))
                cancelPoll?.invoke()
                cancelPoll = loop.every(POLL_MS) { poll() }
            }
        }

        override fun play() = later { it.play() }

        override fun pause() = later { it.pause() }

        override fun seekTo(ms: Long) = later { it.seekTo(ms) }

        override fun setRate(rate: Float) = later { it.setSpeed(rate) }

        override fun release() {
            if (released) return
            released = true
            loop.run {
                stopPoll()
                if (owner !== this) return@run
                owner = null
                player?.takeIf { it.mediaId == id }?.stop()
            }
        }

        private fun poll() {
            val p = player ?: return
            if (released || owner !== this || p.mediaId != id) return
            events.onProgress(p.positionMs, p.durationMs)
        }

        private fun stopPoll() {
            cancelPoll?.invoke()
            cancelPoll = null
        }

        fun end() {
            if (released) return
            stopPoll()
            events.onEnded()
        }

        fun fail(message: String) {
            if (released) return
            stopPoll()
            events.onError(message)
        }
    }

    private companion object {
        const val POLL_MS = 250L
    }
}

/** The process's one set of outputs: every window's platform shares the session (as [AndroidTts] shares its engine). */
internal object AndroidAudio {
    private val instance = AtomicReference<AudioOutputs?>(null)

    fun shared(create: () -> AudioOutputs): AudioOutputs {
        instance.get()?.let { return it }
        return synchronized(this) { instance.get() ?: create().also { instance.set(it) } }
    }

    /** The app's outputs: a controller to [AudioPlaybackService]'s session. */
    fun forApp(context: Context): AudioOutputs {
        val app = context.applicationContext
        return shared { AndroidAudioOutputs(connect = { ready, failed -> connectController(app, ready, failed) }) }
    }
}

/** Binds [AudioPlaybackService] (made by the binding) and hands its session's player over as a [SessionPlayer]. */
private fun connectController(context: Context, ready: (SessionPlayer) -> Unit, failed: (String) -> Unit) {
    val token = SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
    val holder = ControllerPlayer()
    val future = MediaController.Builder(context, token)
        .setListener(holder)
        .buildAsync()
    future.addListener(
        {
            val controller = runCatching { future.get() }.getOrElse {
                return@addListener failed(it.message ?: "Couldn't reach the audio service")
            }
            holder.attach(controller)
            ready(holder)
        },
        ContextCompat.getMainExecutor(context),
    )
}

/** [SessionPlayer] on a `MediaController`. */
private class ControllerPlayer : SessionPlayer, MediaController.Listener, Player.Listener {
    private var controller: MediaController? = null
    private var listener: SessionPlayer.Listener? = null

    fun attach(controller: MediaController) {
        this.controller = controller
        controller.addListener(this)
    }

    override val mediaId: String? get() = controller?.currentMediaItem?.mediaId
    override val positionMs: Long get() = controller?.currentPosition?.coerceAtLeast(0) ?: 0
    override val durationMs: Long
        get() = controller?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0

    override fun listen(listener: SessionPlayer.Listener) {
        this.listener = listener
    }

    override fun load(media: AudioMedia) {
        val c = controller ?: return
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setDisplayTitle(media.title)
            .setArtist(media.artist)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        c.setMediaItem(
            MediaItem.Builder().setMediaId(media.id).setUri(media.uri).setMediaMetadata(metadata).build(),
        )
        c.prepare()
    }

    override fun play() {
        controller?.play()
    }

    override fun pause() {
        controller?.pause()
    }

    override fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    override fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
    }

    override fun stop() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        listener?.onItemChanged(mediaItem?.mediaId)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        listener?.onPlayingChanged(mediaId, playWhenReady)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) listener?.onEnded(mediaId)
    }

    override fun onPlayerError(error: PlaybackException) {
        listener?.onError(mediaId, error.message ?: error.errorCodeName)
    }

    override fun onDisconnected(controller: MediaController) {
        this.controller = null
        listener?.onDisconnected()
    }
}
