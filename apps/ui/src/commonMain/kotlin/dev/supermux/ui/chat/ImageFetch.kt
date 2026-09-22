package dev.supermux.ui.chat

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The inline-markdown-image fetch policy, ported from desktop's `HttpURLConnection` loader to
 * multiplatform Ktor (`:ui` commonMain may not name `java.net`).
 *
 * The rules are unchanged and are the load-bearing part: https only, a hard byte cap, a bounded
 * redirect budget, and every hop re-checked so a redirect can never downgrade the scheme.
 */

/**
 * Max download size for inline markdown images (bytes). Keeps a malicious/huge asset from
 * ballooning RAM; anything over this falls back to the tappable link line.
 */
const val MD_IMAGE_MAX_BYTES: Long = 8L * 1024L * 1024L

/** Default redirect hop budget — loops must not hang the loader. */
const val MD_IMAGE_MAX_REDIRECTS: Int = 5

/** Only `https://` image URLs are fetched (message-content images are a tracking / IP-leak vector). */
fun isHttpsImageUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

/**
 * Resolve a redirect `Location` against [currentUrl]. Absolute http(s) locations are used as-is;
 * protocol-relative, absolute-path and relative locations resolve against the current URL. Pure —
 * unit-testable without the network.
 */
fun resolveImageRedirectUrl(currentUrl: String, location: String): String {
    val next = location.trim()
    if (next.startsWith("https://", ignoreCase = true) || next.startsWith("http://", ignoreCase = true)) {
        return next
    }
    val schemeEnd = currentUrl.indexOf("://")
    if (schemeEnd < 0) return next
    if (next.startsWith("//")) return currentUrl.substring(0, schemeEnd + 1) + next
    val pathStart = currentUrl.indexOf('/', schemeEnd + 3)
    val origin = if (pathStart < 0) currentUrl else currentUrl.substring(0, pathStart)
    if (next.startsWith("/")) return origin + next
    val path = if (pathStart < 0) "/" else currentUrl.substring(pathStart).substringBefore('?').substringBefore('#')
    val dir = path.substringBeforeLast('/', "") + "/"
    return origin + dir + next
}

/** A pull source of bytes: fill [buf], return how many, or -1 at end of stream. */
fun interface ChunkReader {
    suspend fun read(buf: ByteArray): Int
}

/**
 * Read up to [maxBytes] from [source]. Returns null if the stream would exceed the cap (size-capped
 * so a huge body never lands fully in memory). Pure relative to the source — unit-testable without
 * the network.
 */
suspend fun readBytesCapped(source: ChunkReader, maxBytes: Long = MD_IMAGE_MAX_BYTES): ByteArray? {
    val chunks = ArrayList<ByteArray>()
    val buf = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = source.read(buf)
        if (n < 0) break
        if (n == 0) continue
        total += n
        if (total > maxBytes) return null
        chunks.add(buf.copyOf(n))
    }
    val out = ByteArray(total.toInt())
    var at = 0
    for (c in chunks) {
        c.copyInto(out, at)
        at += c.size
    }
    return out
}

/**
 * The client the production loader uses: redirects are followed by US (so every hop can be
 * re-checked against [isHttpsImageUrl]), and a non-2xx is a value, not an exception.
 */
internal val imageHttpClient: HttpClient by lazy {
    HttpClient {
        followRedirects = false
        expectSuccess = false
        // Desktop's old HttpURLConnection values. Without them a server that accepts the socket and
        // then goes quiet leaves the spinner up forever.
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
    }
}

/**
 * [fetchImageBytesWithPolicy] on a worker thread.
 *
 * The composable calls this, never the raw fetch: a `LaunchedEffect` body runs on the MAIN
 * dispatcher, and a socket read there stutters the whole frame loop (and, on desktop, parks the
 * EDT). [fetch] is the seam the dispatcher test drives.
 */
suspend fun loadMarkdownImageBytes(
    url: String,
    fetch: suspend (String) -> ByteArray? = { fetchImageBytesWithPolicy(it) },
): ByteArray? = withContext(Dispatchers.Default) { fetch(url) }

/**
 * GET [url] and return the body when every hop is allowed by [isAllowedUrl], the status is 2xx, and
 * the body is within [maxBytes]. Follows up to [maxRedirects] hops. Null on any failure — callers
 * fall back to the link line.
 *
 * [isAllowedUrl] / [client] are the seams the network-matrix tests drive (a local server, redirect,
 * downgrade, hop budget, oversize, 404).
 */
suspend fun fetchImageBytesWithPolicy(
    url: String,
    maxBytes: Long = MD_IMAGE_MAX_BYTES,
    maxRedirects: Int = MD_IMAGE_MAX_REDIRECTS,
    isAllowedUrl: (String) -> Boolean = ::isHttpsImageUrl,
    client: HttpClient = imageHttpClient,
): ByteArray? {
    if (!isAllowedUrl(url)) return null
    return runCatching {
        var current = url
        repeat(maxRedirects) {
            if (!isAllowedUrl(current)) return null
            val outcome = client.prepareGet(current) {
                header(HttpHeaders.Accept, "image/*,*/*;q=0.8")
            }.execute { resp ->
                val code = resp.status.value
                when {
                    code in 200..299 -> {
                        val declared = resp.contentLength() ?: -1L
                        if (declared > maxBytes) {
                            Hop.Stop
                        } else {
                            val channel = resp.bodyAsChannel()
                            val bytes = readBytesCapped({ buf -> channel.readAvailable(buf, 0, buf.size) }, maxBytes)
                            if (bytes == null) Hop.Stop else Hop.Body(bytes)
                        }
                    }
                    code in 300..399 -> {
                        val next = resp.headers[HttpHeaders.Location]
                        if (next.isNullOrBlank()) Hop.Stop else Hop.Redirect(resolveImageRedirectUrl(current, next))
                    }
                    else -> Hop.Stop
                }
            }
            when (outcome) {
                is Hop.Body -> return outcome.bytes
                is Hop.Stop -> return null
                is Hop.Redirect -> {
                    current = outcome.url
                    // Reject immediately on scheme downgrade / disallowed hop so we never open it.
                    if (!isAllowedUrl(current)) return null
                }
            }
        }
        null
    }.getOrNull()
}

private sealed interface Hop {
    data class Body(val bytes: ByteArray) : Hop
    data class Redirect(val url: String) : Hop
    data object Stop : Hop
}
