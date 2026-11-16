package dev.supermux.webview.engine.mac

import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.DownloadTarget
import dev.supermux.webview.EngineDownload
import dev.supermux.webview.WebDownload

/**
 * The process's downloads (#157), by the glue's id: WebKit saves each with its page's session (WVDownloads.m), and they
 * outlive their page. Main thread, but the transfer's own calls, which only go to the glue.
 */
internal object MacDownloads {
    /** The glue's download calls; tests replace them. */
    interface Ops {
        fun answer(download: Long, path: String?)
        fun pause(download: Long)
        fun resume(download: Long)
        fun cancel(download: Long)
    }

    @Volatile var ops: Ops = object : Ops {
        override fun answer(download: Long, path: String?) = MacGlue.answerDownload(download, path)
        override fun pause(download: Long) = MacGlue.pauseDownload(download)
        override fun resume(download: Long) = MacGlue.resumeDownload(download)
        override fun cancel(download: Long) = MacGlue.cancelDownload(download)
    }

    private val live = HashMap<Long, MacDownload>()

    /** Drops [download] (null [path]), or saves it to [path]. */
    fun answer(download: Long, path: String?) = ops.answer(download, path)

    /** Saves [download] to [target]'s path, and hands the transfer to the app. */
    fun start(download: Long, request: DownloadRequest, target: DownloadTarget): WebDownload {
        val transfer = MacDownload(download, request, target.path)
        live[download] = transfer
        ops.answer(download, target.path)
        target.onStarted(transfer)
        return transfer
    }

    fun on(event: GlueEvent) {
        when (event) {
            is GlueEvent.DownloadProgress -> live[event.download]?.let {
                if (it.state.value is dev.supermux.webview.WebDownloadState.Running) it.running(event.received, event.total)
            }
            is GlueEvent.DownloadDone -> live.remove(event.download)?.done(event.size)
            is GlueEvent.DownloadStopped -> when (event.stop) {
                DownloadStop.Paused -> live[event.download]?.paused()
                DownloadStop.Failed -> live[event.download]?.failed(event.message ?: "Download failed")
                DownloadStop.Cancelled -> live.remove(event.download)?.cancelled()
            }
            else -> {}
        }
    }

    private class MacDownload(private val id: Long, request: DownloadRequest, path: String) : EngineDownload(request, path) {
        // WebKit keeps resume data where the server allows a range; where it doesn't, a resume starts over.
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
