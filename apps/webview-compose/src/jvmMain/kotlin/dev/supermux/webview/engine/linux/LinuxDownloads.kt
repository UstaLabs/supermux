package dev.supermux.webview.engine.linux

import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.DownloadTarget
import dev.supermux.webview.EngineDownload
import dev.supermux.webview.WebDownloadState

/**
 * The process's downloads (#157), by the glue's id: WebKit saves each with its page's network session
 * (wv_downloads.c), and they outlive their view. Main thread, but the transfer's own calls, which only go to the glue.
 */
internal object LinuxDownloads {
    /** The glue's download call; tests replace it. */
    @Volatile var act: (download: Long, action: Int, path: String?) -> Unit = WpeNative::download

    private val live = HashMap<Long, LinuxDownload>()

    /** Drops [download] (null [path]), or saves it to [path]. */
    fun answer(download: Long, path: String?) = act(download, WpeNative.DOWNLOAD_ANSWER, path)

    /** Saves [download] to [target]'s path, and hands the transfer to the app. */
    fun start(download: Long, request: DownloadRequest, target: DownloadTarget) {
        val transfer = LinuxDownload(download, request, target.path)
        live[download] = transfer
        answer(download, target.path)
        target.onStarted(transfer)
    }

    fun on(event: WpeEvent) {
        when (event) {
            is WpeEvent.DownloadProgress -> live[event.download]?.let {
                if (it.state.value is WebDownloadState.Running) it.running(event.received, event.total)
            }
            is WpeEvent.DownloadState -> when (event.state) {
                WpeNative.DOWNLOAD_DONE -> live.remove(event.download)?.done(event.received)
                WpeNative.DOWNLOAD_PAUSED -> live[event.download]?.paused()
                WpeNative.DOWNLOAD_CANCELLED -> live.remove(event.download)?.cancelled()
                else -> live[event.download]?.failed(event.message ?: "Download failed")
            }
            else -> {}
        }
    }

    private class LinuxDownload(private val id: Long, request: DownloadRequest, path: String) : EngineDownload(request, path) {
        // WebKitDownload keeps nothing across a cancel: a pause stops it, and a resume fetches it again.
        override val canPause: Boolean get() = false

        override fun onPause() {
            paused()
            act(id, WpeNative.DOWNLOAD_PAUSE, null)
        }

        override fun onResume() {
            running(0, state.value.total)
            act(id, WpeNative.DOWNLOAD_RESUME, null)
        }

        override fun onCancel() {
            cancelled()
            act(id, WpeNative.DOWNLOAD_CANCEL, null)
        }
    }
}
