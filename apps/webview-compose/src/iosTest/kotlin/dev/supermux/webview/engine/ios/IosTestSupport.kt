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
import platform.CoreGraphics.CGRectMake
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIEvent
import platform.UIKit.UIEventType
import platform.UIKit.UIEventTypeTouches
import platform.UIKit.UIWindow
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/*
 * Kotlin/Native iOS tests run on the main thread of a process that isn't an app: no WKWebView loads a page there (the
 * plan's fact F2), and runTest blocks the main queue (F4). So these tests pump the main run loop by hand, which also
 * runs Dispatchers.Main, and drive the engine's WebKit callbacks with the fakes below.
 */

/**
 * Frees what nothing reaches any more, now: a full collection, then the main queue, where Kotlin/Native releases the
 * Objective-C objects made on the main thread. Run after every test, so what a test leaves is freed while it is the
 * test that ran last, whatever the order: never by the luck of a later collection.
 */
@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
internal fun collectGarbage() {
    repeat(2) {
        kotlin.native.runtime.GC.collect()
        drainMainQueue()
    }
}

/**
 * What a test process can't free, kept until it ends: windows (a process that isn't an app traps freeing one), and the
 * web views of the engines a test lets go to see them collected.
 */
internal val keptForTheRun = mutableListOf<Any>()

/** Runs the main run loop until [done] or [seconds] pass; true if [done]. */
internal fun pumpUntil(seconds: Double = 5.0, done: () -> Boolean): Boolean {
    val deadline = NSDate().timeIntervalSince1970 + seconds
    while (!done()) {
        if (NSDate().timeIntervalSince1970 > deadline) return false
        CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.01, false)
    }
    return true
}

/**
 * Runs the main run loop until everything on the main queue now has run, Dispatchers.Main's work among it, but at most
 * [seconds]; true if it has. A time window alone can lapse first on a loaded machine: the run loop may serve something
 * slow before the main queue.
 */
internal fun drainMainQueue(seconds: Double = 10.0): Boolean {
    var reached = false
    dispatch_async(dispatch_get_main_queue()) { reached = true }
    return pumpUntil(seconds) { reached }
}

/** Display ticks by hand: what waits for the next tick runs when the test calls [tick]. */
internal class ManualTicks : Ticks {
    private val waiting = ArrayList<() -> Unit>()

    override fun next(action: () -> Unit) {
        waiting += action
    }

    override fun cancel() = waiting.clear()

    /** One display tick: what waited for it runs. */
    fun tick() {
        val due = waiting.toList()
        waiting.clear()
        due.forEach { it() }
    }
}

/**
 * [engine]'s surface in a window, as the runtime's view composes it: a container that took the web view, in a window of
 * its own (not on screen: a test process has none). Keep the window while the test needs the surface in it.
 */
internal fun inWindow(engine: IosWebEngine): Pair<UIWindow, EngineContainerView> {
    val window = UIWindow(frame = CGRectMake(0.0, 0.0, 320.0, 480.0))
    val container = EngineContainerView(engine)
    engine.attach(container)
    container.setFrame(CGRectMake(0.0, 0.0, 320.0, 480.0))
    window.addSubview(container)
    return window to container
}

/** Collects [engine]'s events on Dispatchers.Main; [stop] ends it. */
internal class EventLog(engine: WebEngine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val events = mutableListOf<EngineEvent>()

    init {
        scope.launch { engine.events.collect { events += it } }
        pumpUntil(0.05) { false }
        drainMainQueue()
    }

    /** Lets what is queued arrive, then returns everything so far. */
    fun settle(): List<EngineEvent> {
        pumpUntil(0.1) { false }
        drainMainQueue()
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

/**
 * A frame, as the engine reads WebKit's ([FrameInfo]): by default the main frame of a `https://c1.invalid` document.
 * Tests never subclass WebKit's own classes, which only WebKit can make (WebKitObjects.kt).
 */
internal class TestFrame(
    override val isMainFrame: Boolean = true,
    override val securityOrigin: SecurityOriginInfo = SecurityOriginInfo("https", "c1.invalid", 0),
    override val url: String? = "https://c1.invalid/",
) : FrameInfo

/**
 * A navigation action to [url], as the engine reads WebKit's ([NavigationActionInfo]): by default the main frame's.
 * [headers] are its request's (a server redirect's request carries the network's own).
 */
internal class TestAction(
    url: String,
    override val navigationType: Long,
    override val targetFrame: FrameInfo? = TestFrame(),
    override val shouldPerformDownload: Boolean = false,
    headers: Map<String, String> = emptyMap(),
) : NavigationActionInfo {
    override val request: NSURLRequest = NSMutableURLRequest(uRL = NSURL.URLWithString(url)!!).apply {
        for ((name, value) in headers) setValue(value, forHTTPHeaderField = name)
    }
}

/** A touch event, as UIKit hands one to a view's hitTest (a plain UIEvent is of no type). */
internal class TouchEvent : UIEvent() {
    override fun type(): UIEventType = UIEventTypeTouches
}

/** A URL scheme task for [url] with the request [headers], recording how it was answered. */
internal class FakeTask(url: String, headers: Map<String, String> = emptyMap()) : NSObject(), WKURLSchemeTaskProtocol {
    private val asked: NSURLRequest = NSMutableURLRequest(uRL = NSURL.URLWithString(url)!!).apply {
        for ((name, value) in headers) setValue(value, forHTTPHeaderField = name)
    }
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
