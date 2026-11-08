package dev.supermux.webview.engine.linux

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.webkit.WebKitScripts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * Linux's engines: WPE WebKit through [WpeRuntime], which finds the app's runtime and starts WebKit on first use. Without
 * one, every engine is `Unavailable` with the reason (spec §7: Linux reports Unavailable until it can run).
 */
internal class LinuxWebEngineFactory : WebEngineFactory {
    override suspend fun create(config: EngineConfig): WebEngineResult {
        val state = withContext(Dispatchers.IO) { WpeRuntime.start() }
        if (state is WpeRuntime.State.Unavailable) return WebEngineResult.Unavailable(state.reason, state.detail)
        val runtime = state as WpeRuntime.State.Ready
        // One process of the app at a time has a profile's folder: WebKit's network process won't share its store.
        val dir = WpeProfiles.dataDir(runtime.data, config.profile)
        if (dir != null) withContext(Dispatchers.IO) { WpeProfiles.locks.lock(dir) }?.let { return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, it) }
        val profileKind = when (config.profile) {
            WebProfile.Default -> WpeNative.PROFILE_DEFAULT
            WebProfile.Ephemeral -> WpeNative.PROFILE_EPHEMERAL
            is WebProfile.Named -> WpeNative.PROFILE_NAMED
        }
        val profileDir = if (config.profile is WebProfile.Named) dir?.absolutePath else null
        val id = withContext(Dispatchers.IO) {
            WpeNative.createView(profileKind, profileDir, config.mediaPlaybackRequiresGesture, 800, 600, 1.0)
        }
        if (id <= 0) return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, "WPE didn't create a web view")
        val engine = LinuxWebEngine(config, NativeWpeView(id), WebKitScripts.random(), CoroutineScope(SupervisorJob() + Dispatchers.Main), WpeClipboard.System)
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
