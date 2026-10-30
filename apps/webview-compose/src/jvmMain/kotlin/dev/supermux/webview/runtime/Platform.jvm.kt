package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.linux.LinuxWebEngineFactory
import dev.supermux.webview.engine.linux.WpeDefaults

private val isLinux: Boolean = System.getProperty("os.name").orEmpty().startsWith("Linux")

/** Linux runs WPE WebKit (when the app ships its runtime); macOS and Windows report NotImplemented until their engines land. */
internal actual fun platformEngineFactory(): WebEngineFactory =
    if (isLinux) LinuxWebEngineFactory()
    else WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) }

/**
 * Desktop: 8 live engines and 3 spares, so a screenful of embeds recycles instead of rebuilding; 128 MB of stored
 * snapshots and 64 MB of decoded ones. On Linux, what the WPE test's memory allowed: [WpeDefaults.MAX_LIVE] live and
 * [WpeDefaults.MAX_IDLE] spares, since every WPE engine is a web process of its own.
 */
internal actual fun platformRuntimeConfig(): RuntimeConfig = RuntimeConfig(
    maxLive = if (isLinux) WpeDefaults.MAX_LIVE else 8,
    maxIdle = if (isLinux) WpeDefaults.MAX_IDLE else 3,
    snapshotCacheBytes = 128L shl 20,
    decodedSnapshotBytes = 64L shl 20,
)
