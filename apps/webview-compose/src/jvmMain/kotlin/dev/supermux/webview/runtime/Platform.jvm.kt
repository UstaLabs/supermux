package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.desktop.hostDesktop
import dev.supermux.webview.engine.linux.LinuxWebEngineFactory
import dev.supermux.webview.engine.linux.WpeDefaults
import dev.supermux.webview.engine.mac.MacPlatform
import dev.supermux.webview.engine.mac.MacWebEngineFactory
import dev.supermux.webview.engine.windows.WinDefaults
import dev.supermux.webview.engine.windows.WindowsWebEngineFactory
import org.jetbrains.skiko.OS

internal actual fun platformEngineFactory(): WebEngineFactory = desktopEngineFactory(hostDesktop, System.getProperty("os.version").orEmpty())

/**
 * The desktop's engines: WPE WebKit on Linux (when the app ships its runtime), `WKWebView` on macOS 14 and later (D12),
 * WebView2 on Windows (when its runtime is installed). Each reports why it is `Unavailable` where it can't run (no
 * runtime, no glue, an older macOS).
 */
internal fun desktopEngineFactory(os: OS?, osVersion: String): WebEngineFactory = when {
    os == OS.Linux -> LinuxWebEngineFactory()
    os == OS.Windows -> WindowsWebEngineFactory()
    os != OS.MacOS -> WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) }
    MacPlatform.majorVersion(osVersion) < MacPlatform.MIN_MAJOR -> WebEngineFactory {
        WebEngineResult.Unavailable(UnavailableReason.NotImplemented, "the macOS engine needs macOS 14 or later")
    }
    else -> MacWebEngineFactory()
}

/**
 * Desktop: 8 live engines and 3 spares, so a screenful of embeds recycles instead of rebuilding; 128 MB of stored
 * snapshots and 64 MB of decoded ones. On Linux, what the WPE test's memory allowed: [WpeDefaults.MAX_LIVE] live and
 * [WpeDefaults.MAX_IDLE] spares, since every WPE engine is a web process of its own. On Windows, [WinDefaults]' (the
 * desktop's, until the perf lab measures memory per view).
 */
internal actual fun platformRuntimeConfig(): RuntimeConfig = desktopRuntimeConfig(hostDesktop)

/** The pool and caches for the desktop [os] (see [platformRuntimeConfig]); null: one Skiko doesn't know. */
internal fun desktopRuntimeConfig(os: OS?): RuntimeConfig = RuntimeConfig(
    maxLive = when (os) { OS.Linux -> WpeDefaults.MAX_LIVE; OS.Windows -> WinDefaults.MAX_LIVE; else -> 8 },
    maxIdle = when (os) { OS.Linux -> WpeDefaults.MAX_IDLE; OS.Windows -> WinDefaults.MAX_IDLE; else -> 3 },
    snapshotCacheBytes = 128L shl 20,
    decodedSnapshotBytes = 64L shl 20,
)
