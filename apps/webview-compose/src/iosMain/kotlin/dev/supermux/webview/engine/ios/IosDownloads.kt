package dev.supermux.webview.engine.ios

import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.DownloadTarget
import dev.supermux.webview.EngineDownload
import dev.supermux.webview.WebDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.WebKit.WKDownload
import platform.WebKit.WKDownloadDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/**
 * The process's downloads (#157): WebKit saves each with its page's session (cookies, a form's POST, a `blob:`), and
 * this delegate reports them. A download outlives its page and engine. Main thread.
 */
internal object IosDownloads {
    /** WebKit's delegate for every download: an NSObject of its own (a Kotlin object can't be one). */
    private val delegate = Delegate()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val entries = mutableListOf<Entry>()

    /**
     * One download: WebKit's (replaced on a resume), the engine to ask where it goes (until it has said), and the
     * transfer the app holds once it has.
     */
    private class Entry(var download: WKDownload?, var engine: IosWebEngine?) {
        val request: NSURLRequest? = download?.originalRequest
        val store: WKWebsiteDataStore? = download?.webView?.configuration?.websiteDataStore
        var transfer: IosDownload? = null
        var resumeData: NSData? = null
        var standIn: WKWebView? = null
        var watching: Job? = null
    }

    /** Takes a download WebKit started for [engine]'s page: it asks the engine where to save it, once it has the response. */
    fun adopt(download: WKDownload, engine: IosWebEngine?) {
        if (engine == null) {
            download.cancel(null)
            return
        }
        entries += Entry(download, engine)
        download.delegate = delegate
    }

    /** By the ObjC object, not the Kotlin reference: a delegate call may hand over another wrapper of the same download. */
    private fun entryOf(download: WKDownload): Entry? = entries.firstOrNull { it.download?.isEqual(download) == true }

    private class Delegate : NSObject(), WKDownloadDelegateProtocol {
        override fun download(
            download: WKDownload,
            decideDestinationUsingResponse: NSURLResponse,
            suggestedFilename: String,
            completionHandler: (NSURL?) -> Unit,
        ) = decide(download, decideDestinationUsingResponse, suggestedFilename, completionHandler)

        override fun downloadDidFinish(download: WKDownload) = finished(download)

        override fun download(download: WKDownload, didFailWithError: NSError, resumeData: NSData?) =
            failed(download, didFailWithError, resumeData)
    }

    private fun decide(
        download: WKDownload,
        decideDestinationUsingResponse: NSURLResponse,
        suggestedFilename: String,
        completionHandler: (NSURL?) -> Unit,
    ) {
        val entry = entryOf(download)
        if (entry == null) return completionHandler(null)
        entry.transfer?.let {
            // A restart from the start, to the same file.
            NSFileManager.defaultManager.removeItemAtPath(it.path, null)
            completionHandler(NSURL.fileURLWithPath(it.path))
            return
        }
        val engine = entry.engine
        val url = (download.originalRequest?.URL ?: decideDestinationUsingResponse.URL)?.absoluteString
        if (engine == null || url == null) {
            entries -= entry
            return completionHandler(null)
        }
        val response = decideDestinationUsingResponse
        val length = response.expectedContentLength.takeIf { it >= 0 }
        engine.askDownload(url, suggestedFilename, response.MIMEType, length) { request, target ->
            entry.engine = null
            if (target == null) {
                entries -= entry
                completionHandler(null)
                return@askDownload
            }
            val transfer = IosDownload(entry, request, target.path)
            entry.transfer = transfer
            val destination = NSURL.fileURLWithPath(target.path)
            destination.URLByDeletingLastPathComponent?.let {
                NSFileManager.defaultManager.createDirectoryAtURL(it, withIntermediateDirectories = true, attributes = null, error = null)
            }
            // WebKit fails a download whose file exists: the app has said to replace it.
            NSFileManager.defaultManager.removeItemAtPath(target.path, null)
            completionHandler(destination)
            watch(entry)
            target.onStarted(transfer)
        }
    }

    private fun finished(download: WKDownload) {
        val entry = entryOf(download) ?: return
        entry.watching?.cancel()
        entries -= entry
        val transfer = entry.transfer ?: return
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(transfer.path, null)
        val size = (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue ?: download.progress.completedUnitCount
        transfer.done(size)
    }

    private fun failed(download: WKDownload, didFailWithError: NSError, resumeData: NSData?) {
        val entry = entryOf(download) ?: return
        entry.watching?.cancel()
        entry.download = null
        val transfer = entry.transfer
        if (transfer == null) {
            entries -= entry
            return
        }
        if (transfer.state.value !is WebDownloadState.Running) return // A pause or a cancel: reported by them.
        entry.resumeData = resumeData
        transfer.failed(didFailWithError.localizedDescription)
    }

    /** NSProgress's KVO is awkward from Kotlin; its count is read a few times a second while the download runs. */
    private fun watch(entry: Entry) {
        entry.watching?.cancel()
        entry.watching = scope.launch {
            while (isActive) {
                val download = entry.download ?: break
                val transfer = entry.transfer ?: break
                val progress = download.progress
                if (transfer.state.value is WebDownloadState.Running) {
                    transfer.running(progress.completedUnitCount, progress.totalUnitCount.takeIf { it > 0 })
                }
                delay(WATCH_MILLIS)
            }
        }
    }

    private fun pause(entry: Entry) {
        val download = entry.download ?: return
        entry.watching?.cancel()
        entry.download = null
        download.cancel { data -> scope.launch { entry.resumeData = data } }
    }

    private fun resume(entry: Entry) {
        val webView = entry.standIn ?: WKWebView(
            frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
            configuration = WKWebViewConfiguration().apply { entry.store?.let { websiteDataStore = it } },
        ).also { entry.standIn = it }
        val adopt: (WKDownload?) -> Unit = { download ->
            if (download == null) {
                entry.transfer?.failed("The download could not be resumed")
            } else {
                entry.download = download
                download.delegate = delegate
                watch(entry)
            }
        }
        val data = entry.resumeData
        entry.resumeData = null
        val request = entry.request
        when {
            data != null -> webView.resumeDownloadFromResumeData(data) { download -> adopt(download) }
            request != null -> webView.startDownloadUsingRequest(request) { download -> adopt(download) }
            else -> entry.transfer?.failed("The download could not be resumed")
        }
    }

    private fun cancel(entry: Entry) {
        entry.watching?.cancel()
        entry.download?.cancel(null)
        entry.download = null
        entries -= entry
        entry.transfer?.let { NSFileManager.defaultManager.removeItemAtPath(it.path, null) }
    }

    private class IosDownload(private val entry: Entry, request: DownloadRequest, path: String) : EngineDownload(request, path) {
        // WebKit keeps resume data where the server allows a range; where it doesn't, a resume starts over.
        override val canPause: Boolean get() = true

        override fun onPause() {
            paused()
            onMain { pause(entry) }
        }

        override fun onResume() {
            running(state.value.received, state.value.total)
            onMain { resume(entry) }
        }

        override fun onCancel() {
            cancelled()
            onMain { cancel(entry) }
        }

        private fun onMain(block: () -> Unit) {
            scope.launch { block() }
        }
    }

    private const val WATCH_MILLIS = 250L
}
