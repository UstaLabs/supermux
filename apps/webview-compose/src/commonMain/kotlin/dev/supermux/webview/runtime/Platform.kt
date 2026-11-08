package dev.supermux.webview.runtime

import dev.supermux.webview.engine.WebEngineFactory

/** This platform's engines. Until its engine plan lands, a platform reports `Unavailable(NotImplemented)`. */
internal expect fun platformEngineFactory(): WebEngineFactory

/** This platform's pool and cache sizes (spec §4). */
internal expect fun platformRuntimeConfig(): RuntimeConfig

/**
 * Sizes for phones and tablets (spec §4): 4 live engines on a phone, 6 on a tablet, [maxIdle] spares, 32 MB of stored
 * snapshots (150 to 300 sharp pages) and 24 MB of decoded ones ready to draw (about a screenful and the next).
 */
internal fun mobileRuntimeConfig(tablet: Boolean, maxIdle: Int): RuntimeConfig = RuntimeConfig(
    maxLive = if (tablet) 6 else 4,
    maxIdle = maxIdle,
    snapshotCacheBytes = 32L shl 20,
    decodedSnapshotBytes = 24L shl 20,
)

/**
 * This platform's own engines: what a [WebViewRuntime] creates unless given another factory. Public so an app can wrap
 * it, to count the engines it creates, say. Until a platform's engine is built, its engines are
 * `Unavailable(NotImplemented)`.
 */
public fun platformWebEngineFactory(): WebEngineFactory = platformEngineFactory()
