package dev.supermux.webview.runtime

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad

internal actual fun platformEngineFactory(): WebEngineFactory =
    WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) }

/**
 * iPhones: 4 live engines; iPads 6. 2 spares, not 3, since each idle WKWebView may keep its own WebContent process;
 * 32 MB of stored snapshots, 24 MB decoded.
 */
internal actual fun platformRuntimeConfig(): RuntimeConfig =
    mobileRuntimeConfig(tablet = UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad, maxIdle = 2)
