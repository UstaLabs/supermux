package dev.supermux.webview.runtime

import dev.supermux.webview.engine.WebEngineFactory

/** This platform's engines. Until its engine plan lands, a platform reports `Unavailable(NotImplemented)`. */
internal expect fun platformEngineFactory(): WebEngineFactory

/** This platform's pool and cache sizes (spec §4). */
internal expect fun platformRuntimeConfig(): RuntimeConfig

/**
 * Sizes for phones and tablets (spec §4): 4 live engines on a phone, 6 on a tablet, [maxIdle] spares and 32 MB of
 * snapshots.
 */
internal fun mobileRuntimeConfig(tablet: Boolean, maxIdle: Int): RuntimeConfig =
    RuntimeConfig(maxLive = if (tablet) 6 else 4, maxIdle = maxIdle, snapshotCacheBytes = 32L shl 20)
