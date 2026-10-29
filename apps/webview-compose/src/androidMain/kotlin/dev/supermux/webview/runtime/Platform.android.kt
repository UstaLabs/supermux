package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult

internal actual fun platformEngineFactory(): WebEngineFactory =
    WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) }

/** Phones: 4 live engines and 3 spares, so a screenful of embeds recycles instead of rebuilding; 32 MB of snapshots. */
internal actual fun platformRuntimeConfig(): RuntimeConfig =
    RuntimeConfig(maxLive = 4, maxIdle = 3, snapshotCacheBytes = 32L shl 20)
