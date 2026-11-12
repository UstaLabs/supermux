package dev.supermux.ui.browser

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.click
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

@OptIn(ExperimentalTestApi::class, dev.supermux.webview.engine.WebEngineSpi::class)
class BrowserPaneTest {
    private fun view(url: String?) = ViewDto(
        id = "b1", workspaceId = "w1", kind = "browser",
        state = JsonObject(if (url == null) emptyMap() else mapOf("url" to JsonPrimitive(url))),
    )

    private val preview = ProxyDto(domain = "p-5173", sessionName = "s", port = 5173, url = "https://relay.test/p/p-5173/")
    private val cookie = PreviewCookieDto(name = "cmux_preview", value = "pv", url = "https://relay.test/p/", path = "/p/")

    private class Rig(behavior: FakeEngineBehavior = FakeEngineBehavior()) {
        val engines = FakeWebEngineFactory(behavior = behavior)
        val writes = mutableListOf<Pair<JsonObject, String?>>()
        // The engines append from their own threads: a read that meets a write tries again.
        val calls: List<String>
            get() {
                while (true) {
                    try {
                        return engines.created.toList().flatMap { it.calls.toList() }
                    } catch (_: ConcurrentModificationException) {
                    }
                }
            }
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

    // Like a real browser's tab: another tab in front leaves the page alive, scroll, form and all.
    @Test fun aTabThatLeavesAndComesBackKeepsItsLivePage() = runComposeUiTest {
        val rig = Rig()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val webViews = AppWebViews(
            WebViewRuntime(RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20, factory = rig.engines, scope = scope, clock = { mainClock.currentTime })),
        )
        val front = mutableStateOf(true)
        val actions = rig.actions()
        setPlatformContent(platform = FakePlatform()) {
            CompositionLocalProvider(LocalAppWebViews provides webViews) {
                // The pane leaves the composition while another tab is in front, as a tab strip does.
                if (front.value) BrowserPane(view("https://vitejs.dev/"), "w1", actions, Modifier.size(800.dp, 600.dp))
            }
        }
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        waitForIdle()
        front.value = false
        waitForIdle()
        front.value = true
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("browser-address").fetchSemanticsNodes().isNotEmpty() }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(listOf("https://vitejs.dev/"), rig.loads(), "the page loaded again: ${rig.calls}")
        // The parked page itself came back (replayed to the new view), live; any other engine is an idle spare.
        val first = rig.engines.created.first().calls
        assertTrue("replay" in first.drop(first.indexOf("save")), "the parked page wasn't adopted: $first")
        assertTrue(rig.engines.created.drop(1).all { it.calls.none { c -> c.startsWith("load") } }, "another engine loaded a page")
    }

    // A closed tab's page goes with it; an open one's stays.
    @Test fun aClosedBrowserTabsPageIsLetGo() = runComposeUiTest {
        val rig = Rig()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val webViews = AppWebViews(
            WebViewRuntime(RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20, factory = rig.engines, scope = scope, clock = { mainClock.currentTime })),
        )
        val kept = webViews.tab("b1")
        val closed = webViews.tab("b2")
        val views = mutableStateOf(listOf(view(null), view(null).copy(id = "b2"), view(null).copy(id = "t1", kind = "terminal")))
        setPlatformContent(platform = FakePlatform()) {
            CompositionLocalProvider(LocalAppWebViews provides webViews) { ReleaseClosedBrowserTabs(views.value) }
        }
        waitForIdle()
        views.value = views.value.filter { it.id != "b2" }
        waitForIdle()
        assertTrue(webViews.tab("b1") === kept, "an open tab lost its page")
        assertTrue(webViews.tab("b2") !== closed, "a closed tab kept its page")
    }

    // Desktop: a tab not in front stays composed, hidden, its page never parked or saved (Ahmet: tabs never freeze).
    @Test fun onDesktopATabInTheBackStaysComposedAndItsPageIsNeverParked() = runComposeUiTest {
        val rig = Rig()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val webViews = AppWebViews(
            WebViewRuntime(RuntimeConfig(maxLive = 4, snapshotCacheBytes = 8L shl 20, factory = rig.engines, scope = scope, clock = { mainClock.currentTime })),
        )
        val keeper = BrowserTabKeeper()
        val tab = view("https://vitejs.dev/")
        val front = mutableStateOf("b1")
        val actions = rig.actions()
        setPlatformContent(platform = FakePlatform()) {
            CompositionLocalProvider(LocalAppWebViews provides webViews, LocalBrowserTabKeeper provides keeper) {
                Box(Modifier.size(800.dp, 600.dp)) {
                    if (front.value == "b1") keeper.Show(tab, "w1", actions, Modifier.size(800.dp, 600.dp))
                    KeepBrowserTabsLive(listOf(tab), shown = setOf(front.value), workspaceId = "w1", actions = actions)
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        waitForIdle()
        front.value = "t1"
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        front.value = "b1"
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(listOf("https://vitejs.dev/"), rig.loads(), "the page loaded again: ${rig.calls}")
        val first = rig.engines.created.first().calls
        assertTrue("save" !in first && "replay" !in first, "the page was parked and adopted, not kept: $first")
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
        onNodeWithTag("browser-address-preview", useUnmergedTree = true).assertExists() // #122: a preview's green dot, as the prototype's
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
        rig.engines.created.toList().forEach { it.findAnswer = { _, _ -> FindResult(found = true, count = 5, current = 2) } }
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
        rig.engines.created.toList().forEach { it.findAnswer = { _, _ -> FindResult(found = true, count = 5, current = 2) } }
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

    private class ChatSink : BrowserToChat {
        val sent = mutableListOf<Pair<ByteArray, String>>()
        val messages = mutableListOf<Triple<String, List<Pair<String, ByteArray>>, Boolean>>()
        override fun sendImage(png: ByteArray, name: String): Boolean { sent += png to name; return true }
        override fun sendMessage(text: String, images: List<Pair<String, ByteArray>>, send: Boolean): Boolean {
            messages += Triple(text, images, send)
            return true
        }
        override fun recipient(): String = "Checkout agent"
    }

    private fun ComposeUiTest.showWithChat(rig: Rig, v: MutableState<ViewDto>, sink: BrowserToChat?) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20, factory = rig.engines, scope = scope, clock = { mainClock.currentTime }),
        )
        setPlatformContent {
            CompositionLocalProvider(LocalAppWebViews provides AppWebViews(runtime), LocalBrowserToChat provides sink) {
                ShortcutRoot(remember { ShortcutDispatcher() }, Modifier.size(800.dp, 600.dp)) {
                    val paneScope = rememberKeyScope(KeyContext.Browser, "b1")
                    ProvideKeyScope(paneScope) {
                        BrowserPane(v.value, "w1", rig.actions(), Modifier.keyScope(paneScope))
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
    }

    private fun ByteArray.isPng() = size > 8 && this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'N'.code.toByte()

    /** #122: ⌘⇧S / Ctrl+Shift+S sends the page's picture to the chat's composer (the chat adds it, unsent). */
    @Test fun theScreenshotKeyHandsThePageToTheChat() = runComposeUiTest {
        val rig = Rig()
        val sink = ChatSink()
        showWithChat(rig, mutableStateOf(view("https://vitejs.dev/")), sink)
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performKeyInput { withKeyDown(mod) { withKeyDown(Key.ShiftLeft) { pressKey(Key.S) } } }
        waitUntil(timeoutMillis = 5_000) { sink.sent.isNotEmpty() }
        assertTrue(sink.sent.single().first.isPng(), "a PNG")
        assertTrue(sink.sent.single().second.endsWith(".png"))
    }

    @Test fun theMenuSendsAScreenshotToo() = runComposeUiTest {
        val rig = Rig()
        val sink = ChatSink()
        showWithChat(rig, mutableStateOf(view("https://vitejs.dev/")), sink)
        onNodeWithTag("browser-menu").performClick()
        onNodeWithTag("browser-menu-screenshot").performClick()
        waitUntil(timeoutMillis = 5_000) { sink.sent.isNotEmpty() }
    }

    /**
     * The ⋯ menu opens over the page. On macOS a press goes to whatever is natively under the pointer, so a menu the
     * page isn't told about loses every click to the page (Ahmet's hot run, 2026-11-11): the open menu must be one of
     * the page's overlays.
     */
    @Test fun theOpenMenuIsAnOverlayOfThePage() = runComposeUiTest {
        val rig = Rig()
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        waitForIdle()
        fun holes() = rig.calls.filter { it.startsWith("overlays:") }.lastOrNull()?.substringAfter(':')?.toInt() ?: 0
        assertEquals(0, holes())
        onNodeWithTag("browser-menu").performClick()
        waitUntil(timeoutMillis = 5_000) { holes() > 0 }
    }

    /** Every row of the ⋯ menu does what it says. */
    @Test fun eachMenuRowRunsItsCommand() = runComposeUiTest {
        val rig = Rig()
        val platform = FakePlatform()
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions(), platform)
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        onNodeWithTag("browser-menu").performClick()
        onNodeWithTag("browser-zoom-in").performClick()
        waitUntil(timeoutMillis = 5_000) { "zoom:1.1" in rig.calls }
        onNodeWithTag("browser-zoom-out").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.calls.count { it == "zoom:1.0" } >= 1 }
        onNodeWithTag("browser-menu-inspect").performClick()
        waitUntil(timeoutMillis = 5_000) { "inspector" in rig.calls }
        onNodeWithTag("browser-menu").performClick()
        onNodeWithTag("browser-menu-external").performClick()
        assertEquals(listOf("https://vitejs.dev/"), platform.openedUrls)
        onNodeWithTag("browser-menu").performClick()
        onNodeWithTag("browser-menu-find").performClick()
        onNodeWithTag("browser-find").assertExists()
    }

    /** Where no chat can take it (or the platform can't picture a page), the menu doesn't offer it. */
    @Test fun withNowhereToSendItTheMenuHasNoScreenshot() = runComposeUiTest {
        val rig = Rig()
        showWithChat(rig, mutableStateOf(view("https://vitejs.dev/")), sink = null)
        onNodeWithTag("browser-menu").performClick()
        onNodeWithTag("browser-menu-find").assertExists()
        onNodeWithTag("browser-menu-screenshot").assertDoesNotExist()
    }

    /** #122: a zoom other than 100 % shows inside the address; the menu is the app's own (Find with its key). */
    @Test fun theZoomShowsInTheAddressAndTheMenuIsTheApps() = runComposeUiTest {
        val rig = Rig()
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        onNodeWithTag("browser-zoom", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performKeyInput { withKeyDown(mod) { pressKey(Key.Equals) } }
        onNodeWithTag("browser-menu").performClick() // takes the focus off the address, which hides the chip while editing
        waitForIdle()
        onNodeWithTag("browser-zoom", useUnmergedTree = true).assertExists()
        onNodeWithTag("browser-menu-find").assertExists()
        onNodeWithTag("browser-menu-zoom").assertExists()
    }

    /** #122: on a phone (compact, touch) the menu is a sheet from the bottom, with Back and Forward in it. */
    @Test fun aPhonesMenuIsASheet() = runComposeUiTest {
        val rig = Rig()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20, factory = rig.engines, scope = scope, clock = { mainClock.currentTime }),
        )
        setPlatformContent {
            CompositionLocalProvider(
                LocalAppWebViews provides AppWebViews(runtime),
                dev.supermux.ui.adaptive.LocalWindowWidthClass provides dev.supermux.ui.adaptive.WindowWidthClass.Compact,
                dev.supermux.ui.adaptive.LocalPointerAvailable provides false,
            ) {
                BrowserPane(view("https://vitejs.dev/"), "w1", rig.actions(), Modifier.size(390.dp, 700.dp))
            }
        }
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        onNodeWithTag("browser-menu").performClick()
        onNodeWithTag("browser-sheet").assertExists()
        onNodeWithTag("browser-menu-back").assertExists()
        onNodeWithTag("browser-menu-find").performClick()
        onNodeWithTag("browser-sheet").assertDoesNotExist()
        onNodeWithTag("browser-find").assertExists()
    }

    /** #122 phase 2: ⌘⇧M / Ctrl+Shift+M is DevTools' device toolbar: the page at an iPhone 16's size, then back. */
    @Test fun theDeviceKeyShowsThePageAtAPhonesSize() = runComposeUiTest {
        val rig = Rig()
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        onNodeWithTag("browser-device-bar").assertDoesNotExist()
        onNodeWithTag("browser-viewport").assertWidthIsEqualTo(800.dp)
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performKeyInput { withKeyDown(mod) { withKeyDown(Key.ShiftLeft) { pressKey(Key.M) } } }
        onNodeWithTag("browser-device-bar").assertExists()
        onNodeWithTag("browser-device-preset").assertTextEquals("iPhone 16")
        // Taller than the pane: the page keeps its size and the pane pans.
        onNodeWithTag("browser-viewport").assertWidthIsEqualTo(393.dp).assertHeightIsEqualTo(852.dp)
        onNodeWithTag("browser-address").performKeyInput { withKeyDown(mod) { withKeyDown(Key.ShiftLeft) { pressKey(Key.M) } } }
        onNodeWithTag("browser-device-bar").assertDoesNotExist()
        onNodeWithTag("browser-viewport").assertWidthIsEqualTo(800.dp)
        // The page moved in and out of the stage: the same page, never loaded again.
        assertEquals(listOf("https://vitejs.dev/"), rig.loads())
    }

    /** The bar's button and the menu turn it on; a preset sizes the page and rotate turns it; ✕ closes the bar. */
    @Test fun aPresetSizesThePageAndRotateTurnsIt() = runComposeUiTest {
        val rig = Rig()
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        onNodeWithTag("browser-device").performClick()
        onNodeWithTag("browser-device-preset").performClick()
        onNodeWithTag("browser-device-ipad-mini").performClick()
        onNodeWithTag("browser-viewport").assertWidthIsEqualTo(768.dp).assertHeightIsEqualTo(1024.dp)
        onNodeWithTag("browser-device-rotate").performClick()
        onNodeWithTag("browser-viewport").assertWidthIsEqualTo(1024.dp).assertHeightIsEqualTo(768.dp)
        onNodeWithTag("browser-device-close").performClick()
        onNodeWithTag("browser-device-bar").assertDoesNotExist()
        onNodeWithTag("browser-menu").performClick()
        onNodeWithTag("browser-menu-device").performClick()
        // It comes back as it was left: the iPad, turned.
        onNodeWithTag("browser-viewport").assertWidthIsEqualTo(1024.dp).assertHeightIsEqualTo(768.dp)
    }

    /** A typed size is Responsive: the page takes it on Enter. */
    @Test fun aTypedSizeIsResponsive() = runComposeUiTest {
        val rig = Rig()
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        onNodeWithTag("browser-device").performClick()
        onNodeWithTag("browser-device-width").performTextReplacement("500")
        onNodeWithTag("browser-device-width").performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("browser-device-height").performTextReplacement("400")
        onNodeWithTag("browser-device-height").performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("browser-viewport").assertWidthIsEqualTo(500.dp).assertHeightIsEqualTo(400.dp)
        onNodeWithTag("browser-device-preset").assertTextEquals("Responsive")
    }

    /** The engine showing the page now: the last one leased (a released one has no setup). */
    private fun Rig.live() = engines.created.toList().lastOrNull { it.lastSetup != null }

    /** #122 2a2: device mode gives the page its device's browser agent; Desktop gives it the engine's own again. */
    @Test fun deviceModeGivesThePageItsDevicesAgent() = runComposeUiTest {
        val rig = Rig()
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        assertEquals(null, rig.live()?.lastSetup?.userAgent)
        onNodeWithTag("browser-device").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.live()?.lastSetup?.userAgent?.contains("iPhone") == true }
        onNodeWithTag("browser-device-agent").assertTextEquals("Mobile")
        onNodeWithTag("browser-device-agent").performClick()
        onNodeWithTag("browser-device-agent-desktop").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.live()?.lastSetup?.let { it.userAgent == null } == true }
        onNodeWithTag("browser-device-agent").assertTextEquals("Desktop")
        onNodeWithTag("browser-device-agent").performClick()
        onNodeWithTag("browser-device-agent-mobile").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.live()?.lastSetup?.userAgent?.contains("iPhone") == true }
        onNodeWithTag("browser-device-close").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.live()?.lastSetup?.let { it.userAgent == null } == true }
    }

    /** ◐ in the device bar: the page told light or dark whatever the system is (prefers-color-scheme). */
    @Test fun theDeviceBarSetsThePagesAppearance() = runComposeUiTest {
        val rig = Rig()
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        onNodeWithTag("browser-device").performClick()
        onNodeWithTag("browser-device-appearance").performClick()
        onNodeWithTag("browser-device-appearance-dark").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.live()?.lastSetup?.dark == true }
        onNodeWithTag("browser-device-appearance").performClick()
        onNodeWithTag("browser-device-appearance-light").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.live()?.lastSetup?.dark == false }
    }

    /** Where the engine can't change them (the web client's iframe), there is no agent or appearance control at all. */
    @Test fun withoutPageEmulationTheBarHasNoAgentOrAppearance() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions(), FakePlatform(caps = dev.supermux.ui.platform.NO_CAPS.copy(pageEmulation = false)))
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        onNodeWithTag("browser-device").performClick()
        onNodeWithTag("browser-device-bar").assertExists()
        onNodeWithTag("browser-device-agent").assertDoesNotExist()
        onNodeWithTag("browser-device-appearance").assertDoesNotExist()
    }

    private fun Rig.lastZoom(): Float? = calls.lastOrNull { it.startsWith("zoom:") }?.substringAfter(':')?.toFloat()

    /**
     * #122 2b: where the engine has a page zoom, a device bigger than the pane fits it: the page's view is the device's
     * size times the scale and the page is zoomed by the same, so its CSS width stays the device's.
     */
    @Test fun aBigDeviceFitsThePaneAtItsOwnCssSize() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions(), FakePlatform(caps = dev.supermux.ui.platform.NO_CAPS.copy(pageFit = true)))
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        onNodeWithTag("browser-device").performClick()
        onNodeWithTag("browser-device-preset").performClick()
        onNodeWithTag("browser-device-ipad-mini").performClick()
        waitUntil(timeoutMillis = 5_000) { (rig.lastZoom() ?: 1f) < 1f }
        val shown = onNodeWithTag("browser-viewport").fetchSemanticsNode().size.width / density.density
        assertTrue(shown < 768f, "drawn smaller than the pane: $shown")
        assertEquals(768f, shown / rig.lastZoom()!!, 2f)
        onNodeWithTag("browser-device-fit").performClick()
        onNodeWithTag("browser-device-fit-100").performClick()
        onNodeWithTag("browser-viewport").assertWidthIsEqualTo(768.dp)
        waitUntil(timeoutMillis = 5_000) { rig.lastZoom() == 1f }
        onNodeWithTag("browser-device-close").performClick()
        onNodeWithTag("browser-viewport").assertWidthIsEqualTo(800.dp)
    }

    /** No page zoom (Android's is text zoom; Windows, Linux and the web have none yet): no Fit, and the device pans at 100 %. */
    @Test fun withoutAPageZoomThereIsNoFitAndTheDevicePans() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions(), FakePlatform())
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        onNodeWithTag("browser-device").performClick()
        onNodeWithTag("browser-device-preset").performClick()
        onNodeWithTag("browser-device-ipad-mini").performClick()
        onNodeWithTag("browser-device-fit").assertDoesNotExist()
        onNodeWithTag("browser-viewport").assertWidthIsEqualTo(768.dp)
        assertEquals(null, rig.lastZoom())
    }

    private fun Rig.lastEmulation(): String? = calls.lastOrNull { it.startsWith("emulate:") }?.substringAfter(':')

    /**
     * #122 2b on Windows: an engine that emulates devices (WebView2's DevTools protocol) gets the device itself: its CSS
     * size, pixel ratio, mobile viewport and touch, drawn at the stage's scale; DPR and Touch are in the bar; off is off.
     */
    @Test fun anEngineThatEmulatesGetsTheDeviceWithItsRatioAndTouch() = runComposeUiTest {
        val rig = Rig(FakeEngineBehavior(emulatesDevices = true))
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions(), FakePlatform())
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        onNodeWithTag("browser-device-dpr").assertDoesNotExist()
        onNodeWithTag("browser-device").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.lastEmulation()?.startsWith("393x852@3.0 mobile=true touch=true") == true }
        onNodeWithTag("browser-device-fit").assertExists() // the engine scales, so a big device fits here too
        onNodeWithTag("browser-device-dpr").performClick()
        onNodeWithTag("browser-device-dpr-2").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.lastEmulation()?.startsWith("393x852@2.0") == true }
        onNodeWithTag("browser-device-touch").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.lastEmulation()?.contains("touch=false") == true }
        onNodeWithTag("browser-device-close").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.lastEmulation() == "none" }
        assertEquals(null, rig.lastZoom(), "the engine scales: the page's zoom is never used for the fit")
    }

    /** Most engines can't: no DPR or Touch at all (the hide rule). */
    @Test fun anEngineThatCantEmulateShowsNoRatioOrTouch() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions(), FakePlatform())
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        onNodeWithTag("browser-device").performClick()
        onNodeWithTag("browser-device-bar").assertExists()
        waitForIdle()
        onNodeWithTag("browser-device-dpr").assertDoesNotExist()
        onNodeWithTag("browser-device-touch").assertDoesNotExist()
    }

    /** The picker's page: a Pay button in the middle of the viewport (as the engine's evaluate answers), or gone. */
    private fun Rig.answerPicks(gone: () -> Boolean = { false }) {
        val pay = """{"selector":"button.buy","tag":"button","text":"Pay €42","label":"button.buy · 312×44","box":{"x":0.25,"y":0.5,"w":0.5,"h":0.1}}"""
        engines.created.toList().forEach { engine ->
            engine.evaluateAnswer = { script ->
                when {
                    "elementFromPoint" in script -> pay
                    "querySelector(q)" in script -> if (gone()) "[null]" else """[{"x":0.25,"y":0.5,"w":0.5,"h":0.1}]"""
                    else -> null
                }
            }
        }
    }

    /**
     * #122 3a: ⌘⇧C is pick mode. Hovering names the element; a click pins it ① with a comment field there; Return keeps
     * the comment; Esc closes the field, then pick mode; the pin stays.
     */
    @Test fun pickModePinsAnElementWithAComment() = runComposeUiTest {
        val rig = Rig()
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        rig.answerPicks()
        onNodeWithTag("browser-pick-layer").assertDoesNotExist()
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performKeyInput { withKeyDown(mod) { withKeyDown(Key.ShiftLeft) { pressKey(Key.C) } } }
        onNodeWithTag("browser-pick-strip").assertExists()
        onNodeWithTag("browser-pick-layer").performMouseInput { moveTo(center) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("browser-pick-hover", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("button.buy · 312×44", useUnmergedTree = true).assertExists()
        onNodeWithTag("browser-pick-layer").performMouseInput { click(center) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("browser-pin-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("browser-pick-comment").performTextInput("the Pay button is cut off")
        onNodeWithTag("browser-pick-comment").performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("browser-pick-comment").assertDoesNotExist()
        onNodeWithTag("browser-pick-sendbar").assertExists()
        onNodeWithText("1 comment", useUnmergedTree = true).assertExists()
        // Esc leaves pick mode; the pin stays.
        onNodeWithTag("browser-pick-layer").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithTag("browser-pick-layer").assertDoesNotExist()
        onNodeWithTag("browser-pin-1", useUnmergedTree = true).assertExists()
    }

    /** A pin follows its element; one whose element is gone greys out ("element changed") and stays. */
    @Test fun aPinWhoseElementIsGoneGreysOut() = runComposeUiTest {
        val rig = Rig()
        var gone = false
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        rig.answerPicks { gone }
        onNodeWithTag("browser-pick").performClick()
        onNodeWithTag("browser-pick-layer").performMouseInput { click(center) }
        onNodeWithTag("browser-pick-comment").performTextInput("x")
        onNodeWithTag("browser-pick-comment").performKeyInput { pressKey(Key.Enter) }
        gone = true
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("browser-pin-1-gone", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("browser-pick-clear").performClick()
        onNodeWithTag("browser-pin-1-gone", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("browser-pick-sendbar").assertDoesNotExist()
    }

    /** Picks a Pay button and comments [words] on it. */
    private fun ComposeUiTest.pickPay(rig: Rig, words: String) {
        rig.answerPicks()
        onNodeWithTag("browser-pick").performClick()
        onNodeWithTag("browser-pick-layer").performMouseInput { click(center) }
        onNodeWithTag("browser-pick-comment").performTextInput(words)
        onNodeWithTag("browser-pick-comment").performKeyInput { pressKey(Key.Enter) }
    }

    /**
     * #122 3b: Send posts the comments to the workspace's chat at once (Q4): one message in the design's words with each
     * element's picture; the pins are done with then, and pick mode ends.
     */
    @Test fun sendPostsTheCommentsWithTheirPictures() = runComposeUiTest {
        val rig = Rig()
        val sink = ChatSink()
        showWithChat(rig, mutableStateOf(view("https://vitejs.dev/")), sink)
        pickPay(rig, "the Pay button is cut off")
        onNodeWithText("1 comment → Checkout agent", useUnmergedTree = true).assertExists()
        onNodeWithTag("browser-pick-send").performClick()
        waitUntil(timeoutMillis = 5_000) { sink.messages.isNotEmpty() }
        val (text, images, send) = sink.messages.single()
        assertTrue(send, "posted at once")
        assertTrue(text.startsWith("**1 browser comment** · https://vitejs.dev/"), text)
        assertTrue("### ① \"the Pay button is cut off\"" in text && "- element: `button.buy` · text \"Pay €42\"" in text, text)
        assertTrue("- picture: comment-1.png" in text, text)
        assertEquals(listOf("comment-1.png"), images.map { it.first })
        assertTrue(images.single().second.isPng(), "a PNG")
        waitForIdle()
        onNodeWithTag("browser-pick-sendbar").assertDoesNotExist()
        onNodeWithTag("browser-pin-1", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("browser-pick-layer").assertDoesNotExist()
    }

    /** "Add to message" (⌥Send in the design) leaves them in the chat's composer, unsent. */
    @Test fun addToMessageLeavesThemInTheComposer() = runComposeUiTest {
        val rig = Rig()
        val sink = ChatSink()
        showWithChat(rig, mutableStateOf(view("https://vitejs.dev/")), sink)
        pickPay(rig, "x")
        onNodeWithTag("browser-pick-compose").performClick()
        waitUntil(timeoutMillis = 5_000) { sink.messages.isNotEmpty() }
        assertEquals(false, sink.messages.single().third)
    }

    /** With no chat to take them, there's no Send at all, only the count and Clear. */
    @Test fun withNoChatThereIsNoSend() = runComposeUiTest {
        val rig = Rig()
        showWithChat(rig, mutableStateOf(view("https://vitejs.dev/")), sink = null)
        pickPay(rig, "x")
        onNodeWithTag("browser-pick-sendbar").assertExists()
        onNodeWithTag("browser-pick-send").assertDoesNotExist()
        onNodeWithTag("browser-pick-compose").assertDoesNotExist()
    }

    /** No scripts into the page (the web client's iframe): no pick mode at all. */
    @Test fun withoutPagePickingThereIsNoPickButton() = runComposeUiTest {
        val rig = Rig()
        show(rig, mutableStateOf(view("https://vitejs.dev/")), rig.actions(), FakePlatform(caps = dev.supermux.ui.platform.NO_CAPS.copy(pagePick = false)))
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        onNodeWithTag("browser-pick").assertDoesNotExist()
    }

    /** On a phone a phone-sized page inside it is pointless: no device button and no menu row (#122 §3.2). */
    @Test fun aPhoneHasNoDeviceMode() = runComposeUiTest {
        val rig = Rig()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20, factory = rig.engines, scope = scope, clock = { mainClock.currentTime }),
        )
        setPlatformContent {
            CompositionLocalProvider(
                LocalAppWebViews provides AppWebViews(runtime),
                dev.supermux.ui.adaptive.LocalWindowWidthClass provides dev.supermux.ui.adaptive.WindowWidthClass.Compact,
                dev.supermux.ui.adaptive.LocalPointerAvailable provides false,
            ) {
                BrowserPane(view("https://vitejs.dev/"), "w1", rig.actions(), Modifier.size(390.dp, 700.dp))
            }
        }
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        onNodeWithTag("browser-device").assertDoesNotExist()
        onNodeWithTag("browser-menu").performClick()
        onNodeWithTag("browser-menu-device").assertDoesNotExist()
    }

    /** #122 2c: on a phone the sheet's "Desktop site" asks the page for its desktop site, and again for the phone's. */
    @Test fun aPhoneAsksForTheDesktopSite() = runComposeUiTest {
        val rig = Rig()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20, factory = rig.engines, scope = scope, clock = { mainClock.currentTime }),
        )
        setPlatformContent {
            CompositionLocalProvider(
                LocalAppWebViews provides AppWebViews(runtime),
                dev.supermux.ui.adaptive.LocalWindowWidthClass provides dev.supermux.ui.adaptive.WindowWidthClass.Compact,
                dev.supermux.ui.adaptive.LocalPointerAvailable provides false,
            ) {
                BrowserPane(view("https://vitejs.dev/"), "w1", rig.actions(), Modifier.size(390.dp, 700.dp))
            }
        }
        waitUntil(timeoutMillis = 5_000) { "https://vitejs.dev/" in rig.loads() }
        assertEquals(false, rig.live()?.lastSetup?.desktopSite)
        onNodeWithTag("browser-menu").performClick()
        onNodeWithTag("browser-menu-desktop-site").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.live()?.lastSetup?.desktopSite == true }
        onNodeWithTag("browser-menu").performClick()
        onNodeWithTag("browser-menu-desktop-site").performClick()
        waitUntil(timeoutMillis = 5_000) { rig.live()?.lastSetup?.desktopSite == false }
    }

    /** A desktop is a desktop already: no "Desktop site" in its menu. */
    @Test fun aDesktopHasNoDesktopSiteRow() = runComposeUiTest {
        val rig = Rig()
        showWithKeys(rig, mutableStateOf(view("https://vitejs.dev/")))
        onNodeWithTag("browser-menu").performClick()
        onNodeWithTag("browser-menu-find").assertExists()
        onNodeWithTag("browser-menu-desktop-site").assertDoesNotExist()
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
        rig.engines.created.toList().forEach { it.evaluateAnswer = { "\"https://vitejs.dev/logo.svg\"" } }
        onNodeWithTag("browser-address").performClick()
        onNodeWithTag("browser-address").performTextReplacement("vitejs.dev")
        onNodeWithTag("browser-address").performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { icons["b1"] == "https://vitejs.dev/logo.svg" }
    }
}
