package dev.supermux.webview.engine.mac

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.webkit.WebKitScripts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Creates [MacWebEngine]s (spec §7). It starts loading the glue (D8) off the UI thread as soon as it is made, which is
 * when the runtime is; engines wait for it there: a failure is `NativeLibraryFailed`, with why. Each engine gets its
 * own configuration, data store, scheme handlers and secret event names, and is handed out only once its floor has
 * loaded, so a lease never waits for WebKit's processes to start. Never throws.
 */
internal class MacWebEngineFactory(private val glue: BackgroundGlue = MacGlueReady) : WebEngineFactory {
    init {
        glue.prewarm()
    }

    override suspend fun create(config: EngineConfig): WebEngineResult {
        glue.await()?.let { return WebEngineResult.Unavailable(UnavailableReason.NativeLibraryFailed, it) }
        val scripts = WebKitScripts.random()
        val glue = try {
            NativePageGlue.create(config, scripts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, "WebKit didn't make the page")
        val engine = MacWebEngine(
            config = config,
            glue = glue,
            scripts = scripts,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            onReleased = { MacPages.unregister(glue.token) },
        )
        MacPages.register(glue.token, engine::onGlueEvent)
        val ready = try {
            engine.warmUp()
        } catch (e: CancellationException) {
            engine.release()
            throw e
        } catch (e: Exception) {
            false
        }
        if (ready) return WebEngineResult.Created(engine)
        engine.release()
        return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, "WebKit didn't load the engine's first page")
    }
}
