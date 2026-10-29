package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult

internal actual fun platformEngineFactory(): WebEngineFactory =
    WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) }

/**
 * The web: 12 live iframes and 3 spares, so a screenful of embeds recycles instead of rebuilding; 64 MB of snapshots.
 */
internal actual fun platformRuntimeConfig(): RuntimeConfig =
    RuntimeConfig(maxLive = 12, maxIdle = 3, snapshotCacheBytes = 64L shl 20)
