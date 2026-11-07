package dev.supermux.webview.engine.ios

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebResource
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
import platform.Foundation.valueForHTTPHeaderField
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKWebView
import platform.darwin.NSObject

/**
 * Serves the lease's [bundle] at `webview-bundle://<host>.invalid/…` (spec §7) and nothing else: another host, or no
 * bundle (between leases), fails as an unknown host does, so no document of another bundle can exist. Paths go through
 * [normalizeBundlePath] before the provider sees them; a path it refuses is a 404. A request for a byte range gets that
 * range ([respondWith]). The provider runs in [scope], on the main thread; a task WebKit stopped is never answered,
 * since touching it raises.
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
            if (resource == null) respond(task, 404, "text/plain", ByteArray(0), emptyMap()) else respondWith(task, resource)
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

/**
 * Answers [task] with [resource]: whole (200), or the one byte range its request asks for (206, with its
 * `Content-Range`), as media players load a file (a bundled `<video>` plays only so); a range past the end is 416. What
 * isn't one plain byte range gets the whole file, as HTTP lets a server do. Every answer says ranges may be asked for.
 */
private fun respondWith(task: WKURLSchemeTaskProtocol, resource: WebResource) {
    val bytes = resource.bytes
    val headers = resource.headers + ("Accept-Ranges" to "bytes")
    when (val range = task.request.valueForHTTPHeaderField("Range")?.let { byteRange(it, bytes.size) }) {
        null -> respond(task, 200, resource.mimeType, bytes, headers)
        ByteRange.Unsatisfiable -> respond(task, 416, resource.mimeType, ByteArray(0), headers + ("Content-Range" to "bytes */${bytes.size}"))
        is ByteRange.Slice -> respond(
            task, 206, resource.mimeType, bytes.copyOfRange(range.first, range.last + 1),
            headers + ("Content-Range" to "bytes ${range.first}-${range.last}/${bytes.size}"),
        )
    }
}

/** One byte range of a file, as a `Range` header asks for it. */
internal sealed interface ByteRange {
    /** Bytes [first] to [last], both included. */
    data class Slice(val first: Int, val last: Int) : ByteRange

    /** A range that starts past the file's end (416). */
    data object Unsatisfiable : ByteRange
}

/**
 * The range a `Range` [header] asks of a file [size] bytes long (RFC 9110 §14): `bytes=first-last`, `bytes=first-` or
 * `bytes=-suffix`, its end cut at the file's. Null for anything else (several ranges, another unit, a malformed one),
 * which gets the whole file.
 */
internal fun byteRange(header: String, size: Int): ByteRange? {
    val value = header.trim()
    if (!value.startsWith("bytes=")) return null
    val spec = value.substring("bytes=".length).trim()
    val dash = spec.indexOf('-')
    if (dash < 0 || ',' in spec) return null
    val from = spec.substring(0, dash).trim()
    val to = spec.substring(dash + 1).trim()
    if (from.isEmpty()) {
        val suffix = to.toLongOrNull() ?: return null
        if (suffix <= 0 || size == 0) return ByteRange.Unsatisfiable
        return ByteRange.Slice(maxOf(0L, size - suffix).toInt(), size - 1)
    }
    val first = from.toLongOrNull() ?: return null
    val last = if (to.isEmpty()) null else to.toLongOrNull() ?: return null
    if (first < 0 || last != null && last < first) return null
    if (first >= size) return ByteRange.Unsatisfiable
    return ByteRange.Slice(first.toInt(), minOf(last ?: (size - 1L), size - 1L).toInt())
}

/** Serves the floor, the blank page an engine shows between leases, at any `webview-floor:` URL. */
internal class FloorSchemeHandler : NSObject(), WKURLSchemeHandlerProtocol {
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        respond(startURLSchemeTask, 200, "text/html; charset=utf-8", WebKitUrls.FLOOR_HTML.encodeToByteArray(), emptyMap())
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) = Unit
}

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
