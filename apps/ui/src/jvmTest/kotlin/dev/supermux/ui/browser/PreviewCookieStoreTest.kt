package dev.supermux.ui.browser

import dev.supermux.net.PreviewCookieDto
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewCookieStoreTest {
    private fun webViews() = AppWebViews(WebViewRuntime(RuntimeConfig(
        maxLive = 1, snapshotCacheBytes = 1L shl 20, factory = FakeWebEngineFactory(behavior = FakeEngineBehavior()),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), clock = { 0L },
    )))

    private fun cookie(value: String) = PreviewCookieDto(name = "cmux_preview", value = value, url = "https://relay.test/p/", path = "/p/")

    // Ahmet's Fold, 2026-11-10: the broker keeps one preview token per device, and every mint replaces the last. The app
    // minted again (a new screen or process) but set a cookie only once per URL, so the store kept the dead token: 401.
    @Test fun aNewTokenForTheSameHostIsSetAgain() {
        val w = webViews()
        assertTrue(w.needsPreviewCookie(cookie("old")))
        w.previewCookieSet(cookie("old"))
        assertFalse(w.needsPreviewCookie(cookie("old")), "the same token isn't set twice")
        assertTrue(w.needsPreviewCookie(cookie("new")), "a newer token replaces the one in the store")
    }
}
