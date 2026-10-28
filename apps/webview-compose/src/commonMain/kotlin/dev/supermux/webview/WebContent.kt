package dev.supermux.webview

import androidx.compose.runtime.Immutable

/**
 * What a web view shows. [key] identifies the content for snapshots and remembered page state. Two contents with
 * the same key are the same page.
 */
@Immutable
sealed interface WebContent {
    val key: String

    /** A page loaded over the network: http(s) only. Header values never appear in [key] or [toString]. */
    data class Url(val url: String, val headers: Map<String, String> = emptyMap()) : WebContent {
        init {
            val scheme = schemeOf(url)
            require(scheme == "http" || scheme == "https") { "WebContent.Url takes an http(s) URL, not scheme '$scheme'" }
        }

        override val key: String get() = "url:$url"

        override fun toString(): String = "Url(url=$url, headers=${headers.keys})"
    }

    /**
     * In-memory HTML served on [origin], for agent demos. Inline every asset (data:, blob:, <style>, <script>):
     * nothing else is served from the origin. The key covers the HTML itself, so a new revision is a new page even
     * on the same origin.
     */
    data class Html(val html: String, val origin: WebOrigin = WebOrigin.forContent(html)) : WebContent {
        private val fingerprint: String by lazy { contentFingerprint(html) }

        override val key: String get() = "html:${origin.host}:$fingerprint"

        override fun toString(): String = "Html(origin=${origin.host}, ${html.length} chars)"
    }

    /**
     * A multi-file bundle served from [resources] on [origin]; [entry] is the first page. For plugins. A bundle is
     * identified by its origin and entry, so serve a new version under a new origin or entry.
     */
    data class Bundle(
        val origin: WebOrigin,
        val resources: WebResourceProvider,
        val entry: String = "index.html",
    ) : WebContent {
        override val key: String get() = "bundle:${origin.host}/$entry"
    }
}

/** The local origin [this] is served on, or null for network content. */
internal val WebContent.localOrigin: WebOrigin?
    get() = when (this) {
        is WebContent.Url -> null
        is WebContent.Html -> origin
        is WebContent.Bundle -> origin
    }

/**
 * A local origin the library serves content from: `https://<host>.invalid`.
 *
 * Each origin is its own site, because `.invalid` is a top-level domain. Demos therefore share no cookies and no
 * renderer process. The TLD never resolves (RFC 2606), so a request an engine fails to intercept fails closed
 * instead of reaching the network.
 */
@Immutable
data class WebOrigin(val host: String) {
    init {
        require(HOST.matches(host)) { "WebOrigin host must be a lowercase DNS label: '$host'" }
    }

    /** The origin as browsers write it: scheme and host, no trailing slash. */
    val origin: String get() = "https://$host.$SUFFIX"

    /** The origin's root URL, with the trailing slash. */
    val url: String get() = "$origin/"

    /** Whether [url] is on this origin, decided the way a browser's URL parser decides it. */
    fun owns(url: String): Boolean {
        if (schemeOf(url) != "https") return false
        val authority = authorityOf(url) ?: return false
        val name = "$host.$SUFFIX"
        return authority == name || authority == "$name:443"
    }

    companion object {
        const val SUFFIX: String = "invalid"
        private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        /** The origin for [content], derived from its SHA-256: the same everywhere, and impossible to forge. */
        fun forContent(content: String): WebOrigin = WebOrigin("c" + contentFingerprint(content))
    }
}

/** Serves a [WebContent.Bundle]'s files. */
fun interface WebResourceProvider {
    /**
     * The resource at [path], or null for a 404.
     *
     * Engines normalize [path] before calling: it is relative (no leading slash), percent-decoded, and free of
     * query, fragment, `..` segments, backslashes and NUL. May be called off the main thread.
     */
    suspend fun load(path: String): WebResource?
}

class WebResource(
    val bytes: ByteArray,
    val mimeType: String,
    val headers: Map<String, String> = emptyMap(),
)
