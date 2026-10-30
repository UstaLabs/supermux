package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.linux.LinuxWebEngineFactory
import dev.supermux.webview.engine.linux.WpeDefaults
import dev.supermux.webview.engine.mac.MacPlatform
import dev.supermux.webview.engine.mac.MacWebEngineFactory

internal actual fun platformEngineFactory(): WebEngineFactory =
    desktopEngineFactory(System.getProperty("os.name").orEmpty(), System.getProperty("os.version").orEmpty())

/**
 * The desktop's engines: WPE WebKit on Linux (when the app ships its runtime), `WKWebView` on macOS 14 and later (D12);
 * Windows has none yet. Each reports why it is `Unavailable` where it can't run (no runtime, no glue, an older macOS).
 */
internal fun desktopEngineFactory(osName: String, osVersion: String): WebEngineFactory = when {
    isLinux(osName) -> LinuxWebEngineFactory()
    !MacPlatform.isMac(osName) -> WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) }
    MacPlatform.majorVersion(osVersion) < MacPlatform.MIN_MAJOR -> WebEngineFactory {
        WebEngineResult.Unavailable(UnavailableReason.NotImplemented, "the macOS engine needs macOS 14 or later")
    }
    else -> MacWebEngineFactory()
}

/**
 * Desktop: 8 live engines and 3 spares, so a screenful of embeds recycles instead of rebuilding; 128 MB of stored
 * snapshots and 64 MB of decoded ones. On Linux, what the WPE test's memory allowed: [WpeDefaults.MAX_LIVE] live and
 * [WpeDefaults.MAX_IDLE] spares, since every WPE engine is a web process of its own.
 */
internal actual fun platformRuntimeConfig(): RuntimeConfig = desktopRuntimeConfig(System.getProperty("os.name").orEmpty())

/** The pool and caches for the desktop named [osName] (see [platformRuntimeConfig]). */
internal fun desktopRuntimeConfig(osName: String): RuntimeConfig = RuntimeConfig(
    maxLive = if (isLinux(osName)) WpeDefaults.MAX_LIVE else 8,
    maxIdle = if (isLinux(osName)) WpeDefaults.MAX_IDLE else 3,
    snapshotCacheBytes = 128L shl 20,
    decodedSnapshotBytes = 64L shl 20,
)

private fun isLinux(osName: String): Boolean = osName.startsWith("Linux")
