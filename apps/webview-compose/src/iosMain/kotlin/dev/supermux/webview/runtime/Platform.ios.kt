package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult

internal actual fun platformEngineFactory(): WebEngineFactory =
    WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) }

/**
 * Phones: 4 live engines and 2 spares, not 3, since each idle WKWebView may keep its own WebContent process; 32 MB of
 * snapshots.
 */
internal actual fun platformRuntimeConfig(): RuntimeConfig =
    RuntimeConfig(maxLive = 4, maxIdle = 2, snapshotCacheBytes = 32L shl 20)
