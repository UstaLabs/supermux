package dev.supermux.webview

/** What a web view shows. [key] identifies the content for snapshots and remembered page state. */
sealed interface WebContent {
    val key: String

    /** A page loaded over the network. */
    data class Url(val url: String, val headers: Map<String, String> = emptyMap()) : WebContent {
        override val key: String get() = "url:$url"
    }

    /**
     * In-memory HTML served on [origin], for agent demos. Inline every asset (data:, blob:, <style>,
     * <script>): nothing else is served from the origin.
     */
    data class Html(val html: String, val origin: WebOrigin = WebOrigin.forContent(html)) : WebContent {
        override val key: String get() = "html:${origin.host}"
    }

    /** A multi-file bundle served from [resources] on [origin]; [entry] is the first page. For plugins. */
    data class Bundle(
        val origin: WebOrigin,
        val resources: WebResourceProvider,
        val entry: String = "index.html",
    ) : WebContent {
        override val key: String get() = "bundle:${origin.host}/$entry"
    }
}

/**
 * A local origin the library serves content from: `https://<host>.webview.invalid`. The `.invalid` TLD never
 * resolves (RFC 2606), so a request an engine fails to intercept fails closed instead of reaching the network.
 */
data class WebOrigin(val host: String) {
    init {
        require(HOST.matches(host)) { "WebOrigin host must be a lowercase DNS label: '$host'" }
    }

    /** The origin as browsers write it: scheme and host, no trailing slash. */
    val origin: String get() = "https://$host.$SUFFIX"

    /** The origin's root URL, with the trailing slash. */
    val url: String get() = "$origin/"

    /** Whether [url] is on this origin (not merely a URL that starts with the same characters). */
    fun owns(url: String): Boolean {
        if (!url.startsWith(origin)) return false
        val rest = url.substring(origin.length)
        return rest.isEmpty() || rest[0] == '/' || rest[0] == '?' || rest[0] == '#'
    }

    companion object {
        const val SUFFIX: String = "webview.invalid"
        private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        /** A deterministic origin for [content]: the same content always lands on the same origin. */
        fun forContent(content: String): WebOrigin =
            WebOrigin("c" + fnv1a64(content).toULong().toString(16).padStart(16, '0'))
    }
}

/** Serves a [WebContent.Bundle]'s files. */
fun interface WebResourceProvider {
    /** The resource at [path] (relative, no leading slash), or null for a 404. May be called off the main thread. */
    suspend fun load(path: String): WebResource?
}

class WebResource(
    val bytes: ByteArray,
    val mimeType: String,
    val headers: Map<String, String> = emptyMap(),
)

/** 64-bit FNV-1a over UTF-16 code units: stable across platforms and runs. */
internal fun fnv1a64(text: String): Long {
    var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (c in text) {
        hash = hash xor c.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash
}
