package dev.supermux.webview.sample.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.webview.Activation
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebSettings
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.WebViewState
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.sample.linux.Check
import dev.supermux.webview.sample.linux.CheckBundle
import dev.supermux.webview.sample.linux.CheckClient
import dev.supermux.webview.sample.linux.CheckHost
import dev.supermux.webview.sample.linux.CheckScope
import dev.supermux.webview.sample.linux.LinuxChecks
import dev.supermux.webview.sample.linux.LinuxChecks.adopt
import dev.supermux.webview.sample.linux.LinuxChecks.eventually
import dev.supermux.webview.sample.linux.LinuxChecks.live
import dev.supermux.webview.sample.linux.LinuxChecks.park
import dev.supermux.webview.sample.linux.LinuxChecks.probe
import dev.supermux.webview.sample.linux.LinuxChecks.solidPage
import dev.supermux.webview.sample.linux.LinuxChecks.start
import dev.supermux.webview.sample.linux.LocalServer
import dev.supermux.webview.sample.linux.SURFACE_HEIGHT
import dev.supermux.webview.sample.linux.SURFACE_WIDTH
import dev.supermux.webview.sample.linux.echoPage
import dev.supermux.webview.sample.linux.hostEvent
import dev.supermux.webview.sample.linux.isPong
import dev.supermux.webview.sample.linux.isReady
import dev.supermux.webview.sample.linux.nonceOf
import dev.supermux.webview.sample.linux.page
import dev.supermux.webview.sample.linux.runChecks
import dev.supermux.webview.sample.linux.setupFor
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.system.exitProcess
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Windows engine's conformance checks: Linux's shared ones (the same contract, the same names), without those that
 * check WPE itself, and Windows' own. Run by [windowsChecksMain] (LinuxCheckRunner's runner, Windows' input).
 */
object WindowsChecks {
    /**
     * Linux checks of WPE's own behaviour, or whose assertions name WebKit, or whose steps Chromium takes otherwise (a
     * history entry a script adds with no user activation is one the back button skips, so CanGoBack ignores it);
     * Windows has its own of each where it applies.
     */
    private val WPE_ONLY = setOf(
        "a-bundle-is-served-on-its-scheme-and-reported-as-its-origin",   // → a-bundle-is-served-on-its-https-origin
        "a-select-opens-its-menu-and-takes-the-choice",                 // → a-select-opens-its-list-and-takes-the-choice
        "restore-brings-back-a-bundles-history",                        // → restore-brings-back-the-page-it-was-on
        "a-persistent-profiles-cookies-are-written-to-disk",            // WebKit's cookie file
        "a-second-process-of-the-app-gets-no-profile-the-first-has",    // → a-second-process-of-the-app-shares-its-profiles
        "pages-run-in-webkits-sandbox-unless-it-is-off",
        "an-avif-image-decodes",
        "page-scripts-cannot-reach-the-channel",                        // names window.webkit → Windows' own below
        "a-hung-pages-process-is-gone-once-released",                   // reads /proc → Windows' own below
        "a-reset-keeps-no-hung-page",                                   // reads /proc → Windows' own below
        "a-page-that-stops-answering-is-ended-and-reported-crashed",    // reads /proc → Windows' own below
        "a-parked-page-is-adopted-live-with-its-state",                 // a script's pushState → Windows' own below (by a click)
    )

    private val shared: List<Check> = LinuxChecks.all.filter { it.name !in WPE_ONLY }

    val all: List<Check> get() = shared + bridge + focus + popups + ime + lessons + keepAlive + questions

    /** A focus scene of [engine]'s, its places on screen measured from this check's window. */
    private fun CheckScope.focusScene(engine: WebEngine, script: String = "") = FocusScene(engine, script, ::contentOrigin)

    /** The bridge on WebView2: the receiver, the secret, the patched page (D5, L31). */
    private val bridge: List<Check> = listOf(
        Check("page-scripts-cannot-reach-the-channel") {
            val engine = engine()
            live(engine, echoPage)
            // The channel the shim took is gone; the receiver exists but answers only its own origin.
            assertEquals("\"undefined\"", engine.evaluate("typeof Object.getOwnPropertyNames(window).filter(function (n) { return n.indexOf('__wv_c') === 0; })[0]"))
            assertEquals("\"object\"", engine.evaluate("typeof hostBridge"))
            // A page's own postMessage, a thousand times, reaches nothing: no secret, no upcall.
            val events = record(engine)
            engine.evaluate("for (var i = 0; i < 1000; i++) chrome.webview.postMessage('{\"t\":\"event\",\"n\":\"forged\"}'); 1")
            events.expectNone("a forged message", from = 0) { it is EngineEvent.PageMessage && "forged" in it.json }
        },
        Check("a-patched-postmessage-changes-nothing") {
            val engine = engine()
            val patch = "chrome.webview.postMessage = function () { window.__stolen = (window.__stolen || 0) + 1; };" +
                "String.prototype.indexOf = function () { return 0; }; Array.prototype.indexOf = function () { return 0; };" +
                "Math.min = function () { return Infinity; };"
            val events = live(engine, page("patched", script = patch + "hostBridge.on('ping', function (d) { hostBridge.post('pong', d); });"))
            val ready = events.events.first(::isReady) as EngineEvent.PageMessage
            engine.postMessage(hostEvent("ping", 3, nonceOf(ready)), ready.origin)
            events.await("pong") { isPong(it, 3) }
            assertEquals("null", engine.evaluate("window.__stolen === undefined ? null : window.__stolen"), "the shim's own channel was captured first")
        },
        Check("a-bundle-is-served-on-its-https-origin") {
            val bundle = CheckBundle("plugin-check")
            val engine = engine()
            val events = live(engine, bundle.content)
            val ready = events.events.first(::isReady) as EngineEvent.PageMessage
            assertEquals("https://plugin-check.invalid", ready.origin)
            assertEquals("\"https://plugin-check.invalid|true\"", engine.evaluate("self.origin + '|' + isSecureContext"))
            engine.evaluate(
                "window.__s = null; Promise.all([fetch('data.json'), fetch('/%2e%2e%2fsecret'), fetch('/..%5csecret')])" +
                    ".then(function (r) { window.__s = r.map(function (x) { return x.status; }).join('|'); }); 1",
            )
            eventually("the three statuses") { engine.evaluate("window.__s") == "\"200|404|404\"" }
            assertTrue(bundle.asked.none { ".." in it || '\\' in it }, "asked: ${bundle.asked}")
        },
        Check("restore-brings-back-the-page-it-was-on") {
            val bundle = CheckBundle("plugin-restore")
            val first = engine()
            first.client = CheckClient(navigation = NavigationDecision.Allow)
            val events = live(first, bundle.content)
            first.evaluate("document.getElementById('next').click(); 1")
            events.await("page 2") { it == EngineEvent.UrlChanged("https://plugin-restore.invalid/page2.html") }
            val state = assertNotNull(first.saveState())
            val second = engine()
            val restored = record(second)
            second.configure(setupFor(bundle.content))
            assertFalse(second.restoreState(state, CheckBundle("plugin-other").content), "another content's state")
            assertTrue(second.restoreState(state, bundle.content))
            restored.await("the restored page") { it == EngineEvent.UrlChanged("https://plugin-restore.invalid/page2.html") }
        },
    )

    /** The test's focus matrix (W4), as checks on the engine: F1–F5 and F8a/F8b, with D15's changes. */
    private val focus: List<Check> = listOf(
        Check("a-click-moves-the-keyboard-into-the-page-and-a-click-on-compose-takes-it-back") {
            val scene = focusScene(engine())
            compose { scene.Content() }
            scene.typeIntoPage(this, "abc")
            assertEquals("\"abc\"", scene.pageValue())
            clickOn(scene.fieldA)
            type("xyz")
            eventually("Compose A has it") { scene.a == "xyz" }
            assertEquals("\"abc\"", scene.pageValue(), "nothing typed for Compose reached the page")
        },
        Check("twenty-round-trips-lose-no-key", timeoutMillis = 120_000) {
            val scene = focusScene(engine())
            compose { scene.Content() }
            scene.awaitPainted()
            repeat(20) {
                clickOn(scene.input)
                type("q")
                clickOn(scene.fieldA)
                type("w")
            }
            eventually("every key where it was typed") { scene.pageValue() == "\"" + "q".repeat(20) + "\"" && scene.a == "w".repeat(20) }
        },
        Check("tab-enters-the-page-and-leaves-it-both-ways") {
            val scene = focusScene(engine())
            compose { scene.Content() }
            scene.awaitPainted()
            clickOn(scene.fieldA)
            keys("Tab")
            eventually("the page's first element") { scene.engine.evaluate("document.activeElement.id") == "\"in1\"" }
            repeat(FocusScene.PAGE_ELEMENTS) { keys("Tab") }
            type("u")
            eventually("out to Compose B") { scene.b.endsWith("u") }
            keys("shift+Tab")
            eventually("back in at the last") { scene.engine.evaluate("document.activeElement.id") == "\"in${FocusScene.PAGE_ELEMENTS}\"" }
        },
        Check("reactivation-keeps-the-pages-keyboard") {
            // F5: another window takes activation and gives it back; the page still has the keys.
            val scene = focusScene(engine())
            compose { scene.Content() }
            scene.typeIntoPage(this, "d")
            val decoy = withContext(Dispatchers.Main) { javax.swing.JFrame("decoy").apply { setBounds(760, 100, 260, 160); isVisible = true } }
            try {
                delay(400)
                clickScreen(890, 180)
                delay(400)
                clickTitleBar()
                delay(400)
                type("e")
                eventually("the page has 'de'") { scene.pageValue() == "\"de\"" }
            } finally {
                withContext(Dispatchers.Main) { decoy.dispose() }
            }
        },
        Check("copy-in-the-page-paste-in-compose") {
            val scene = focusScene(engine())
            compose { scene.Content() }
            scene.typeIntoPage(this, "x")
            scene.engine.evaluate("document.getElementById('in1').value = 'copyme'; 1")
            keys("ctrl+a", "ctrl+c")
            clickOn(scene.fieldB)
            keys("ctrl+v")
            eventually("pasted") { scene.b == "copyme" }
        },
        Check("an-app-shortcut-reaches-the-app-and-not-the-page") {
            // F8b and D15: Ctrl+Shift+K is the app's (an onPreviewKeyEvent around the WebView); the page never sees it.
            val scene = focusScene(engine())
            compose { scene.Content() }
            scene.typeIntoPage(this, "x")
            scene.engine.evaluate("window.__k = 0; addEventListener('keydown', function (e) { if (e.key === 'K' || e.key === 'k') window.__k++; }); 1")
            keys("ctrl+shift+k")
            eventually("the app took it") { scene.shortcuts == 1 }
            assertEquals("0", scene.engine.evaluate("window.__k"))
        },
        Check("a-demo-cannot-take-the-keyboard-from-the-app") {
            // L32: a page focusing its input on a timer while the user types in Compose.
            val scene = focusScene(engine(), script = "setInterval(function () { document.getElementById('in1').focus(); }, 150);")
            compose { scene.Content() }
            scene.awaitPainted()
            clickOn(scene.fieldA)
            type("the quick brown fox jumps over the lazy dog")
            eventually("every key in Compose") { scene.a == "the quick brown fox jumps over the lazy dog" }
            assertEquals("\"\"", scene.pageValue(), "nothing typed for Compose reached the page")
        },
    )

    /** WebView2's own popups (W6) and the cursor (W8). */
    private val popups: List<Check> = listOf(
        Check("a-select-opens-its-list-and-takes-the-choice") {
            val engine = engine()
            val select = "<select id=s style='position:fixed;left:10px;top:10px;width:200px;height:30px;font-size:16px'>" +
                "<option value=a>Apple</option><option value=b>Banana</option><option value=c>Cherry</option></select>"
            live(engine, solidPage("select", "#ffffff", body = select))
            val before = popupWindows()
            click(100, 25)
            eventually("WebView2's list window") { popupWindows().keys.any { it !in before } }
            keys("Down", "Down", "Return")
            eventually("Cherry") { engine.evaluate("document.getElementById('s').value") == "\"c\"" }
        },
        Check("a-select-is-placed-right-after-a-window-height-change") {
            // L26: the window's height changes; the select's list still opens right under it.
            val engine = engine()
            live(engine, solidPage("select", "#ffffff", body = "<select id=s style='position:fixed;left:10px;top:10px;width:200px;height:30px'><option>a</option><option>b</option></select>"))
            resizeWindowHeightBy(-150)
            try {
                delay(500)
                val before = popupWindows()
                click(100, 25)
                eventually("the list") { popupWindows().keys.any { it !in before } }
                // WebView2 opens the list in two windows of one place, the browser's and one in the app's process: both.
                val lists = popupWindows().filterKeys { it !in before }.values
                val select = surfaceRectOnScreen(10, 10, 200, 30)
                for (list in lists) assertTrue(abs(list.y - (select.y + select.height)) <= 8, "list at ${list.y}, select ends at ${select.y + select.height} (opened: $lists)")
                keys("Escape")
            } finally {
                resizeWindowHeightBy(150)
            }
        },
        Check("the-cursor-follows-the-page") {
            val engine = engine()
            // The test's expectations (wintest CursorMapping.expectedFor, W8): IDC_NO has no AWT cursor, so it is drawn
            // from its own pixels, a custom cursor to the OS.
            val cells = listOf("pointer" to "IDC_HAND", "text" to "IDC_IBEAM", "crosshair" to "IDC_CROSS", "not-allowed" to "custom")
            val body = cells.withIndex().joinToString("") { (i, c) -> "<div style='position:fixed;left:0;top:${i * 60}px;width:100%;height:60px;cursor:${c.first}'></div>" }
            live(engine, solidPage("cursors", "#ffffff", body = body))
            for ((i, c) in cells.withIndex()) {
                move(100, i * 60 + 30)
                eventually("the ${c.first} cursor") { WindowsInput.cursorInfo().startsWith(c.second + "|") }
            }
        },
    )

    /**
     * The Microsoft IME (Japanese) on both sides of the hand-off (W9; the gate's F7a–c), on the VM's disk since the GPU
     * gate. It composes from keys, so "nihon" goes in as scan codes. Layouts are per thread: each check switches the
     * focused side's thread, and always switches it back with the IME off, or later checks would type Japanese.
     */
    private val ime: List<Check> = listOf(
        Check("the-ime-composes-in-the-page") {
            val scene = focusScene(engine(), script = COMPOSITION_LOG)
            compose { scene.Content() }
            scene.typeIntoPage(this, "x")
            keys("ctrl+a", "backspace")
            eventually("in1 empty") { scene.pageValue() == "\"\"" }
            japanese {
                typeNihon { scene.pageValue() }
                pressVk(VK_SPACE)
                pressVk(VK_RETURN)
                eventually("Japanese in the page") { hasJapanese(scene.pageValue()) }
                assertEquals("true", scene.engine.evaluate("window.__comp.indexOf('compositionstart') >= 0 && window.__comp.indexOf('compositionend') >= 0"))
            }
        },
        Check("the-ime-composes-in-compose") {
            val scene = focusScene(engine())
            compose { scene.Content() }
            scene.awaitPainted()
            clickOn(scene.fieldA)
            japanese {
                typeNihon { scene.a }
                pressVk(VK_SPACE)
                pressVk(VK_RETURN)
                eventually("Japanese in Compose A") { hasJapanese(scene.a) }
            }
        },
        Check("the-ime-candidate-list-sits-under-the-pages-input") {
            // Windows 11's IME shows its list in a window that already exists (the gate's F7b): judged by where a
            // window appears or moves to when the list opens, not by a new window.
            val scene = focusScene(engine())
            compose { scene.Content() }
            scene.typeIntoPage(this, "x")
            keys("ctrl+a", "backspace")
            eventually("in1 empty") { scene.pageValue() == "\"\"" }
            japanese {
                typeNihon { scene.pageValue() }
                pressVk(VK_SPACE)
                val input = scene.input
                // Where the list may open: around the input and well under it.
                val area = Rectangle(maxOf(0, input.x - 120), maxOf(0, input.y - 40), input.width + 320, input.height + 400)
                val before = capture(area)
                pressVk(VK_SPACE)                                   // the second Space opens the candidate list
                delay(800)
                val after = capture(area)
                screenshot("ime-candidates")
                pressVk(VK_ESCAPE)
                pressVk(VK_ESCAPE)
                // What appeared, the input's own text aside: the list, whose top edge is at the input's bottom.
                val changed = changedBounds(before, after, area, except = Rectangle(input.x - 4, input.y - 4, input.width + 8, input.height + 8))
                assertNotNull(changed, "nothing appeared around the input at $input")
                val bottom = input.y + input.height
                assertTrue(
                    changed.y in (bottom - 12)..(bottom + 30) && abs(changed.x - input.x) <= 40 && changed.height >= 60,
                    "the candidate list at $changed, the input at $input",
                )
            }
        },
    )

    private const val COMPOSITION_LOG =
        "window.__comp = []; ['compositionstart', 'compositionend'].forEach(function (t) { " +
            "document.getElementById('in1').addEventListener(t, function () { window.__comp.push(t); }); });"
    private val NIHON = intArrayOf(0x31, 0x17, 0x23, 0x18, 0x31)   // the keys n, i, h, o, n (the wintest's)
    private const val VK_IME_ON = 0x16
    private const val VK_IME_OFF = 0x1A
    private const val VK_SPACE = 0x20
    private const val VK_RETURN = 0x0D
    private const val VK_ESCAPE = 0x1B
    private const val VK_BACK = 0x08

    private fun hasJapanese(text: String) = text.any { it in '぀'..'ヿ' || it in '一'..'鿿' }

    /** Japanese with the Microsoft IME for the focused thread, [body], then the IME off and en-US again, whatever happened. */
    private suspend fun CheckScope.japanese(body: suspend () -> Unit) {
        val layout = withContext(Dispatchers.IO) { WindowsInput.setInputLanguage("00000411") }
        delay(800)
        try {
            body()
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message} (layout $layout)")
        } finally {
            withContext(Dispatchers.IO) {
                WindowsInput.sendVk(VK_IME_OFF, true)
                WindowsInput.sendVk(VK_IME_OFF, false)
                WindowsInput.imeControl(0, -1)
                WindowsInput.setInputLanguage("00000409")
            }
            delay(400)
        }
    }

    /**
     * The IME on (VK_IME_ON; if that typed plain "nihon" into [value]'s field, the IME opened in hiragana mode instead, the
     * gate's fallback), then "nihon" as keys.
     */
    private suspend fun CheckScope.typeNihon(value: suspend () -> String) {
        suspend fun keysOfNihon() = withContext(Dispatchers.IO) {
            for (scan in NIHON) {
                WindowsInput.sendScan(scan, true, false); Thread.sleep(15)
                WindowsInput.sendScan(scan, false, false); Thread.sleep(30)
            }
        }
        pressVk(VK_IME_ON)
        delay(400)
        keysOfNihon()
        delay(400)
        if (value().contains("nihon")) {                          // the toggle didn't take
            keys("ctrl+a"); pressVk(VK_BACK)
            withContext(Dispatchers.IO) { WindowsInput.imeControl(1, 0x09) }   // open, IME_CMODE_NATIVE | FULLSHAPE
            delay(400)
            keysOfNihon()
            delay(400)
        }
    }

    /** The review lessons that need real WebView2 (L1–L28, D8, D17), each as the plan's table words it. */
    private val lessons: List<Check> = listOf(
        Check("a-redirect-of-the-apps-own-load-is-followed") {
            // L1: the app's own load is followed through a server redirect, to http(s), without asking the client.
            val engine = engine()
            val client = CheckClient(navigation = NavigationDecision.Block)
            engine.client = client
            val events = record(engine)
            show(engine)
            start(engine, WebContent.Url("${LocalServer.origin}/redirect"))
            events.await("the redirect's target") { it == EngineEvent.UrlChanged("${LocalServer.origin}/page") }
            assertTrue(client.asked.none { it is NavigationRequest }, "the client was asked: ${client.asked}")
        },
        Check("a-scripted-navigation-after-a-wheel-is-no-gesture") {
            // L2: a wheel is no user gesture: a navigation a page starts after one isn't the user's. A click on a link is.
            val engine = engine()
            val client = CheckClient(navigation = NavigationDecision.Block)
            engine.client = client
            val target = "${LocalServer.origin}/page"
            val link = "<a id=l href='$target' style='position:fixed;left:0;top:0;width:100%;height:60px;display:block'>link</a>"
            val script = "addEventListener('wheel', function () { setTimeout(function () { location.href = '$target?wheel'; }, 200); }, { once: true });"
            live(engine, solidPage("gesture", "#ffffff", body = "$link<div style='height:3000px'></div>", script = script))
            wheel(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2, 2)
            eventually("the wheel's navigation asked about") { client.asked.any { it is NavigationRequest && it.url == "$target?wheel" } }
            val scripted = client.asked.filterIsInstance<NavigationRequest>().first { it.url == "$target?wheel" }
            assertFalse(scripted.isUserGesture, "a navigation a timer started after a wheel: $scripted")
            click(SURFACE_WIDTH / 2, 30)
            eventually("the link's navigation asked about") { client.asked.any { it is NavigationRequest && it.url == target } }
            val clicked = client.asked.filterIsInstance<NavigationRequest>().first { it.url == target }
            assertTrue(clicked.isUserGesture, "a click on a link: $clicked")
        },
        Check("a-retry-after-a-failed-load-loads-the-content") {
            // L3: a load that never committed is loaded again by reload(): a second error, never the floor.
            val engine = engine()
            val events = record(engine)
            show(engine)
            start(engine, WebContent.Url("https://nowhere.invalid/"))
            events.await("the load's error", millis = 20_000) { it is EngineEvent.Error }
            val before = events.events.size
            engine.reload()
            events.await("the reload's error", from = before, millis = 20_000) { it is EngineEvent.Error }
            val floor = events.events.filterIsInstance<EngineEvent.UrlChanged>().filter { it.url?.contains("floor.invalid") == true }
            assertTrue(floor.isEmpty(), "the floor was reported: $floor")
        },
        Check("an-engine-made-right-after-the-last-ones-release-is-quick") {
            // An app's last engine released, another made 300 ms later, then a second as the browser's exit is told: each
            // in under 3 s. WebView2 starts a browser for the folder only once the last one has exited, and a page that
            // went right after its load left a SmartScreen check that held that exit 6 s; an environment opened again
            // after the exit made the second page wait for the first's browser (run 8's "phase Cold" for 10 s).
            for ((round, after) in listOf(0L, 150L, 300L, 600L, 1_000L).withIndex()) {
                val last = engine()
                val events = record(last)
                start(last, page("brief-$round"))
                events.await("the commit") { it is EngineEvent.UrlChanged }
                delay(after)
                last.release()
                delay(300)   // the browser goes, with nothing of the app's left: an engine made now needs another
                val (first, firstMs) = timed { engine() }
                delay(600)
                val (second, secondMs) = timed { engine() }
                assertTrue(firstMs < 3_000 && secondMs < 3_000, "released $after ms after its commit: made in $firstMs and $secondMs ms")
                first.release()
                second.release()
                delay(1_500)
            }
        },
        Check("an-app-started-offline-shows-the-error") {
            // L3: a view whose page fails shows the error (live, no cover), within 10 s.
            val stage = ViewStage(WebContent.Url("https://nowhere.invalid/page"))
            compose { stage.Scene() }
            try {
                stage.waitFor("the error, live", 10_000) { stage.state.phase == WebViewPhase.Live && stage.state.error != null }
            } finally {
                compose(null)
                delay(300)
                stage.close()
            }
        },
        Check("reset-mid-load-succeeds") {
            // L4: a reset while the page is still loading (the server holds it 5 s).
            val engine = engine()
            val events = record(engine)
            show(engine)
            start(engine, WebContent.Url("${LocalServer.origin}/held"))
            events.await("the load's start") { it is EngineEvent.Progress }
            delay(300)
            assertTrue(engine.reset(), "reset during a load")
        },
        Check("a-hung-pages-process-is-gone-once-released") {
            // L6, V10: release ends a hung page's renderer.
            val engine = engine()
            live(engine, page("hung"))
            val spinning = hang(engine)
            engine.release()
            delay(2_000)
            val after = renderers()
            assertTrue(spinning !in after, "the hung page's renderer $spinning still runs 2 s after release (renderers: $after)")
        },
        Check("a-reset-keeps-no-hung-page") {
            // L6: a reset finds the page silent and ends its renderer; it fails, and the pool releases the engine at once,
            // as here. Ended by CDP's Page.crash alone, the renderer took 0.9 to over 2 s to go (Windows' crash handling);
            // released, its controller closed, the browser ends it.
            val engine = engine()
            live(engine, page("hung"))
            val spinning = hang(engine)
            val reused = engine.reset()
            if (!reused) engine.release()
            eventually("the hung page's renderer gone after the reset (reused: $reused)", millis = 2_000) { !alive(spinning) }
        },
        Check("a-page-that-stops-answering-is-ended-and-reported-crashed") {
            // WebView2 finds the renderer unresponsive to input (or the heartbeat finds it silent): the engine ends it
            // and reports a crash, as Android's does.
            val engine = engine()
            val events = live(engine, page("stuck"))
            val spinning = hang(engine)
            click(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            events.await("the crash", millis = 12_000) { it == EngineEvent.Crashed }
            assertFalse(engine.isUsable)
            eventually("the stuck page's renderer gone", millis = 2_000) { !alive(spinning) }
        },
        Check("each-engine-keeps-its-own-theme") {
            // L17: dark mode per engine (DevTools emulation), not per process or profile.
            val dark = engine()
            val light = engine()
            val content = page("theme")
            val darkEvents = record(dark)
            dark.configure(setupFor(content).copy(dark = true))
            dark.load(content)
            val lightEvents = record(light)
            light.configure(setupFor(content).copy(dark = false))
            light.load(content)
            darkEvents.await("the dark one's commit") { it == EngineEvent.UrlChanged(content.origin.url) }
            lightEvents.await("the light one's commit") { it == EngineEvent.UrlChanged(content.origin.url) }
            val query = "matchMedia('(prefers-color-scheme: dark)').matches"
            eventually("dark in the dark one") { dark.evaluate(query) == "true" }
            eventually("light in the light one") { light.evaluate(query) == "false" }
            assertEquals("true", dark.evaluate(query), "the dark one still dark")
        },
        Check("a-second-process-of-the-app-shares-its-profiles") {
            // D8: WebView2 serves one user data folder from one browser process: a second process of the app gets the
            // same named profile (Linux's exclusive lock is deliberately not Windows').
            val engine = engine(WebProfile.Named("shared-check"))
            live(engine, page("first"))
            assertEquals("PROBE Created", probe("shared-check", app = null))
        },
        Check("two-processes-never-share-an-ephemeral-store") {
            // D8, L12: ephemeral slots are locked across processes: a second process's ephemeral engine has its own store.
            val engine = engine(WebProfile.Ephemeral)
            live(engine, PROBE_PAGE)
            engine.evaluate("localStorage.setItem('x', 'here'); 1")
            assertEquals("\"here\"", engine.evaluate("localStorage.getItem('x')"))
            assertEquals("PROBE Created x=null", probe("ephemeral", app = null))
        },
        Check("media-playback-follows-the-engines-config") {
            // EngineConfig.mediaPlaybackRequiresGesture, per engine: play() without a gesture is refused, or allowed.
            suspend fun played(requiresGesture: Boolean): String? {
                val engine = engine(EngineConfig(WebProfile.Ephemeral, mediaPlaybackRequiresGesture = requiresGesture))
                val script = "window.__play = 'pending'; document.getElementById('a').play()" +
                    ".then(function () { window.__play = 'played'; }, function (e) { window.__play = e.name; });"
                live(engine, solidPage("media", "#ffffff", body = "<audio id=a src='data:audio/wav;base64,$WAV'></audio>", script = script))
                var result: String? = null
                eventually("play() settled", millis = 5_000) { engine.evaluate("window.__play").also { result = it } != "\"pending\"" }
                return result
            }
            assertEquals("\"NotAllowedError\"", played(requiresGesture = true), "with a gesture required")
            assertEquals("\"played\"", played(requiresGesture = false), "without")
        },
        Check("autoplay-outlives-another-engines-release") {
            // Without a gesture required, play() is allowed by the profile's site setting for the origin an engine shows,
            // which a document takes as it commits. One engine's release must not take it from another engine of the
            // profile on that origin: here the second's document is on its way (the server holds it 5 s) as the first goes.
            val config = EngineConfig(WebProfile.Named("autoplay-check"), mediaPlaybackRequiresGesture = false)
            val first = engine(config)
            val firstEvents = record(first)
            start(first, WebContent.Url("${LocalServer.origin}/page"))
            firstEvents.await("the first engine's commit") { it == EngineEvent.UrlChanged("${LocalServer.origin}/page") }
            val second = engine(config)
            val events = record(second)
            show(second)
            start(second, WebContent.Url("${LocalServer.origin}/held"))
            events.await("the second engine's load starting") { it is EngineEvent.Progress }
            delay(300)
            first.release()
            events.await("the second engine's commit", millis = 10_000) { it == EngineEvent.UrlChanged("${LocalServer.origin}/held") }
            second.evaluate(
                "window.__play = 'pending'; new Audio('data:audio/wav;base64,$WAV').play()" +
                    ".then(function () { window.__play = 'played'; }, function (e) { window.__play = e.name; }); 1",
            )
            var result: String? = null
            eventually("play() settled", millis = 5_000) { second.evaluate("window.__play").also { result = it } != "\"pending\"" }
            assertEquals("\"played\"", result, "the second engine's play(), its document committed after the first's release")
        },
        Check("the-context-menu-has-only-editing-items") {
            // D17, L11: WebView2's own menu, with editing items only (which ones is glue_test's; this is its look by eye).
            val engine = engine()
            val link = "<a href='https://example.org/' style='position:fixed;left:10px;top:10px;font-size:24px'>a link</a>"
            live(engine, solidPage("menu", "#ffffff", body = link))
            val before = popupWindows()
            rightClick(40, 22)
            eventually("WebView2's menu window") { popupWindows().keys.any { it !in before } }
            delay(300)
            screenshot("context-menu")
            keys("Escape")
            eventually("the menu closed") { popupWindows().keys.none { it !in before } }
        },
        Check("a-date-input-opens-its-picker") {
            // L11: a date input's calendar is WebView2's own window, placed right under the input.
            val engine = engine()
            live(engine, solidPage("date", "#ffffff", body = "<input id=d type=date style='position:fixed;left:10px;top:10px;width:200px;height:30px;box-sizing:border-box'>"))
            val before = popupWindows()
            click(10 + 200 - 12, 25)   // the calendar button at the input's right end
            eventually("the picker") { popupWindows().keys.any { it !in before } }
            // As a select's list: two windows of one place, the browser's and one in the app's process.
            val pickers = popupWindows().filterKeys { it !in before }.values
            val input = surfaceRectOnScreen(10, 10, 200, 30)
            for (picker in pickers) assertTrue(abs(picker.y - (input.y + input.height)) <= 8, "picker at ${picker.y}, input ends at ${input.y + input.height} (opened: $pickers)")
            keys("Escape")
            eventually("the picker closed") { popupWindows().keys.none { it !in before } }
            assertEquals("\"\"", engine.evaluate("document.getElementById('d').value"), "the value unchanged")
        },
        Check("an-apps-ui-zoom-keeps-the-page-sharp-and-whole") {
            // L26: an app's UI zoom (LocalDensity 1.5) makes the surface 480 × 360 px; the page is laid out in those
            // pixels at the window's scale, not LocalDensity's, and drawn 1:1: its 1-px edges land on the surface's edges.
            val frame = "<div style='position:fixed;left:0;top:0;right:0;bottom:0;border:1px solid #000;box-sizing:border-box'></div>"
            val scene = SceneEngine(engine(), solidPage("zoom", "#ffffff", body = frame), ::contentOrigin)
            compose {
                CompositionLocalProvider(LocalDensity provides Density(1.5f)) {
                    Box(Modifier.padding(start = 20.dp, top = 20.dp)) { scene.Surface(Modifier.size(320.dp, 240.dp)) }
                }
            }
            scene.awaitPainted()
            assertEquals("480", scene.engine.evaluate("innerWidth"), "the page's width in CSS pixels")
            assertEquals("1", scene.engine.evaluate("devicePixelRatio"), "the window's scale, not LocalDensity")
            val r = scene.rect
            assertEquals(480 to 360, r.width to r.height, "the surface on screen")
            val corners = listOf(r.x to r.y, r.x + r.width - 1 to r.y, r.x to r.y + r.height - 1, r.x + r.width - 1 to r.y + r.height - 1)
            val inside = listOf(r.x + 1 to r.y + 1, r.x + r.width - 2 to r.y + 1, r.x + 1 to r.y + r.height - 2, r.x + r.width - 2 to r.y + r.height - 2)
            eventually("the page's edges on the surface's edges") {
                corners.all { (x, y) -> LinuxChecks.near(0x000000, screenPixel(x, y), 24) } &&
                    inside.all { (x, y) -> LinuxChecks.near(0xffffff, screenPixel(x, y), 24) }
            }
        },
        Check("an-overlay-takes-the-pages-hover") {
            // L28: Compose's hit test routes the mouse: over an overlay the page gets a mouseout and no moves.
            val script = "window.__out = 0; window.__right = 0;" +
                "document.addEventListener('mouseout', function (e) { if (!e.relatedTarget) window.__out++; });" +
                "document.addEventListener('mousemove', function (e) { if (e.clientX > 160) window.__right++; });"
            val body = "<div style='position:fixed;left:0;top:0;width:100%;height:100%'></div>"
            val scene = SceneEngine(engine(), solidPage("hover", "#ffffff", body = body, script = script), ::contentOrigin)
            compose {
                Box(Modifier.padding(start = 40.dp, top = 40.dp)) {
                    scene.Surface(Modifier.size(320.dp, 240.dp))
                    // An opaque overlay over the page's right half that takes the pointer, as a menu or a dialog does.
                    Box(
                        Modifier.offset(160.dp, 0.dp).size(160.dp, 240.dp).background(Color(0xFF3366CC))
                            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                    )
                }
            }
            scene.awaitPainted()
            val left = scene.pagePoint(80, 120)
            moveScreen(left.x, left.y)
            delay(300)
            moveScreen(left.x + 10, left.y + 10)
            eventually("the page sees the mouse") { scene.engine.evaluate("document.querySelector(':hover') !== null") == "true" }
            scene.engine.evaluate("window.__out = 0; window.__right = 0; 1")
            for (step in 0..4) {
                val over = scene.pagePoint(200 + step * 20, 100 + step * 10)
                moveScreen(over.x, over.y)
                delay(150)
            }
            delay(500)
            assertEquals("1", scene.engine.evaluate("window.__out"), "mouseouts: the page should have seen the mouse leave once")
            assertEquals("0", scene.engine.evaluate("window.__right"), "moves the page saw over the overlay")
        },
    )

    /** Linux's keep-alive check, with its same-document entry pushed by the user's click ([WPE_ONLY]). */
    private val keepAlive: List<Check> = listOf(
        Check("a-parked-page-is-adopted-live-with-its-state") {
            val bundle = CheckBundle("plugin-kept")
            val engine = engine()
            val events = live(engine, bundle.content)
            val nonce = nonceOf(events.await("\$ready", matches = ::isReady))
            // What only this document has: a value, a scroll position, a same-document URL and a title. The URL is the
            // user's click's: an entry a script pushes with no user activation is one Chromium's back button skips.
            val steps = listOf(
                "window.__kept = 42",
                "document.body.style.height = '3000px'",
                "scrollTo(0, 600)",
                "document.addEventListener('click', function () { history.pushState({}, '', '#kept'); }, { once: true })",
            )
            for (step in steps) assertEquals("\"ok\"", engine.evaluate("try { $step; 'ok' } catch (e) { String(e) }"), step)
            click(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            val moved = EngineEvent.UrlChanged("https://plugin-kept.invalid/index.html#kept")
            events.await("the same-document URL") { it == moved }
            assertEquals("\"ok\"", engine.evaluate("try { document.title = 'kept now'; 'ok' } catch (e) { String(e) }"))
            events.await("the title") { it == EngineEvent.TitleChanged("kept now") }
            events.await("history to go back to") { it == EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false) }
            park(engine)
            delay(1_000)
            val adopted = adopt(engine)
            adopted.await("the replayed URL") { it == moved }
            adopted.await("the replayed history") { it == EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false) }
            adopted.await("the replayed title") { it == EngineEvent.TitleChanged("kept now") }
            adopted.await("the replayed first paint") { it == EngineEvent.FirstPaint }
            assertTrue(adopted.events.none { it is EngineEvent.Progress }, "no navigation: ${adopted.events}")
            assertEquals("\"42|600\"", engine.evaluate("window.__kept + '|' + scrollY"), "the same document, scrolled as it was")
            // Its bridge is the same document's too: its nonce still reaches it.
            engine.postMessage(hostEvent("ping", 5, nonce), bundle.content.origin.origin)
            adopted.await("pong") { isPong(it, 5) }
        },
    )


    /** The V-questions that only running WebView2 answers (the plan's V4, V5, V7), as checks. */
    private val questions: List<Check> = listOf(
        Check("a-back-navigation-is-a-new-document") {
            // V4: going back makes a new document: its bridge announces itself anew, with a nonce of its own.
            val bundle = CheckBundle("back-check")
            val engine = engine()
            engine.client = CheckClient(navigation = NavigationDecision.Allow)
            val events = live(engine, bundle.content)
            val first = nonceOf(events.events.first(::isReady))
            // The user's click, not a script's: Chromium's back button skips an entry left with no user activation, and
            // WebView2's CanGoBack with it.
            engine.evaluate("document.getElementById('next').style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;display:block'; 1")
            click(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            events.await("page 2") { it == EngineEvent.UrlChanged("https://back-check.invalid/page2.html") }
            events.await("page 2's \$ready", from = events.events.indexOfLast { it == EngineEvent.UrlChanged("https://back-check.invalid/page2.html") }, matches = ::isReady)
            val before = events.events.size
            engine.goBack()
            events.await("the first page again", from = before) { it == EngineEvent.UrlChanged("https://back-check.invalid/index.html") }
            val again = events.await("a new \$ready", from = before, matches = ::isReady)
            assertNotEquals(first, nonceOf(again), "the document gone back to is a new one")
        },
        Check("a-fragment-only-load-lifts-the-cover") {
            // V5, L21: a load of the same document's URL with another fragment: no new document, and the view is live
            // again with no cover within 2 s; its bridge still answers.
            val stage = ViewStage(WebContent.Url("${LocalServer.origin}/doc#a"), WebSettings(bridgeOrigins = setOf(LocalServer.origin)))
            compose { stage.Scene() }
            try {
                stage.waitFor("live and announced") { stage.state.phase == WebViewPhase.Live && stage.state.bridge.isReady }
                stage.state.load(WebContent.Url("${LocalServer.origin}/doc#b"))
                stage.waitFor("live again on #b", 2_000) { stage.state.url == "${LocalServer.origin}/doc#b" && stage.state.phase == WebViewPhase.Live }
                assertEquals(JsonPrimitive(5), stage.state.bridge.call("echo", JsonPrimitive(5)))
            } finally {
                compose(null)
                delay(300)
                stage.close()
            }
        },
        Check("reset-forgets-the-leases-grants") {
            // V7: a permission one lease granted is asked again in the next lease of the same origin.
            val asked = mutableListOf<PermissionRequest>()
            val client = object : EngineClient by CheckClient() {
                override suspend fun onPermission(request: PermissionRequest): Boolean {
                    asked += request
                    return true
                }
            }
            val engine = engine(WebProfile.Default)
            val content = page("grants")
            val geolocate = "navigator.geolocation.getCurrentPosition(function () {}, function () {}); 1"
            engine.client = client
            live(engine, content)
            engine.evaluate(geolocate)
            eventually("the first lease asked") { asked.size == 1 }
            assertTrue(engine.reset(), "reset")
            engine.client = client
            live(engine, content)
            engine.evaluate(geolocate)
            eventually("the next lease asked again") { asked.size == 2 }
            assertTrue(asked.all { PermissionKind.Geolocation in it.kinds }, "$asked")
        },
    )

    /** The screen's pixels in [area] now, as the user sees them (other processes' windows too). */
    private suspend fun capture(area: Rectangle): BufferedImage = withContext(Dispatchers.IO) { Robot().createScreenCapture(area) }

    /**
     * The bounds on screen of the pixels of [area] that differ by more than [tolerance] in a channel between [before] and
     * [after], outside [except]; null if none do.
     */
    internal fun changedBounds(before: BufferedImage, after: BufferedImage, area: Rectangle, except: Rectangle, tolerance: Int = 24): Rectangle? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (y in 0 until minOf(before.height, after.height)) {
            for (x in 0 until minOf(before.width, after.width)) {
                if (except.contains(area.x + x, area.y + y)) continue
                val a = before.getRGB(x, y)
                val b = after.getRGB(x, y)
                val differs = (0..16 step 8).any { shift -> abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) > tolerance }
                if (!differs) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        if (left > right) return null
        return Rectangle(area.x + left, area.y + top, right - left + 1, bottom - top + 1)
    }

    /** The app's renderer processes now: the PIDs of `msedgewebview2.exe --type=renderer` under its browser process. */
    private suspend fun renderers(): Set<Long> = withContext(Dispatchers.IO) {
        val app = System.getProperty("dev.supermux.webview.app") ?: "wvc-sample"
        val script = "\$all = @(Get-CimInstance Win32_Process -Filter \"Name = 'msedgewebview2.exe'\"); " +
            "\$browsers = @(\$all | Where-Object { \$_.CommandLine -like '*dev.supermux.webview\\webview2\\$app*' -and \$_.CommandLine -notlike '*--type=*' } | ForEach-Object { \$_.ProcessId }); " +
            "\$all | Where-Object { \$browsers -contains \$_.ParentProcessId -and \$_.CommandLine -like '*--type=renderer*' } | ForEach-Object { \$_.ProcessId }"
        val process = ProcessBuilder(powershellCommand(script)).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        out.lines().mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    /** What [block] gives, and how long it took, in ms. */
    private suspend fun <T> timed(block: suspend () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val value = block()
        return value to (System.nanoTime() - start) / 1_000_000
    }

    /** Hangs [engine]'s page in a loop, and returns its renderer: the one that then spins. */
    private suspend fun hang(engine: WebEngine): Long {
        engine.evaluate("setTimeout(function () { for (;;) {} }, 0); 1")
        return spinningRenderer()
    }

    private fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    /** The app's renderer that spins once a page hangs: the one whose CPU time grows by most of 800 ms. */
    private suspend fun spinningRenderer(): Long {
        val pids = renderers()
        return withContext(Dispatchers.IO) { spinningOf(pids) }
    }

    private fun spinningOf(pids: Set<Long>): Long {
        fun cpu(pid: Long): Long = ProcessHandle.of(pid).flatMap { it.info().totalCpuDuration() }.map { it.toMillis() }.orElse(0L)
        val start = pids.associateWith(::cpu)
        Thread.sleep(800)
        val grown = pids.associateWith { cpu(it) - start.getValue(it) }
        val (pid, ms) = grown.maxByOrNull { it.value } ?: throw AssertionError("no renderer of the app's")
        if (ms < 400) throw AssertionError("no renderer spins: CPU grown by $grown ms in 800 ms")
        return pid
    }

    /** A WAV of half a second's silence (8 kHz, 8-bit, mono), base64: media with sound to the autoplay policy. */
    private val WAV: String by lazy {
        val samples = 4_000
        val buffer = ByteBuffer.allocate(44 + samples).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(8_000).putShort(1).putShort(8)
        buffer.put("data".toByteArray()).putInt(samples)
        repeat(samples) { buffer.put(0x80.toByte()) }
        Base64.getEncoder().encodeToString(buffer.array())
    }

    /** A check's own runtime (no spares) and one `WebView` of [content]; [close] lets them all go. */
    private class ViewStage(content: WebContent, private val settings: WebSettings = WebSettings()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(config = RuntimeConfig(maxLive = 2, maxIdle = 0, snapshotCacheBytes = 8L shl 20), scope = scope)
        val state = WebViewState(content)

        @Composable
        fun Scene() {
            ProvideWebViewRuntime(runtime) {
                Box(Modifier.padding(start = 40.dp, top = 40.dp)) {
                    WebView(state, Modifier.size(320.dp, 300.dp), settings = settings, activation = Activation.Eager)
                }
            }
        }

        suspend fun waitFor(what: String, millis: Long = 20_000, condition: () -> Boolean) {
            withTimeoutOrNull(millis) { while (!condition()) delay(20); true }
                ?: throw AssertionError("no $what within $millis ms: phase ${state.phase}, error ${state.error}, url ${state.url}")
        }

        fun close() {
            runtime.clear()
            runtime.onMemoryPressure()
            scope.cancel()
        }
    }
}

/**
 * The Windows checks' window (winvm.sh checks): decorated, at the screen's corner (a title bar the focus checks give
 * activation back through), the surface at (40, 40). Runs [WindowsChecks.all], or those named in [only]
 * (comma-separated), and exits: 0 if every check passed, 1 if one failed, 2 if none matched.
 */
fun windowsChecksMain(only: String?) {
    runCatching { WindowsInput.ready() }.onFailure {
        println("CHECKS DONE passed=0 failed=1 (the sample's test DLL: ${it.message})")
        exitProcess(1)
    }
    val names = only?.split(',')?.map { it.trim() }?.toSet()
    val checks = WindowsChecks.all.filter { names == null || it.name in names }
    if (checks.isEmpty()) {
        println("CHECKS DONE passed=0 failed=0 (no check named $only)")
        exitProcess(2)
    }
    application {
        val host = remember { CheckHost() }
        Window(
            onCloseRequest = ::exitApplication,
            title = "webview-sample · Windows engine checks",
            state = rememberWindowState(position = WindowPosition(0.dp, 0.dp), size = DpSize(720.dp, 720.dp)),
        ) {
            host.window = window
            LaunchedEffect(Unit) {
                val failed = runChecks(checks, host, this)
                exitProcess(if (failed == 0) 0 else 1)
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF202124))) {
                val scene = host.scene
                val engine = host.shown
                if (scene != null) {
                    scene()
                } else if (engine != null) {
                    key(engine) {
                        engine.Surface(
                            Modifier.offset(40.dp, 40.dp).size(SURFACE_WIDTH.dp, SURFACE_HEIGHT.dp)
                                .onGloballyPositioned { host.bounds = it.boundsInWindow() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * powershell.exe running [script], encoded (UTF-16LE, base64): Java on Windows passes a double quote inside an argument
 * as nothing, so a `-Command` script's quoted strings would reach PowerShell unquoted.
 */
internal fun powershellCommand(script: String): List<String> = listOf(
    "powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand",
    Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)),
)
