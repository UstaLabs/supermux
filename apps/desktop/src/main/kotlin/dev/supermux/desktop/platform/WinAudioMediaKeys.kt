// Windows's media keys (#118 car 3c): the keyboard's ▶❚❚, ⏭ and ⏮ reach supermux's audio player while it plays, as
// RegisterHotKey hot keys on a thread of their own, the way the overlay's hold-to-talk key does (#62's WinHotKey).
// Windows shows no Now Playing for them (that is the WinRT SystemMediaTransportControls, not cheap from the JVM).
package dev.supermux.desktop.platform

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import dev.supermux.work.MediaCommand
import dev.supermux.work.NowPlayingInfo
import dev.supermux.work.SystemNowPlaying
import java.util.concurrent.CountDownLatch

/** The media keys taken, by virtual-key code. */
internal enum class WinMediaKey(val virtualKey: Int) {
    PLAY_PAUSE(0xB3),
    NEXT(0xB0),
    PREVIOUS(0xB1),
    ;

    /** ⏭/⏮ skip, as the lock screen's ±15 s: a voice note has no next track. */
    val command: MediaCommand
        get() = when (this) {
            PLAY_PAUSE -> MediaCommand.TogglePlayPause
            NEXT -> MediaCommand.SkipBy(MediaCommand.SKIP_MS)
            PREVIOUS -> MediaCommand.SkipBy(-MediaCommand.SKIP_MS)
        }
}

/** The hot keys, behind a seam: Linux CI has no user32. */
internal interface WinKeys {
    /** Take [keys] from any app; each press to [onKey], on the keys' own thread. */
    fun register(keys: List<WinMediaKey>, onKey: (WinMediaKey) -> Unit)

    fun unregister()
}

/**
 * The Windows app's [SystemNowPlaying]: the keys are taken on the first show and let go on [clear], so they go back to
 * whatever else plays when supermux is idle. A sound that cannot seek takes ▶❚❚ only.
 */
internal class WinAudioMediaKeys(native: () -> WinKeys = { JnaWinKeys() }) : SystemNowPlaying {
    private val keys by lazy(native)
    private var taken: List<WinMediaKey>? = null

    @Volatile private var commands: ((MediaCommand) -> Boolean)? = null

    @Synchronized
    override fun show(info: NowPlayingInfo, commands: (MediaCommand) -> Boolean) {
        this.commands = commands
        val wanted = if (info.seekable) WinMediaKey.entries else listOf(WinMediaKey.PLAY_PAUSE)
        if (wanted == taken) return
        runCatching {
            if (taken != null) keys.unregister()
            keys.register(wanted) { key -> this.commands?.invoke(key.command) }
            taken = wanted
        }.onFailure { System.err.println("[audio] media keys: ${it.message}") }
    }

    @Synchronized
    override fun clear() {
        commands = null
        if (taken != null) runCatching { keys.unregister() }
        taken = null
    }
}

/** [WinKeys] on user32's RegisterHotKey, its messages read on a daemon thread (a hot key is its thread's). */
internal class JnaWinKeys : WinKeys {
    private val user32 = NativeLibrary.getInstance("user32")
    private val kernel32 = NativeLibrary.getInstance("kernel32")
    private var thread: Thread? = null
    @Volatile private var threadId = 0

    override fun register(keys: List<WinMediaKey>, onKey: (WinMediaKey) -> Unit) {
        unregister()
        val ready = CountDownLatch(1)
        thread = Thread({ loop(keys, onKey, ready) }, "audio-media-keys").apply {
            isDaemon = true
            start()
        }
        ready.await()
    }

    override fun unregister() {
        val id = threadId
        if (id != 0) user32.getFunction("PostThreadMessageW").invokeInt(arrayOf<Any>(id, WM_QUIT, 0L, 0L))
        thread?.join(1_000)
        thread = null
        threadId = 0
    }

    private fun loop(keys: List<WinMediaKey>, onKey: (WinMediaKey) -> Unit, ready: CountDownLatch) {
        threadId = kernel32.getFunction("GetCurrentThreadId").invokeInt(emptyArray())
        val registered = keys.filter { key ->
            val ok = user32.getFunction("RegisterHotKey").invokeInt(arrayOf(null, idOf(key), 0, key.virtualKey)) != 0
            if (!ok) System.err.println("[audio] the media key $key is another app's")
            ok
        }
        ready.countDown()
        // MSG on 64-bit Windows: hwnd(8) message(4)+pad(4) wParam(8) lParam(8) time(4) pt(8) lPrivate(4) = 48 bytes.
        val msg = Memory(48)
        try {
            while (user32.getFunction("GetMessageW").invokeInt(arrayOf(msg, null, 0, 0)) > 0) {
                if (msg.getInt(8) != WM_HOTKEY) continue
                val id = msg.getLong(16).toInt()
                registered.firstOrNull { idOf(it) == id }?.let { runCatching { onKey(it) } }
            }
        } finally {
            for (key in registered) user32.getFunction("UnregisterHotKey").invokeInt(arrayOf(null, idOf(key)))
        }
    }

    private fun idOf(key: WinMediaKey) = HOT_KEY_BASE + key.ordinal

    private companion object {
        const val HOT_KEY_BASE = 0x5360 // clear of the overlay's 0x5355
        const val WM_HOTKEY = 0x0312
        const val WM_QUIT = 0x0012
    }
}

/** The process's [desktopNowPlaying]: the command center and the hot keys are the process's. */
internal object DesktopNowPlaying {
    val shared: SystemNowPlaying? by lazy { desktopNowPlaying() }
}

/** The desktop's Now Playing: the Mac's, Windows's media keys; none on Linux (MPRIS over D-Bus: not cheap yet). */
internal fun desktopNowPlaying(os: DesktopOs = DesktopOs.current): SystemNowPlaying? = when (os) {
    DesktopOs.MACOS -> MacAudioNowPlaying()
    DesktopOs.WINDOWS -> WinAudioMediaKeys()
    DesktopOs.LINUX -> null
}
