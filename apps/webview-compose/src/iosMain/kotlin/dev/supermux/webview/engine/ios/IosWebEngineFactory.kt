package dev.supermux.webview.engine.ios

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import kotlinx.coroutines.CancellationException

/**
 * Creates [IosWebEngine]s (spec §7). Each gets a configuration of its own: its profile's data store ([dataStoreFor]), the
 * media policy, the scheme handlers and the relay's message handler. An engine is handed out only once its floor has
 * loaded, so its first lease never waits for WebKit's processes to start. Never throws: a failure is `CreationFailed`.
 */
internal class IosWebEngineFactory : WebEngineFactory {
    /** From iOS 17: before, a named profile's store lasts as long as the app runs ([dataStoreFor]). */
    override val namedProfilesPersist: Boolean get() = osAtLeast(17)

    override suspend fun create(config: EngineConfig): WebEngineResult {
        val engine = try {
            IosWebEngine.create(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, e.message ?: e::class.simpleName)
        }
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
