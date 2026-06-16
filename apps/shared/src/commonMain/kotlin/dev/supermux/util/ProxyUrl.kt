package dev.supermux.util

import dev.supermux.net.ProxyDto

/**
 * Proxy-link calculation shared across iOS + Android. The broker builds the
 * canonical `url` (a subdomain when a wildcard base domain is configured,
 * otherwise a `/p/<slug>/` sub-path); the client should USE that, not rebuild
 * it from the domain. These helpers mirror the web `displayUrl` lib.
 */

/** Prettify a URL for display: drop the scheme and any trailing slash. */
fun displayUrl(url: String): String =
    url.replace(Regex("^https?://"), "").replace(Regex("/+$"), "")

/** The openable URL for a proxy — the broker's canonical `url`, else a `https://<domain>` fallback. */
fun proxyUrl(proxy: ProxyDto): String =
    proxy.url?.takeIf { it.isNotBlank() } ?: "https://${proxy.domain}"

/** Scheme-less display form of a proxy's URL. */
fun proxyDisplayUrl(proxy: ProxyDto): String = displayUrl(proxyUrl(proxy))
