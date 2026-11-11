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
        override fun sendImage(png: ByteArray, name: String): Boolean { sent += png to name; return true }
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
