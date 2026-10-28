package dev.supermux.webview

import androidx.compose.runtime.Immutable

/**
 * What a web view shows. [key] identifies the content for snapshots and remembered page state. Two contents with
 * the same key are the same page.
 */
@Immutable
sealed interface WebContent {
    val key: String

    /** A page loaded over the network: an http(s) URL with a host. Header values never appear in [key] or [toString]. */
    data class Url(val url: String, val headers: Map<String, String> = emptyMap()) : WebContent {
        init {
            val scheme = schemeOf(url)
            require(scheme == "http" || scheme == "https") { "WebContent.Url takes an http(s) URL, not scheme '$scheme'" }
            require(hostOf(url) != null) { "WebContent.Url needs a host: '$url'" }
        }

        override val key: String get() = "url:$url"

        /** Scheme, host and path only: userinfo, query and fragment can carry secrets. */
        override fun toString(): String {
            val path = normalizeUrl(url).substringAfter("://", "").dropWhile { it != '/' }.substringBefore('?').substringBefore('#')
            return "Url(${schemeOf(url)}://${hostOf(url)}$path, headers=${headers.keys})"
        }

        companion object {
            /** [text] as a network URL, or null when it isn't one. For typed input, where the constructor would throw. */
            fun parse(text: String): Url? {
                val url = normalizeUrl(text)
                val scheme = schemeOf(url)
                return if ((scheme == "http" || scheme == "https") && hostOf(url) != null) Url(url) else null
            }
        }
    }

    /**
     * In-memory HTML served on [origin], for agent demos. Inline every asset (data:, blob:, <style>, <script>):
     * nothing else is served from the origin.
     * - The key covers the HTML itself, so a new revision is a new page even on the same origin.
     * - Construction hashes the HTML once, so `remember` the content rather than rebuilding it every composition.
     */
    class Html(val html: String, origin: WebOrigin? = null) : WebContent {
        private val fingerprint: String = contentFingerprint(html)

        /** Where the HTML is served. Defaults to the origin derived from its fingerprint. */
        val origin: WebOrigin = origin ?: originForFingerprint(fingerprint)

        override val key: String = "html:${this.origin.host}:$fingerprint"

        override fun equals(other: Any?): Boolean = other is Html && other.html == html && other.origin == origin

        override fun hashCode(): Int = 31 * html.hashCode() + origin.hashCode()

        override fun toString(): String = "Html(origin=${origin.host}, ${html.length} chars)"
    }

    /**
     * A multi-file bundle served from [resources] on [origin]; [entry] is the first page. For plugins.
     * - A bundle is its origin and entry: another provider instance for them is the same bundle.
     * - Serve a new version under a new origin or entry.
     */
    data class Bundle(
        val origin: WebOrigin,
        val resources: WebResourceProvider,
        val entry: String = "index.html",
    ) : WebContent {
        override val key: String get() = "bundle:${origin.host}/$entry"

        override fun equals(other: Any?): Boolean = other is Bundle && other.origin == origin && other.entry == entry

        override fun hashCode(): Int = 31 * origin.hashCode() + entry.hashCode()
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
 * - Each origin is its own site (`.invalid` is a top-level domain), so demos share no cookies.
 * - Engines with site isolation (desktop Chromium, WebView2) also give each origin its own renderer. Android
 *   WebView shares one renderer across all pages (spec §4).
 * - The TLD never resolves (RFC 2606), so a request an engine fails to intercept fails closed.
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
        fun forContent(content: String): WebOrigin = originForFingerprint(contentFingerprint(content))
    }
}

internal fun originForFingerprint(fingerprint: String): WebOrigin = WebOrigin("c$fingerprint")

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
