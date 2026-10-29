package dev.supermux.webview.engine.android

import android.view.Choreographer
import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Android's engines (spec §7): the system WebView, built for the Activity that shows web views (never the application
 * context), once WebView has started up, and two frames after it was asked for, so building one never freezes the frame
 * that shows its view's cover. The runtime's default on Android ([dev.supermux.webview.runtime.platformWebEngineFactory]);
 * build one yourself only to wrap it, to count engines, say.
 * - No WebView, or one that can't start: `Unavailable(NoWebViewProvider)`.
 * - A WebView without the features the engine needs (see `Features`): `Unavailable(NoWebViewProvider)` with the list.
 * - No Activity has shown a web view yet, or anything else: `Unavailable(CreationFailed)`, which the runtime retries.
 */
class AndroidWebEngineFactory : WebEngineFactory {
    override suspend fun create(config: EngineConfig): WebEngineResult = try {
        withContext(Dispatchers.Main.immediate) { createOnMain(config) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        WebEngineResult.Unavailable(UnavailableReason.CreationFailed, e.toString())
    }

    private suspend fun createOnMain(config: EngineConfig): WebEngineResult {
        val asking = ActivityRegistry.current()
            ?: return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, "no Activity shows a web view yet")
        when (val startup = WebViewStartup.begin(asking.applicationContext).await()) {
            is WebViewStartup.Result.Failed -> return WebEngineResult.Unavailable(UnavailableReason.NoWebViewProvider, startup.detail)
            WebViewStartup.Result.Ready -> Unit
        }
        val missing = Features.missing(config.profile)
        if (missing.isNotEmpty()) {
            return WebEngineResult.Unavailable(
                UnavailableReason.NoWebViewProvider,
                "Android System WebView ${Features.version(asking)} lacks $missing; update it",
            )
        }
        awaitFrames(2)
        val activity = ActivityRegistry.current()
            ?: return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, "the Activity went away")
        return when (val profile = config.profile) {
            WebProfile.Default -> WebEngineResult.Created(AndroidWebEngine(config, activity, profileName = null))
            is WebProfile.Named -> WebEngineResult.Created(AndroidWebEngine(config, activity, namedProfile(profile.id)))
            WebProfile.Ephemeral -> {
                val taken = EphemeralProfiles.take()
                WebEngineResult.Created(AndroidWebEngine(config, activity, taken.name, reusedProfile = taken.reused))
            }
        }
    }
}

/** Waits for [count] frames of the main thread's Choreographer. */
internal suspend fun awaitFrames(count: Int) = repeat(count) {
    suspendCancellableCoroutine { continuation ->
        Choreographer.getInstance().postFrameCallback { if (continuation.isActive) continuation.resume(Unit) }
    }
}
