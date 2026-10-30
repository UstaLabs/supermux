package dev.supermux.webview.engine.windows

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.desktop.DesktopApp
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the Windows engine needs before its first page (D20): Windows with visual capture, the glue, a WebView2 runtime of
 * 120 or later, and the process's environment on the app's own data folder. Started once, off the EDT, when the factory
 * is made (L29); a browser process that exits makes the next start open a new environment. Every failure is
 * [State.Unavailable] with its reason, never a crash.
 */
internal object Wv2Runtime {
    const val MIN_RUNTIME: String = "120.0.0.0"
    const val DOWNLOAD: String = "https://go.microsoft.com/fwlink/p/?LinkId=2124703"

    /** Chromium switches every process of an app must share (D8): the same options, or the environment is refused. */
    const val BROWSER_ARGUMENTS: String = "--disable-features=BackForwardCache"

    private const val OPEN_MILLIS = 30_000L
    private const val ERROR_INVALID_STATE = 0x8007139FL

    sealed interface State {
        data class Ready(val version: String, val userDataFolder: File) : State
        data class Unavailable(val reason: UnavailableReason, val detail: String) : State
    }

    /** The outside world [load] reads: the system's ([system]) or a test's. */
    class Host(
        val osName: String,
        val loadGlue: () -> String?,
        val start: () -> Int,
        val runtimeVersion: () -> String?,
        val compare: (String, String) -> Int,
        val openEnvironment: suspend (File) -> WinEvent.Environment,
        val localAppData: File?,
        val app: String,
    ) {
        companion object {
            fun system(): Host = Host(
                osName = System.getProperty("os.name").orEmpty(),
                loadGlue = { WinNativeLibrary.ensureLoaded() },
                start = { WinGlue.start() },
                runtimeVersion = { WinGlue.runtimeVersion() },
                compare = { a, b -> WinGlue.compareVersions(a, b) },
                openEnvironment = { folder ->
                    val (request, opened) = WinPages.newEnvironmentRequest()
                    WinGlue.openEnvironment(request, folder.absolutePath, BROWSER_ARGUMENTS)
                    withTimeoutOrNull(OPEN_MILLIS) { opened.await() } ?: WinEvent.Environment(request, 0x80004005L, null)
                },
                localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File),
                app = DesktopApp.id,
            )
        }
    }

    private val mutex = Mutex()

    @Volatile
    private var state: State? = null

    /** The process's start: once, and again after the browser process exited ([browserExited]). */
    suspend fun start(host: Host = Host.system()): State = mutex.withLock { state ?: load(host).also { state = it } }

    /**
     * The environment's browser process is gone: it crashed (its engines crashed with it), or its last page closed (it
     * exits then, and `createPage` fails with no environment). Either way the next engine's [start] opens it again.
     */
    fun browserExited() {
        if (state is State.Ready) state = null
    }

    /** For tests: as if this process had never started the runtime. */
    internal fun resetForTests() {
        state = null
    }

    internal suspend fun load(host: Host): State {
        if (!host.osName.startsWith("Windows")) return State.Unavailable(UnavailableReason.NotImplemented, "not Windows")
        host.loadGlue()?.let { return State.Unavailable(UnavailableReason.NativeLibraryFailed, it) }
        when (host.start()) {
            WinGlue.START_OK -> Unit
            WinGlue.START_NO_CAPTURE -> return State.Unavailable(
                UnavailableReason.NotImplemented,
                "Windows.Graphics.Capture can't capture a visual here: the engine needs Windows 10 1809 or later with graphics capture",
            )
            WinGlue.START_NO_DEVICE -> return State.Unavailable(UnavailableReason.NotImplemented, "no Direct3D 11 device, not even WARP")
            WinGlue.START_NO_UPCALL -> return State.Unavailable(UnavailableReason.NativeLibraryFailed, "the glue can't call back: ${WinNativeLoader.KEEP_RULE}")
            else -> return State.Unavailable(UnavailableReason.CreationFailed, "the glue's UI thread didn't start")
        }
        val version = host.runtimeVersion()
            ?: return State.Unavailable(UnavailableReason.NoWebView2Runtime, "no WebView2 runtime is installed: $DOWNLOAD")
        if (host.compare(version, MIN_RUNTIME) < 0) {
            return State.Unavailable(UnavailableReason.NoWebView2Runtime, "WebView2 runtime $version; the engine needs $MIN_RUNTIME or later: $DOWNLOAD")
        }
        val folder = Wv2Profiles.userDataFolder(host.localAppData, host.app)
            ?: return State.Unavailable(UnavailableReason.CreationFailed, "no %LOCALAPPDATA% for WebView2's data")
        val opened = host.openEnvironment(folder)
        if (opened.hresult != 0L) {
            val why = if (opened.hresult == ERROR_INVALID_STATE) {
                "another process of this app runs WebView2 on $folder with other options (another version of the app?)"
            } else {
                "WebView2 didn't start: HRESULT 0x${java.lang.Long.toHexString(opened.hresult and 0xffffffffL)}"
            }
            return State.Unavailable(UnavailableReason.CreationFailed, why)
        }
        return State.Ready(opened.version ?: version, folder)
    }
}
