package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult

internal actual fun platformEngineFactory(): WebEngineFactory =
    WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) }

/**
 * Desktop: 8 live engines and 3 spares, so a screenful of embeds recycles instead of rebuilding; 128 MB of stored
 * snapshots and 64 MB of decoded ones.
 */
internal actual fun platformRuntimeConfig(): RuntimeConfig =
    RuntimeConfig(maxLive = 8, maxIdle = 3, snapshotCacheBytes = 128L shl 20, decodedSnapshotBytes = 64L shl 20)
