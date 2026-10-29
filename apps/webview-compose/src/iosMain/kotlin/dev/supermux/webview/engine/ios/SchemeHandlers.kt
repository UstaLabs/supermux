package dev.supermux.webview.engine.ios

import dev.supermux.webview.WebContent
import dev.supermux.webview.engine.webkit.WebKitUrls
import dev.supermux.webview.normalizeBundlePath
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDomain
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKWebView
import platform.darwin.NSObject

/**
 * Serves the lease's [bundle] at `webview-bundle://<host>.invalid/…` (spec §7) and nothing else: another host, or no
 * bundle (between leases), fails as an unknown host does, so no document of another bundle can exist. Paths go through
 * [normalizeBundlePath] before the provider sees them; a path it refuses is a 404. The provider runs in [scope], on the
 * main thread; a task WebKit stopped is never answered, since touching it raises.
 */
internal class BundleSchemeHandler(private val scope: CoroutineScope) : NSObject(), WKURLSchemeHandlerProtocol {
    private class Running(val task: WKURLSchemeTaskProtocol) {
        var stopped = false
        var job: Job? = null
    }

    private val running = mutableListOf<Running>()

    /** The bundle served now. */
    var bundle: WebContent.Bundle? = null

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val task = startURLSchemeTask
        val url = task.request.URL?.absoluteString
        val content = bundle
        if (url == null || content == null || !WebKitUrls.isBundleUrl(url, content.origin)) {
            task.didFailWithError(NSError.errorWithDomain(NSURLErrorDomain, NSURLErrorCannotFindHost, null))
            return
        }
        val path = normalizeBundlePath(WebKitUrls.rawPath(url))
        if (path == null) {
            respond(task, 404, "text/plain", ByteArray(0), emptyMap())
            return
        }
        val file = path.ifEmpty { content.entry }
        val request = Running(task)
        running += request
        request.job = scope.launch {
            val resource = try {
                content.resources.load(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            running -= request
            if (request.stopped) return@launch
            if (resource == null) {
                respond(task, 404, "text/plain", ByteArray(0), emptyMap())
            } else {
                respond(task, 200, resource.mimeType, resource.bytes, resource.headers)
            }
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        val request = running.firstOrNull { it.task === stopURLSchemeTask } ?: return
        request.stopped = true
        running -= request
        request.job?.cancel()
    }
}

/** Serves the floor, the blank page an engine shows between leases, at any `webview-floor:` URL. */
internal class FloorSchemeHandler : NSObject(), WKURLSchemeHandlerProtocol {
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        respond(startURLSchemeTask, 200, "text/html; charset=utf-8", FLOOR_HTML.encodeToByteArray(), emptyMap())
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) = Unit
}

/** The floor: blank, in the theme the web view is given. (A subclass of an Objective-C class can't keep it.) */
private const val FLOOR_HTML = "<!doctype html><html><head><meta name=\"color-scheme\" content=\"light dark\">" +
    "<meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>"

/**
 * Answers [task] with an HTTP response: [status], [mimeType], [bytes] and [headers] (the provider's own; it never
 * overrides the type or the length).
 */
internal fun respond(task: WKURLSchemeTaskProtocol, status: Int, mimeType: String, bytes: ByteArray, headers: Map<String, String>) {
    val url = task.request.URL ?: return
    val fields = LinkedHashMap<Any?, Any?>()
    for ((name, value) in headers) {
        if (!name.equals("Content-Type", ignoreCase = true) && !name.equals("Content-Length", ignoreCase = true)) fields[name] = value
    }
    fields["Content-Type"] = mimeType
    fields["Content-Length"] = bytes.size.toString()
    val response = NSHTTPURLResponse(uRL = url, statusCode = status.toLong(), HTTPVersion = "HTTP/1.1", headerFields = fields) ?: return
    task.didReceiveResponse(response)
    if (bytes.isNotEmpty()) task.didReceiveData(bytes.toNSData())
    task.didFinish()
}
