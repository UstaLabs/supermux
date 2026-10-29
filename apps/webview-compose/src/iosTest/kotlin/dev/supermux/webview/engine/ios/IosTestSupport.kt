package dev.supermux.webview.engine.ios

import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebDialog
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNull
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.timeIntervalSince1970
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationType
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.darwin.NSObject

/*
 * Kotlin/Native iOS tests run on the main thread of a process that isn't an app: no WKWebView loads a page there (the
 * plan's fact F2), and runTest blocks the main queue (F4). So these tests pump the main run loop by hand, which also
 * runs Dispatchers.Main, and drive the engine's WebKit callbacks with the fakes below.
 */

/** Runs the main run loop until [done] or [seconds] pass; true if [done]. */
internal fun pumpUntil(seconds: Double = 5.0, done: () -> Boolean): Boolean {
    val deadline = NSDate().timeIntervalSince1970 + seconds
    while (!done()) {
        if (NSDate().timeIntervalSince1970 > deadline) return false
        CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.01, false)
    }
    return true
}

/** Collects [engine]'s events on Dispatchers.Main; [stop] ends it. */
internal class EventLog(engine: WebEngine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val events = mutableListOf<EngineEvent>()

    init {
        scope.launch { engine.events.collect { events += it } }
        pumpUntil(0.05) { false }
    }

    /** Lets what is queued arrive, then returns everything so far. */
    fun settle(): List<EngineEvent> {
        pumpUntil(0.1) { false }
        return events.toList()
    }

    fun stop() = scope.cancel()
}

/** A client that records what it is asked, answering [navigation] and [newWindow] at once and dialogs from [dialog]. */
internal class RecordingClient(
    var navigation: NavigationDecision = NavigationDecision.Block,
    var newWindow: NewWindowDecision = NewWindowDecision.Block,
    var dialog: CompletableDeferred<DialogResult>? = null,
) : EngineClient {
    val asked = mutableListOf<Any>()

    override fun onNavigation(request: NavigationRequest): NavigationDecision = navigation.also { asked += request }
    override suspend fun onDialog(dialog: WebDialog): DialogResult {
        asked += dialog
        return this.dialog?.await() ?: DialogResult.Dismissed
    }
    override suspend fun onFileChooser(request: FileChooserRequest): List<String>? = null.also { asked += request }
    override suspend fun onPermission(request: PermissionRequest): Boolean = false.also { asked += request }
    override suspend fun onDownload(request: DownloadRequest): Boolean = false.also { asked += request }
    override fun onNewWindow(request: NewWindowRequest): NewWindowDecision = newWindow.also { asked += request }
}

/** WebKit's navigation action, as WebKit would hand it over. */
internal class FakeAction(
    private val url: String,
    private val type: WKNavigationType,
    private val target: WKFrameInfo? = FakeFrame(main = true, origin = FakeOrigin("https", "c1.invalid")),
) : WKNavigationAction() {
    override fun request(): NSURLRequest = NSURLRequest.requestWithURL(NSURL.URLWithString(url)!!)
    override fun navigationType(): WKNavigationType = type
    override fun targetFrame(): WKFrameInfo? = target
    override fun shouldPerformDownload(): Boolean = false
}

internal class FakeOrigin(private val scheme: String, private val name: String, private val number: Long = 0) : WKSecurityOrigin() {
    override fun protocol(): String = scheme
    override fun host(): String = name
    override fun port(): Long = number
}

internal class FakeFrame(private val main: Boolean, private val origin: WKSecurityOrigin) : WKFrameInfo() {
    override fun isMainFrame(): Boolean = main
    override fun securityOrigin(): WKSecurityOrigin = origin
    override fun request(): NSURLRequest = NSURLRequest.requestWithURL(NSURL.URLWithString("https://c1.invalid/")!!)
}

/** A message from the relay, as WebKit delivers it to the isolated world's handler. */
internal class FakeMessage(private val text: Any?, private val frame: WKFrameInfo) : WKScriptMessage() {
    override fun body(): Any = text ?: NSNull()
    override fun frameInfo(): WKFrameInfo = frame
}

/** A URL scheme task, recording how it was answered. */
internal class FakeTask(url: String) : NSObject(), WKURLSchemeTaskProtocol {
    private val asked = NSURLRequest.requestWithURL(NSURL.URLWithString(url)!!)
    var response: NSURLResponse? = null
    var body = ByteArray(0)
    var finished = false
    var error: NSError? = null

    override fun request(): NSURLRequest = asked
    override fun didReceiveResponse(response: NSURLResponse) {
        this.response = response
    }
    override fun didReceiveData(data: NSData) {
        body += data.toByteArray()
    }
    override fun didFinish() {
        finished = true
    }
    override fun didFailWithError(error: NSError) {
        this.error = error
    }
}
