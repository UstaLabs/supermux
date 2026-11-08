package dev.supermux.webview.runtime

import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.ios.IosWebEngineFactory
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad

/** `WKWebView`s (spec §7). */
internal actual fun platformEngineFactory(): WebEngineFactory = IosWebEngineFactory()

/**
 * iPhones: 4 live engines; iPads 6. 2 spares, not 3, since each idle WKWebView may keep its own WebContent process;
 * 32 MB of stored snapshots, 24 MB decoded.
 */
internal actual fun platformRuntimeConfig(): RuntimeConfig =
    mobileRuntimeConfig(tablet = UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad, maxIdle = 2)
