package dev.supermux.webview.engine.ios

import platform.Foundation.NSURLRequest
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKSecurityOrigin

/*
 * What the engine reads of the objects WebKit hands its delegates: frames, navigation actions and security origins. The
 * delegates wrap WebKit's own; tests make their own. WebKit's API objects can't be made outside WebKit: their init is
 * unavailable, and one made anyway lacks the C++ object WebKit keeps inside it, which its dealloc destroys all the same
 * (a test's stand-in crashed in -[WKSecurityOrigin dealloc] whenever the garbage collector freed it).
 */

/** The origin of a frame's document, as WebKit reports it: an empty [protocol] or [host] is opaque; [port] 0, the default. */
internal data class SecurityOriginInfo(val protocol: String, val host: String, val port: Int)

/** A frame (`WKFrameInfo`). */
internal interface FrameInfo {
    val isMainFrame: Boolean
    val securityOrigin: SecurityOriginInfo

    /** The URL of the frame's request, or null. */
    val url: String?
}

/** A navigation action (`WKNavigationAction`). */
internal interface NavigationActionInfo {
    val request: NSURLRequest

    /** `WKNavigationType`'s raw value. */
    val navigationType: Long

    /** The frame it navigates; null for a new window. */
    val targetFrame: FrameInfo?
    val shouldPerformDownload: Boolean
}

internal fun WKSecurityOrigin.info(): SecurityOriginInfo = SecurityOriginInfo(protocol, host, port.toInt())

/** WebKit's frame, read when the engine asks. */
internal class WebKitFrame(private val frame: WKFrameInfo) : FrameInfo {
    override val isMainFrame: Boolean get() = frame.mainFrame
    override val securityOrigin: SecurityOriginInfo get() = frame.securityOrigin.info()
    override val url: String? get() = frame.request.URL?.absoluteString
}

/** WebKit's navigation action, read when the engine asks. */
internal class WebKitAction(private val action: WKNavigationAction) : NavigationActionInfo {
    override val request: NSURLRequest get() = action.request
    override val navigationType: Long get() = action.navigationType
    override val targetFrame: FrameInfo? get() = action.targetFrame?.let(::WebKitFrame)
    override val shouldPerformDownload: Boolean get() = action.shouldPerformDownload
}
