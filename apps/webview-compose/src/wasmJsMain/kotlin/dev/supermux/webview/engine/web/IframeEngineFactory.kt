package dev.supermux.webview.engine.web

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult

/**
 * Creates the web's engines. It never throws. A browser without `clip-path: path()` gets [CutoutMode.HidePage] whatever
 * was decided. Profiles: the browser has one storage for the app, so `Named` profiles share it (README); `Ephemeral`
 * engines refuse network pages, since nothing could keep their storage apart.
 */
internal object IframeEngineFactory : WebEngineFactory {
    override suspend fun create(config: EngineConfig): WebEngineResult = try {
        val mode = if (supportsClipPathPath()) WebEngineDecisions.cutoutMode else CutoutMode.HidePage
        WebEngineResult.Created(IframeWebEngine(config, mode))
    } catch (e: Throwable) {
        WebEngineResult.Unavailable(UnavailableReason.CreationFailed, e.message ?: e.toString())
    }
}
