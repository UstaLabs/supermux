package dev.supermux.webview.engine.windows

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Windows' engines (spec §7): WebView2 through [Wv2Runtime], which loads the glue and opens the environment off the EDT
 * as soon as the factory is made (L29). Each engine gets its profile (D8), its own secret script names (D5), and is
 * handed out once WebView2 has made its page and an ephemeral one's slot is wiped. Never throws.
 */
internal class WindowsWebEngineFactory(
    private val runtime: suspend () -> Wv2Runtime.State = { withContext(Dispatchers.IO) { Wv2Runtime.start() } },
) : WebEngineFactory {
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runtime() }
        WinPages.onBrowserExited = { Wv2Runtime.browserExited() }
    }

    override suspend fun create(config: EngineConfig): WebEngineResult {
        val state = runtime()
        if (state is Wv2Runtime.State.Unavailable) return WebEngineResult.Unavailable(state.reason, state.detail)
        val ready = state as Wv2Runtime.State.Ready
        val slots = Wv2Profiles.slots(ready.userDataFolder)
        var slot: Int? = null
        val name = when (config.profile) {
            WebProfile.Ephemeral -> {
                slot = slots.claim() ?: return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, "every ephemeral slot is taken")
                Wv2Profiles.ephemeralName(slot)
            }
            else -> checkNotNull(Wv2Profiles.profileName(config.profile))
        }
        val giveBack: () -> Unit = { slot?.let(slots::release) }
        val (token, created) = WinPages.newPage()
        WinGlue.createPage(token, name, config.profile == WebProfile.Ephemeral, WHITE)
        val made = try {
            withTimeoutOrNull(CREATE_MILLIS) { created.await() }
        } catch (e: CancellationException) {
            // Nobody waits any more: the page goes now, or as it arrives (WvPage::onCreated), and so does its slot.
            WinGlue.release(token)
            WinPages.forget(token)
            giveBack()
            throw e
        }
        if (made == null || !made.ok) {
            // The glue let the environment go if it couldn't make the page on it: the next engine opens it anew.
            Wv2Runtime.browserExited()
            // Released now: a page WebView2 makes after all is closed as it arrives (WvPage::onCreated).
            WinGlue.release(token)
            WinPages.forget(token)
            giveBack()
            return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, made?.detail ?: "WebView2 didn't make the page within 30 s")
        }
        val engine = WindowsWebEngine(
            config = config,
            page = NativeWinPage(token),
            scripts = Wv2Scripts.random(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            onReleased = giveBack,
        )
        val warm = try {
            engine.warmUp()
        } catch (e: CancellationException) {
            engine.release()
            throw e
        } catch (e: Exception) {
            false
        }
        if (warm) return WebEngineResult.Created(engine)
        engine.release()
        return WebEngineResult.Unavailable(UnavailableReason.CreationFailed, "WebView2 didn't ready the engine")
    }

    private companion object {
        const val CREATE_MILLIS = 30_000L
        const val WHITE = -1   // 0xFFFFFFFF: opaque white until a lease's configure
    }
}
