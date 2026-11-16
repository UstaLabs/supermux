package dev.supermux.webview.engine.android

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import dev.supermux.webview.DownloadFailure
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.EngineDownload
import dev.supermux.webview.WebDownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Android WebView hands a download over and saves nothing (`DownloadListener`), so the library fetches it, as the page
 * would: with the profile's cookies, the page's user agent and the page as referrer. HTTP(S) resumes with a `Range`
 * where the server allows one; `data:` is decoded; `blob:` is read from the page that made it, while it is there.
 *
 * Transfers run on the process's own scope, not the engine's: a closed tab leaves them running.
 */
internal object AndroidDownloads {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What [HttpDownload] sends with every attempt: read again on a resume, so a refreshed login is used. */
    class Session(val cookies: CookieManager, val userAgent: String?, val referrer: String?)
}

/** An `http:` or `https:` download. A partial file waits at `<path>.part` until the whole file is there. */
internal class HttpDownload(
    request: DownloadRequest,
    path: String,
    private val session: AndroidDownloads.Session,
) : EngineDownload(request, path) {
    @Volatile private var ranges = false
    @Volatile private var job: Job? = null
    @Volatile private var connection: HttpURLConnection? = null

    /** The response's `ETag` (strong only) or `Last-Modified`, so a resumed range comes from the same file. */
    @Volatile private var validator: String? = null

    private val part = File("$path.part")

    override val canPause: Boolean get() = ranges

    fun start() {
        job = AndroidDownloads.scope.launch { transfer() }
    }

    override fun onPause() {
        paused()
        stop()
    }

    override fun onResume() {
        running(state.value.received, state.value.total)
        start()
    }

    override fun onCancel() {
        cancelled()
        val stopping = stop()
        if (stopping == null) part.delete() else stopping.invokeOnCompletion { part.delete() }
    }

    /** Ends the transfer now: a blocked read only ends when its socket closes. */
    private fun stop(): Job? {
        connection?.disconnect()
        return job?.also { it.cancel() }
    }

    private suspend fun transfer() {
        try {
            part.parentFile?.mkdirs()
            var from = if (part.exists()) part.length() else 0L
            val conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                session.cookies.getCookie(request.url)?.let { setRequestProperty("Cookie", it) }
                session.userAgent?.let { setRequestProperty("User-Agent", it) }
                session.referrer?.let { setRequestProperty("Referer", it) }
                setRequestProperty("Accept-Encoding", "identity")
                if (from > 0) {
                    setRequestProperty("Range", "bytes=$from-")
                    validator?.let { setRequestProperty("If-Range", it) }
                }
            }
            connection = conn
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                failed("HTTP $code")
                return
            }
            if (code != HttpURLConnection.HTTP_PARTIAL) from = 0
            val length = conn.contentLengthLong.takeIf { it >= 0 }
            val total = length?.plus(from) ?: request.contentLength
            ranges = code == HttpURLConnection.HTTP_PARTIAL || conn.getHeaderField("Accept-Ranges")?.trim() == "bytes"
            validator = conn.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                ?: conn.getHeaderField("Last-Modified")
            running(from, total)
            var received = from
            var reported = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, from > 0).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        val now = System.nanoTime()
                        if (now - reported > REPORT_NANOS) {
                            reported = now
                            if (state.value is WebDownloadState.Running) running(received, total)
                        }
                    }
                }
            }
            if (total != null && received < total) {
                failed("The connection closed early")
                return
            }
            finish(part, File(path))
            done(received)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // A pause or cancel closes the socket under the read: that's not a failure.
            if (state.value is WebDownloadState.Running) failed(e.message ?: "Download failed")
        } finally {
            connection = null
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val TIMEOUT_MILLIS = 30_000
        const val REPORT_NANOS = 100_000_000L
    }
}

/**
 * A download whose bytes are in the request itself (`data:`) or in the page ([read], a `blob:`): there is nothing to
 * resume from, so it can't pause, and a retry starts over.
 */
internal class BytesDownload(
    request: DownloadRequest,
    path: String,
    private val read: suspend (progress: (Long, Long?) -> Unit) -> ByteArray?,
) : EngineDownload(request, path) {
    @Volatile private var job: Job? = null

    override val canPause: Boolean get() = false

    fun start() {
        job = AndroidDownloads.scope.launch {
            try {
                val bytes = read { received, total -> if (state.value is WebDownloadState.Running) running(received, total) }
                if (bytes == null) {
                    failed("The page that made this file is gone")
                    return@launch
                }
                val part = File("$path.part")
                withContext(Dispatchers.IO) {
                    part.parentFile?.mkdirs()
                    part.writeBytes(bytes)
                    finish(part, File(path))
                }
                done(bytes.size.toLong())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a download failed", e)
                failed(e.message ?: "Download failed")
            }
        }
    }

    // Nothing to keep: a pause stops it, and a resume reads it again from the start.
    override fun onPause() {
        paused()
        job?.cancel()
    }

    override fun onResume() {
        running(0, state.value.total)
        start()
    }

    override fun onCancel() {
        cancelled()
        job?.cancel()
    }
}

/** Moves a whole [part] to [target], replacing a file already there. */
private fun finish(part: File, target: File) {
    target.delete()
    if (!part.renameTo(target)) {
        part.copyTo(target, overwrite = true)
        part.delete()
    }
}

/** The bytes of a `data:` URL, or null when it is malformed. */
internal fun dataUrlBytes(url: String): ByteArray? {
    val comma = url.indexOf(',')
    if (!url.startsWith("data:", ignoreCase = true) || comma < 0) return null
    val meta = url.substring(5, comma)
    val body = url.substring(comma + 1)
    return try {
        if (meta.endsWith(";base64", ignoreCase = true)) {
            Base64.decode(URLDecoder.decode(body, "UTF-8"), Base64.DEFAULT)
        } else {
            URLDecoder.decode(body.replace("+", "%2B"), "UTF-8").toByteArray(Charsets.UTF_8)
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}

/**
 * Reads a `blob:` URL from the page that made it, a slice at a time ([BLOB_SLICE] bytes, base64 in a JS string), so a
 * big file never goes over the bridge as one string. [evaluate] runs a script in the page and gives its JSON result,
 * or null once the page is gone.
 */
internal suspend fun readBlob(
    url: String,
    evaluate: suspend (String) -> String?,
    progress: (Long, Long?) -> Unit,
): ByteArray? {
    val key = "__wvDownload" + System.nanoTime()
    evaluate(
        "(function(){window.$key=null;fetch(${jsString(url)}).then(function(r){return r.blob()})" +
            ".then(function(b){window.$key=b},function(){window.$key=false});return 0})()",
    ) ?: return null
    // fetch() resolves later: ask until the blob is there (its size), or failed (-2).
    var length: Long? = null
    for (attempt in 0 until BLOB_WAITS) {
        val answer = evaluate("(function(){var b=window.$key;return b===null?-1:(b===false?-2:b.size)})()") ?: return null
        val value = answer.toLongOrNull() ?: return null
        if (value == -2L) return null
        if (value >= 0) {
            length = value
            break
        }
        delay(BLOB_WAIT_MILLIS)
    }
    val total = length ?: return null
    progress(0, total)
    val out = java.io.ByteArrayOutputStream(total.toInt().coerceAtLeast(0))
    var offset = 0L
    try {
        while (offset < total) {
            val end = minOf(offset + BLOB_SLICE, total)
            evaluate(
                """(function(){var b=window.$key;var r=new FileReader();window.${key}_s=null;""" +
                    """r.onload=function(){window.${key}_s=r.result.substring(r.result.indexOf(',')+1)};""" +
                    """r.readAsDataURL(b.slice($offset,$end));return 0})()""",
            ) ?: return null
            var chunk: String? = null
            for (attempt in 0 until BLOB_WAITS) {
                val answer = evaluate("window.${key}_s") ?: return null
                if (answer != "null") {
                    chunk = answer.trim('"')
                    break
                }
                delay(BLOB_WAIT_MILLIS)
            }
            out.write(Base64.decode(chunk ?: return null, Base64.DEFAULT))
            offset = end
            progress(offset, total)
        }
    } finally {
        evaluate("delete window.$key;delete window.${key}_s;0")
    }
    return out.toByteArray()
}

internal fun jsString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c.code < 0x20 || c == ' ' || c == ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

private const val BLOB_SLICE = 384 * 1024L
private const val BLOB_WAITS = 600
private const val BLOB_WAIT_MILLIS = 50L

/**
 * The file name a `Content-Disposition` header gives (RFC 6266): `filename*=UTF-8''…` before `filename="…"`, or null.
 * Only the name: a path in it is the server's mistake, and is dropped.
 */
internal fun dispositionFileName(disposition: String?): String? {
    if (disposition.isNullOrBlank()) return null
    val parts = disposition.split(';').map { it.trim() }
    val extended = parts.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }?.substringAfter('=')?.let { value ->
        val encoded = value.substringAfter("''", value).trim('"')
        runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull()
    }
    val plain = parts.firstOrNull { it.startsWith("filename=", ignoreCase = true) }?.substringAfter('=')?.trim()?.trim('"')
    // platform-ok: a header's file name, cut to its last part whichever separator a server used
    return (extended ?: plain)?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * What WebView's DownloadListener doesn't say, learned in the page (#157), at document start, where JavaScript runs:
 * - the `download` name of the link a `blob:` or `data:` download came from (a page's own name for the file it made),
 *   from the user's click on it or the page's `click()` of a link it never added to the document;
 * - the last form the page sent with POST: its answer, if it is a download, can't be fetched again without sending
 *   the form a second time.
 * `window.__wvDl.hint(url)` answers both for [url], as JSON.
 */
internal const val DOWNLOAD_HINTS_SCRIPT: String = """(function(){if(window.__wvDl)return;var names={},post=null;
function note(a){try{if(a&&a.href&&a.hasAttribute('download'))names[a.href]=a.getAttribute('download')||''}catch(e){}}
var click=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){note(this);return click.apply(this,arguments)};
document.addEventListener('click',function(e){var t=e.target;while(t&&t.tagName!=='A')t=t.parentElement;note(t)},true);
function sent(f){try{if(f&&String(f.method).toLowerCase()==='post')post={action:f.action,at:Date.now()}}catch(e){}}
document.addEventListener('submit',function(e){sent(e.target)},true);
var submit=HTMLFormElement.prototype.submit;HTMLFormElement.prototype.submit=function(){sent(this);return submit.apply(this,arguments)};
Object.defineProperty(window,'__wvDl',{value:{hint:function(u){return {name:names[u]||null,post:post&&post.action===u&&Date.now()-post.at<60000}}}});})();"""

/** The page's [DOWNLOAD_HINTS_SCRIPT] answer for a download: the link's name, and whether a form's POST sent it. */
internal class DownloadHint(val name: String?, val post: Boolean) {
    companion object {
        val None = DownloadHint(null, post = false)

        /** From the script's JSON answer (`evaluateJavascript`'s result), or [None] for anything else. */
        fun parse(json: String?): DownloadHint = try {
            val o = org.json.JSONObject(json ?: return None)
            DownloadHint(o.optString("name").takeIf { !o.isNull("name") && it.isNotBlank() }, o.optBoolean("post"))
        } catch (e: org.json.JSONException) {
            None
        }
    }
}

/** A download this engine can't save, listed failed at once with its [reason], rather than as a broken file. */
internal class RefusedDownload(request: DownloadRequest, path: String, message: String, reason: DownloadFailure) :
    EngineDownload(request, path) {
    init {
        failed(message, reason)
    }

    override val canPause: Boolean get() = false
    override fun onPause() {}
    override fun onResume() {}
    override fun onCancel() = cancelled()
}
