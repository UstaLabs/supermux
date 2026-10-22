package dev.supermux.web.seams

import dev.supermux.chat.mimeForFileName
import dev.supermux.media.MediaCache
import dev.supermux.media.MediaKey
import dev.supermux.media.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.toInt8Array
import org.khronos.webgl.toUByteArray
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * [MediaStore] on the browser's Cache Storage: one cache, [cacheName]; an entry's key is
 * `/__supermux-media/v1/<host>/<fileId>` and its response carries `x-supermux-size`. Entries are not
 * files ([hasFiles] is false — the web has no inline player to hand one to). Where `caches` does not
 * exist (an insecure context: a plain-http LAN address) every call is a no-op and [persistent] is
 * false, so the cache runs on memory alone.
 */
class WebMediaStore(private val cacheName: String = "supermux-media-v1") : MediaStore {
    override val hasFiles: Boolean get() = false
    override val persistent: Boolean = cachesAvailable()

    private fun url(key: MediaKey) = "$PREFIX${key.host}/${key.fileId}"

    @OptIn(ExperimentalUnsignedTypes::class)
    override suspend fun read(key: MediaKey): ByteArray? {
        if (!persistent) return null
        return suspendCoroutine<Uint8Array?> { c -> matchJs(cacheName, url(key)) { c.resume(it) } }
            ?.toUByteArray()?.asByteArray()
    }

    override suspend fun exists(key: MediaKey): Boolean =
        persistent && suspendCoroutine { c -> existsJs(cacheName, url(key)) { c.resume(it) } }

    override suspend fun uri(key: MediaKey): String? = null

    override suspend fun write(key: MediaKey, bytes: ByteArray): Boolean =
        persistent && suspendCoroutine { c -> putJs(cacheName, url(key), bytes.toInt8Array(), mimeOf(key)) { c.resume(it) } }

    override suspend fun writeStream(
        key: MediaKey,
        fill: suspend (append: suspend (ByteArray, Int) -> Unit) -> Boolean,
    ): Boolean {
        if (!persistent) return false
        val chunks = mutableListOf<ByteArray>()
        if (!fill { buf, n -> chunks += buf.copyOf(n) }) return false
        val all = ByteArray(chunks.sumOf { it.size })
        var at = 0
        for (c in chunks) {
            c.copyInto(all, at)
            at += c.size
        }
        return write(key, all)
    }

    override suspend fun delete(key: MediaKey) {
        if (persistent) suspendCoroutine { c -> deleteJs(cacheName, url(key)) { c.resume(Unit) } }
    }

    override suspend fun removeHost(host: String) {
        if (persistent) suspendCoroutine { c -> deletePrefixJs(cacheName, "$PREFIX$host/") { c.resume(Unit) } }
    }

    override suspend fun clear() {
        if (persistent) suspendCoroutine { c -> dropCacheJs(cacheName) { c.resume(Unit) } }
    }

    override suspend fun sizeBytes(): Long =
        if (!persistent) 0L else suspendCoroutine { c -> sizeJs(cacheName) { c.resume(it.toLong()) } }

    private fun mimeOf(key: MediaKey) = mimeForFileName("x.${key.ext}") ?: "application/octet-stream"

    private companion object {
        const val PREFIX = "/__supermux-media/v1/"
    }
}

/** The page's one attachment cache, shared by `HostStoreDeps` and Settings ▸ Storage. */
object WebMedia {
    val cache: MediaCache by lazy { MediaCache(WebMediaStore(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
}

private fun cachesAvailable(): Boolean = js("typeof caches !== 'undefined'")

@Suppress("UNUSED_PARAMETER")
private fun matchJs(name: String, url: String, onDone: (Uint8Array?) -> Unit): Unit = js(
    """{
      caches.open(name)
        .then(function (c) { return c.match(url); })
        .then(function (r) { return r ? r.arrayBuffer() : null; })
        .then(function (b) { onDone(b ? new Uint8Array(b) : null); }, function () { onDone(null); });
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun existsJs(name: String, url: String, onDone: (Boolean) -> Unit): Unit = js(
    """{
      caches.open(name)
        .then(function (c) { return c.match(url); })
        .then(function (r) { onDone(!!r); }, function () { onDone(false); });
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun putJs(name: String, url: String, data: Int8Array, mime: String, onDone: (Boolean) -> Unit): Unit = js(
    """{
      try {
        var body = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
        var resp = new Response(body, { headers: { 'content-type': mime, 'x-supermux-size': String(data.byteLength) } });
        caches.open(name)
          .then(function (c) { return c.put(url, resp); })
          .then(function () { onDone(true); }, function () { onDone(false); });
      } catch (e) { onDone(false); }
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun deleteJs(name: String, url: String, onDone: () -> Unit): Unit = js(
    """{
      caches.open(name)
        .then(function (c) { return c.delete(url); })
        .then(function () { onDone(); }, function () { onDone(); });
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun deletePrefixJs(name: String, prefix: String, onDone: () -> Unit): Unit = js(
    """{
      caches.open(name)
        .then(function (c) {
          return c.keys().then(function (reqs) {
            return Promise.all(reqs
              .filter(function (r) { return new URL(r.url).pathname.indexOf(prefix) === 0; })
              .map(function (r) { return c.delete(r); }));
          });
        })
        .then(function () { onDone(); }, function () { onDone(); });
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun dropCacheJs(name: String, onDone: () -> Unit): Unit = js(
    """{ caches.delete(name).then(function () { onDone(); }, function () { onDone(); }); }""",
)

@Suppress("UNUSED_PARAMETER")
private fun sizeJs(name: String, onDone: (Double) -> Unit): Unit = js(
    """{
      caches.open(name)
        .then(function (c) {
          return c.keys().then(function (reqs) {
            return Promise.all(reqs.map(function (r) {
              return c.match(r).then(function (resp) { return resp ? Number(resp.headers.get('x-supermux-size') || 0) : 0; });
            }));
          });
        })
        .then(function (sizes) { onDone(sizes.reduce(function (a, b) { return a + b; }, 0)); }, function () { onDone(0); });
    }""",
)
