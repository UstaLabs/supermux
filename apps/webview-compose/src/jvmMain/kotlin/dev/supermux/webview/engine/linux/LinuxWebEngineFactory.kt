package dev.supermux.webview.engine.linux

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.webkit.WebKitScripts
import java.io.File
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
        val profileKind: Int
        val profileDir: String?
        when (val profile = config.profile) {
            WebProfile.Default -> {
                profileKind = WpeNative.PROFILE_DEFAULT
                profileDir = null
            }
            WebProfile.Ephemeral -> {
                profileKind = WpeNative.PROFILE_EPHEMERAL
                profileDir = null
            }
            is WebProfile.Named -> {
                profileKind = WpeNative.PROFILE_NAMED
                val home = System.getProperty("user.home")
                profileDir = File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "dev.supermux.webview/wpe/profiles/${profile.id}").absolutePath
            }
        }
        val id = withContext(Dispatchers.IO) {
            WpeNative.createView(profileKind, profileDir, config.mediaPlaybackRequiresGesture, 800, 600, 1.0)
        }
        if (id <= 0) return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, "WPE didn't create a web view")
        val engine = LinuxWebEngine(config, NativeWpeView(id), WebKitScripts.random(), CoroutineScope(SupervisorJob() + Dispatchers.Main))
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
