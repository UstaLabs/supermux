package dev.supermux.ui.browser

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.supermux.net.PreviewCookieDto
import dev.supermux.net.ProxyDto
import dev.supermux.proto.ViewDto
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.ui.keys.KeyContext
import dev.supermux.ui.keys.ProvideKeyScope
import dev.supermux.ui.keys.ShortcutDispatcher
import dev.supermux.ui.keys.ShortcutRoot
import dev.supermux.ui.keys.keyScope
import dev.supermux.ui.keys.rememberKeyScope
import androidx.compose.runtime.remember
import dev.supermux.webview.FindResult
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BrowserPaneTest {
    private fun view(url: String?) = ViewDto(
        id = "b1", workspaceId = "w1", kind = "browser",
        state = JsonObject(if (url == null) emptyMap() else mapOf("url" to JsonPrimitive(url))),
    )

    private val preview = ProxyDto(domain = "p-5173", sessionName = "s", port = 5173, url = "https://relay.test/p/p-5173/")
    private val cookie = PreviewCookieDto(name = "cmux_preview", value = "pv", url = "https://relay.test/p/", path = "/p/")

    private class Rig {
        val engines = FakeWebEngineFactory(behavior = FakeEngineBehavior())
        val writes = mutableListOf<Pair<JsonObject, String?>>()
        val calls: List<String> get() = engines.created.flatMap { it.calls }
        fun loads(): List<String> = calls.filter { it.startsWith("load:") && "url:" in it }.map { it.substringAfter("url:") }
    }

    private fun Rig.actions(previews: List<ProxyDto> = emptyList(), cookie: PreviewCookieDto? = null, byPort: String? = null) = BrowserActions(
        saveView = { _, _, state, title -> writes += state to title; true },
        proxies = { previews },
        byPort = { byPort },
        previewCookie = { cookie },
    )

    private fun ComposeUiTest.show(rig: Rig, view: MutableState<ViewDto>, actions: BrowserActions, platform: FakePlatform = FakePlatform(), opener: LinkOpener? = null, icons: SnapshotStateMap<String, String>? = null) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20, factory = rig.engines, scope = scope, clock = { mainClock.currentTime }),
        )
        setPlatformContent(platform = platform) {
            CompositionLocalProvider(LocalAppWebViews provides AppWebViews(runtime), LocalLinkOpener provides opener, LocalBrowserIcons provides icons) {
                BrowserPane(view.value, "w1", actions, Modifier.size(800.dp, 600.dp))
            }
        }
    }

    @Test fun theStoredPageOpensAndIsWrittenBack() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions())
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        waitUntil(timeoutMillis = 5_000) { rig.writes.isNotEmpty() }
        assertEquals(JsonObject(mapOf("url" to JsonPrimitive("https://vitejs.dev/"))), rig.writes.last().first)
        onNodeWithTag("browser-address").assertTextEquals("vitejs.dev")
    }

    @Test fun aNewTabWaitsForAnAddressAndLoadsWhatIsTyped() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view(null)), rig.actions())
        onNodeWithTag("browser-new-tab").assertExists()
        assertTrue(rig.engines.created.isEmpty(), "a new tab makes no engine")
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performTextReplacement("vitejs.dev")
        onNodeWithTag("browser-address").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev" in rig.loads() }
    }

    /** #122: a new tab (⌘T) waits with its address bar focused, as Chrome's does. A tab with a page doesn't take it. */
    @Test fun aNewTabTakesTheFocusForItsAddress() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view(null)), rig.actions())
        waitForIdle()
        onNodeWithTag("browser-address").assertIsFocused()
    }

    @Test fun aTabWithAPageLeavesTheFocusAlone() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions())
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        onNodeWithTag("browser-address").assertIsNotFocused()
    }

    @Test fun aPageAnotherClientStoredIsFollowed() = runComposeUiTest {
        val rig = Rig()
        val v = mutableStateOf(view("https://a.test/"))
        show(rig, v, rig.actions())
        waitUntil(timeoutMillis = 5_000) { "https://a.test/" in rig.loads() }
        v.value = view("https://c.test/")
        waitUntil(timeoutMillis = 5_000) { "https://c.test/" in rig.loads() }
    }

    @Test fun aLocalhostLinkOnAnotherDeviceOpensThePortsPreview() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("http://localhost:5173/login")), rig.actions(previews = listOf(preview)))
        waitUntil(timeoutMillis = 5_000) { "https://relay.test/p/p-5173/login" in rig.loads() }
    }

    @Test fun aLocalhostLinkOpensThroughTheHostsByPortPreview() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("http://localhost:3000/app")), rig.actions(byPort = "https://relay.test/p/{port}/", cookie = cookie))
        waitUntil(timeoutMillis = 5_000) { "https://relay.test/p/3000/app" in rig.loads() }
        onNodeWithTag("browser-address").assertTextEquals("port 3000/app")
    }

    @Test fun aPortWithNoRouteSaysSoInsteadOfLoading() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("http://localhost:3000/")), rig.actions())
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("browser-said").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rig.loads().isEmpty())
    }

    @Test fun aPreviewTakesTheCookieBeforeItLoads() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("https://relay.test/p/p-5173/")), rig.actions(previews = listOf(preview), cookie = cookie))
        waitUntil(timeoutMillis = 5_000) { "https://relay.test/p/p-5173/" in rig.loads() }
        val cookieAt = rig.calls.indexOf("cookie:cmux_preview")
        val loadAt = rig.calls.indexOfFirst { it.startsWith("load:") && it.endsWith("https://relay.test/p/p-5173/") }
        assertTrue(cookieAt in 0 until loadAt, "the cookie first: ${rig.calls}")
    }

    @Test fun ctrlRReloadsAndCtrlFFindsWithTheEnginesCountThroughTheRegistry() = runComposeUiTest {
        val rig = Rig()
        val v = mutableStateOf(view("https://vitejs.dev/"))
        val actions = rig.actions()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20, factory = rig.engines, scope = scope, clock = { mainClock.currentTime }),
        )
        setPlatformContent {
            CompositionLocalProvider(LocalAppWebViews provides AppWebViews(runtime)) {
                ShortcutRoot(remember { ShortcutDispatcher() }, Modifier.size(800.dp, 600.dp)) {
                    // As WorkspacePanes scopes a pane.
                    val paneScope = rememberKeyScope(KeyContext.Browser, "b1")
                    ProvideKeyScope(paneScope) {
                        BrowserPane(v.value, "w1", actions, Modifier.keyScope(paneScope))
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        rig.engines.created.forEach { it.findAnswer = { _, _ -> FindResult(found = true, count = 5, current = 2) } }
        onNodeWithTag("browser-address").performClick()
        val mod = if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft
        onNodeWithTag("browser-address").performKeyInput { withKeyDown(mod) { pressKey(Key.R) } }
        waitUntil(timeoutMillis = 5_000) { "reload" in rig.calls }
        onNodeWithTag("browser-address").performKeyInput { withKeyDown(mod) { pressKey(Key.F) } }
        onNodeWithTag("browser-find-field").performTextInput("vite")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("browser-find-count").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("browser-find-count").assertTextEquals("2 of 5")
    }

    /** A Browser pane inside the app's key registry, as WorkspacePanes scopes it (#96). */
    private fun ComposeUiTest.showWithKeys(rig: Rig, v: MutableState<ViewDto>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20, factory = rig.engines, scope = scope, clock = { mainClock.currentTime }),
        )
        setPlatformContent {
            CompositionLocalProvider(LocalAppWebViews provides AppWebViews(runtime)) {
                ShortcutRoot(remember { ShortcutDispatcher() }, Modifier.size(800.dp, 600.dp)) {
                    val paneScope = rememberKeyScope(KeyContext.Browser, "b1")
                    ProvideKeyScope(paneScope) {
                        BrowserPane(v.value, "w1", rig.actions(), Modifier.keyScope(paneScope))
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { v.value.state["url"].toString().trim('"') in rig.loads() }
    }

    private val mod = if (isApplePlatform) Key.MetaLeft else Key.CtrlLeft

    /** #122: ⌘G / Ctrl+G is the next match and ⌘⇧G / Ctrl+Shift+G the previous one, with the find bar's query. */
    @Test fun findNextAndPreviousKeysStepThroughTheMatches() = runComposeUiTest {
        val rig = Rig()
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        rig.engines.created.forEach { it.findAnswer = { _, _ -> FindResult(found = true, count = 5, current = 2) } }
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performKeyInput { withKeyDown(mod) { pressKey(Key.F) } }
        onNodeWithTag("browser-find-field").performTextInput("vite")
        waitUntil(timeoutMillis = 5_000) { "find:vite:false" in rig.calls }
        val forward = rig.calls.count { it == "find:vite:false" }
        onNodeWithTag("browser-find-field").performKeyInput { withKeyDown(mod) { pressKey(Key.G) } }
        waitUntil(timeoutMillis = 5_000) { rig.calls.count { it == "find:vite:false" } == forward + 1 }
        onNodeWithTag("browser-find-field").performKeyInput { withKeyDown(mod) { withKeyDown(Key.ShiftLeft) { pressKey(Key.G) } } }
        waitUntil(timeoutMillis = 5_000) { "find:vite:true" in rig.calls }
    }

    /** #122: F12 off Apple, ⌘⌥I on Apple, open the engine's inspector, as Chrome's DevTools keys do. */
    @Test fun theDevToolsKeyOpensTheInspector() = runComposeUiTest {
        val rig = Rig()
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performKeyInput {
            if (isApplePlatform) withKeyDown(Key.MetaLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.I) } } else pressKey(Key.F12)
        }
        waitUntil(timeoutMillis = 5_000) { "inspector" in rig.calls }
    }

    /** #122: Esc stops a page only while it loads; on a loaded page the key is left alone (it's the page's). */
    @Test fun escapeOnALoadedPageDoesNotStop() = runComposeUiTest {
        val rig = Rig()
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertTrue("stop" !in rig.calls, "${rig.calls}")
    }

    @Test fun noEngineHereSaysSoAndOffersTheSystemBrowser() = runComposeUiTest {
        val rig = Rig().apply { engines.unavailable = UnavailableReason.NotImplemented }
        val platform = FakePlatform()
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions(), platform)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("browser-unavailable").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Open in your browser").performClick()
        assertEquals(listOf("https://vitejs.dev/"), platform.openedUrls)
    }

    @Test fun eachCommittedPageTellsItsTabItsIcon() = runComposeUiTest {
        val rig = Rig()
        val icons = mutableStateMapOf<String, String>()
        show(rig, mutableStateOf(view("https://a.test/")), rig.actions(), icons = icons)
        waitUntil(timeoutMillis = 5_000) { "https://a.test/" in rig.loads() }
        rig.engines.created.forEach { it.evaluateAnswer = { "\"https://vitejs.dev/logo.svg\"" } }
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performTextReplacement("vitejs.dev")
        onNodeWithTag("browser-address").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { icons["b1"] == "https://vitejs.dev/logo.svg" }
    }
}
