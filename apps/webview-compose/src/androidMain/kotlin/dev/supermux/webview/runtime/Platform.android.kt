package dev.supermux.webview.runtime

import android.content.res.Resources
import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult

internal actual fun platformEngineFactory(): WebEngineFactory =
    WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) }

/**
 * Phones: 4 live engines; tablets, whose smallest width is 600 dp or more, 6. The system resources tell, with no
 * Context. 3 spares, so a screenful of embeds recycles instead of rebuilding; 32 MB of snapshots.
 */
internal actual fun platformRuntimeConfig(): RuntimeConfig =
    mobileRuntimeConfig(tablet = Resources.getSystem().configuration.smallestScreenWidthDp >= 600, maxIdle = 3)
