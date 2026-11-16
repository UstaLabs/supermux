package dev.supermux.webview.engine.windows

import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.DownloadTarget
import dev.supermux.webview.EngineDownload
import dev.supermux.webview.WebDownloadState

/**
 * The process's downloads (#157), by an id of the library's: WebView2 saves each with its page's session (downloads.cpp)
 * and they outlive their page. Main thread, but the transfer's own calls, which only go to the glue.
 */
internal object WinDownloads {
    /** The glue's download calls; tests replace them. */
    interface Ops {
        fun pause(download: Long)
        fun resume(download: Long)
        fun cancel(download: Long)
    }

    @Volatile var ops: Ops = object : Ops {
        override fun pause(download: Long) = WinGlue.pauseDownload(download)
        override fun resume(download: Long) = WinGlue.resumeDownload(download)
        override fun cancel(download: Long) = WinGlue.cancelDownload(download)
    }

    private var next = 0
    private val live = HashMap<Long, Pair<WinDownload, DownloadTarget>>()

    /** An id for [request], saved to [path] (the target's, as Windows writes it) once the glue has the answer: then [started]. */
    fun start(request: DownloadRequest, path: String, target: DownloadTarget): Int {
        next = if (next == Int.MAX_VALUE) 1 else next + 1
        live[next.toLong()] = WinDownload(next.toLong(), request, path) to target
        return next
    }

    /** Hands the transfer of [id] to the app. */
    fun started(id: Int) {
        live[id.toLong()]?.let { (transfer, target) -> target.onStarted(transfer) }
    }

    fun on(event: WinEvent) {
        when (event) {
            is WinEvent.DownloadProgress -> live[event.download]?.first?.let {
                if (it.state.value is WebDownloadState.Running) it.running(event.received, event.total)
            }
            is WinEvent.DownloadState -> when (event.state) {
                STATE_DONE -> live.remove(event.download)?.first?.done(event.received)
                STATE_PAUSED -> live[event.download]?.first?.paused()
                STATE_CANCELLED -> live.remove(event.download)?.first?.cancelled()
                else -> live[event.download]?.first?.failed(event.message ?: "Download failed")
            }
            else -> {}
        }
    }

    private const val STATE_DONE = 0
    private const val STATE_PAUSED = 1
    private const val STATE_CANCELLED = 3

    private class WinDownload(private val id: Long, request: DownloadRequest, path: String) : EngineDownload(request, path) {
        // WebView2 pauses and resumes itself; one the server can't resume fails, and its Retry says so.
        override val canPause: Boolean get() = true

        override fun onPause() {
            paused()
            ops.pause(id)
        }

        override fun onResume() {
            running(state.value.received, state.value.total)
            ops.resume(id)
        }

        override fun onCancel() {
            cancelled()
            ops.cancel(id)
        }
    }
}
