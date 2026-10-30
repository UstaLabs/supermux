package dev.supermux.webview.sample.linux

import com.sun.net.httpserver.HttpServer
import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebError
import dev.supermux.webview.engine.EngineClient
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EmbeddingMode
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.WebEngine
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * The Linux engine's conformance checks: the WebEngine contract with real WebKit pages, in the app, as iOS's
 * `EngineChecks` are, and named as they are where they check the same thing. Each goes through the public engine SPI
 * only, as the runtime does. Grouped by the plan task that brought them.
 */
object LinuxChecks {
    /** Starts [content] in [engine] as the runtime does: configured, then loaded. */
    internal fun start(engine: WebEngine, content: WebContent, javaScript: Boolean = true) {
        engine.configure(setupFor(content, javaScript))
        engine.load(content)
    }

    /** [engine] loads [content] on screen, and returns its recorder once the page has painted and announced itself. */
    internal suspend fun CheckScope.live(engine: WebEngine, content: WebContent): Recorder {
        val events = record(engine)
        show(engine)
        start(engine, content)
        events.await("first paint") { it == EngineEvent.FirstPaint }
        events.await("\$ready", matches = ::isReady)
        return events
    }

    /** Creation, commits, the bridge both ways and its isolation, documents (Task 23). */
    val bridge: List<Check> = listOf(
        Check("factory-makes-a-frames-engine") {
            val engine = engine()
            assertTrue(engine.isUsable)
            assertEquals(EmbeddingMode.Frames, engine.mode)
            assertFalse(engine.needsCutouts)
        },
        Check("html-commits-on-its-secure-origin") {
            val engine = engine()
            val events = record(engine)
            val content = page("origin")
            start(engine, content)
            events.await("the commit") { it == EngineEvent.UrlChanged(content.origin.url) }
            assertEquals("\"${content.origin.origin}|true\"", engine.evaluate("self.origin + '|' + isSecureContext"))
        },
        Check("the-bridge-delivers-to-the-current-document") {
            val engine = engine()
            val events = live(engine, echoPage)
            val ready = events.await("\$ready", matches = ::isReady) as EngineEvent.PageMessage
            assertEquals(echoPage.origin.origin, ready.origin)
            engine.postMessage(hostEvent("ping", 7, nonceOf(ready)), echoPage.origin.origin)
            events.await("pong") { isPong(it, 7) }
        },
        Check("delivery-checks-the-origin-when-it-arrives") {
            val engine = engine()
            val events = live(engine, echoPage)
            val nonce = nonceOf(events.await("\$ready", matches = ::isReady))
            // The page lies about its origin in its own world; the delivering script, in the engine's world, can't be fooled.
            engine.evaluate("Object.defineProperty(window, 'origin', { get: function () { return 'https://evil.example'; } }); 1")
            val before = events.events.size
            engine.postMessage(hostEvent("ping", 1, nonce), "https://evil.example")
            events.expectNone("a pong for the wrong origin", from = before) { isPong(it, 1) }
            engine.postMessage(hostEvent("ping", 2, nonce), echoPage.origin.origin)
            events.await("pong", from = before) { isPong(it, 2) }
        },
        Check("page-scripts-cannot-reach-the-channel") {
            val engine = engine()
            live(engine, echoPage)
            assertEquals("true", engine.evaluate("!(window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.webview)"))
            assertEquals("\"object\"", engine.evaluate("typeof hostBridge"))
        },
        Check("subframes-get-no-shim") {
            val engine = engine()
            live(engine, page("frames", body = "<iframe srcdoc='<p>inner</p>'></iframe>"))
            delay(300)
            assertEquals("\"undefined\"", engine.evaluate("typeof document.querySelector('iframe').contentWindow.hostBridge"))
        },
        Check("javascript-off-means-no-shim-and-no-page-scripts") {
            val engine = engine()
            val events = record(engine)
            show(engine)
            start(engine, page("js-off", script = "document.title = 'ran';"), javaScript = false)
            events.await("first paint") { it == EngineEvent.FirstPaint }
            assertEquals("\"undefined\"", engine.evaluate("typeof hostBridge"))
            assertEquals("\"js-off\"", engine.evaluate("document.title"))
            assertTrue(events.events.none(::isReady))
        },
        Check("a-load-leaves-the-old-documents-events-behind") {
            val engine = engine()
            live(engine, page("chatty", script = "setInterval(function () { hostBridge.post('tick', 1); }, 5);"))
            val next = record(engine)
            // A recorder takes what is queued when it starts, and a tick every 5 ms is usually queued: those came before
            // the load and are the old document's to deliver. What the check forbids is a tick after the load.
            val atLoad = next.events.size
            engine.load(page("quiet"))
            next.await("the new commit") { it is EngineEvent.UrlChanged }
            next.expectNone("the old document's ticks", from = atLoad, millis = 1_000) {
                it is EngineEvent.PageMessage && it.json.contains("\"tick\"")
            }
        },
        Check("titles-and-same-document-urls-are-reported") {
            val engine = engine()
            val content = page("titled")
            val events = live(engine, content)
            events.await("the page's title") { it == EngineEvent.TitleChanged("titled") }
            assertEquals("\"ok\"", engine.evaluate("history.pushState({}, '', '#moved'); document.title = 'moved'; 'ok'"))
            events.await("the same-document URL") { it == EngineEvent.UrlChanged(content.origin.url + "#moved") }
            events.await("the new title") { it == EngineEvent.TitleChanged("moved") }
        },
        Check("a-bundle-is-served-on-its-scheme-and-reported-as-its-origin") {
            val bundle = CheckBundle("plugin-check")
            val engine = engine()
            val events = live(engine, bundle.content)
            val ready = events.events.first(::isReady) as EngineEvent.PageMessage
            assertEquals("https://plugin-check.invalid", ready.origin)
            assertTrue(EngineEvent.UrlChanged("https://plugin-check.invalid/index.html") in events.events, "${events.events}")
            assertEquals("\"webview-bundle://plugin-check.invalid\"", engine.evaluate("self.origin"))
            engine.evaluate(
                "window.__statuses = null; Promise.all([fetch('data.json'), fetch('/%2e%2e%2fsecret')])" +
                    ".then(function (r) { window.__statuses = r[0].status + '|' + r[1].status; }); 1",
            )
            var statuses: String? = "null"
            repeat(60) { if (statuses == "null") statuses = engine.evaluate("window.__statuses").also { delay(50) } }
            assertEquals("\"200|404\"", statuses)
            assertTrue(bundle.asked.none { ".." in it }, "asked: ${bundle.asked}")
        },
    )

    /** A page of one colour, edge to edge, with [body] over it. */
    internal fun solidPage(title: String, color: String, body: String = "", script: String = ""): WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>$title</title><style>html,body{margin:0;height:100%;background:$color}</style></head>
<body>$body<script>$script</script></body></html>""",
    )

    /** Whether two `0xRRGGBB` colours are within [tolerance] in each channel. */
    internal fun near(expected: Int, actual: Int, tolerance: Int = 3): Boolean =
        (0..2).all { channel -> abs((expected shr (8 * channel) and 0xff) - (actual shr (8 * channel) and 0xff)) <= tolerance }

    /** Waits up to [millis] for [condition], looking every 50 ms. */
    internal suspend fun eventually(what: String, millis: Long = 3_000, condition: suspend () -> Boolean) {
        val until = System.nanoTime() + millis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > until) throw AssertionError("$what: not within $millis ms")
            delay(50)
        }
    }

    /** The surface: frames on screen, size, pointer, keys, wheel, focus (Task 24). */
    val surface: List<Check> = listOf(
        Check("the-surface-draws-the-page") {
            val engine = engine()
            live(engine, solidPage("blue", "#3366cc"))
            eventually("the page's colour on screen") { near(0x3366cc, pixel(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)) }
        },
        Check("a-new-engines-first-page-is-laid-out-at-its-surfaces-size") {
            // Loaded before its surface is laid out, as the runtime loads a view's first page: laid out at the surface's
            // size, not at the size the view had when the load began.
            val engine = engine()
            val events = record(engine)
            start(engine, page("scale"))
            show(engine)
            events.await("first paint") { it == EngineEvent.FirstPaint }
            assertEquals("$SURFACE_WIDTH", engine.evaluate("innerWidth"), "the page's width, in CSS pixels")
            assertEquals("$SURFACE_HEIGHT", engine.evaluate("innerHeight"))
            assertEquals("1", engine.evaluate("devicePixelRatio"))
        },
        Check("a-click-reaches-the-page") {
            val engine = engine()
            val button = "<button style='position:fixed;left:0;top:0;width:100%;height:100%' " +
                "onclick=\"document.title='clicked'\">click</button>"
            val events = live(engine, solidPage("click", "#ffffff", body = button))
            click(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            events.await("the click's title") { it == EngineEvent.TitleChanged("clicked") }
        },
        Check("typing-reaches-a-focused-field") {
            val engine = engine()
            val field = "<input id=f style='position:fixed;left:0;top:0;width:100%;height:60px;font-size:24px'>"
            live(engine, solidPage("type", "#ffffff", body = field))
            click(SURFACE_WIDTH / 2, 30)
            eventually("the field focused") { engine.evaluate("document.activeElement.id") == "\"f\"" }
            type("Merhaba çğış İÖÜ")
            eventually("the typed text") { engine.evaluate("document.getElementById('f').value") == "\"Merhaba çğış İÖÜ\"" }
        },
        Check("keyboard-shortcuts-and-the-clipboard-work-in-a-page") {
            // I3: with Control held AWT on X11 reports Ctrl+C as 0x03; WebKit's shortcuts want the letter. A page's copy
            // reaches the system's clipboard (what a Compose text field pastes), and the system's reaches the page.
            val engine = engine()
            val field = "<input id=f value='hello world' style='position:fixed;left:0;top:0;width:100%;height:60px;font-size:24px'>"
            val script = "addEventListener('keydown', function (e) { if (e.ctrlKey && e.key === 'f') document.title = 'find'; });"
            live(engine, solidPage("keys", "#ffffff", body = field, script = script))
            click(SURFACE_WIDTH / 2, 30)
            eventually("the field focused") { engine.evaluate("document.activeElement.id") == "\"f\"" }
            val system = Toolkit.getDefaultToolkit().systemClipboard
            fun copied(): String? = runCatching { system.getData(DataFlavor.stringFlavor) as String }.getOrNull()
            suspend fun value(): String? = engine.evaluate("document.getElementById('f').value")
            keys("ctrl+a", "ctrl+c")
            eventually("the page's copy in the system's clipboard") { copied() == "hello world" }
            keys("ctrl+x")
            eventually("the field cut") { value() == "\"\"" }
            keys("ctrl+z")
            eventually("the cut undone") { value() == "\"hello world\"" }
            system.setContents(StringSelection("from compose"), null)
            keys("ctrl+a", "ctrl+v")
            eventually("the system's clipboard pasted") { value() == "\"from compose\"" }
            keys("ctrl+f")
            eventually("the page's own Ctrl+F") { engine.evaluate("document.title") == "\"find\"" }
        },
        Check("a-select-opens-its-menu-and-takes-the-choice") {
            // I7: WPE shows no menu for a <select>: the surface shows one under it, and WebKit gets the choice, chosen with
            // the mouse, then with the keys.
            val engine = engine()
            val select = "<select id=s style='position:fixed;left:10px;top:10px;width:200px;height:30px;font-size:16px'>" +
                "<option value=a>Apple</option><option value=b>Banana</option><option value=c>Cherry</option></select>"
            live(engine, solidPage("select", "#ffffff", body = select))
            suspend fun value(): String? = engine.evaluate("document.getElementById('s').value")
            click(100, 25)
            delay(500)
            // The menu: under the select (it ends at y 40), an item about 25 px high each: Cherry, the third.
            click(60, 41 + 25 * 2 + 12)
            eventually("Cherry, chosen with the mouse") { value() == "\"c\"" }
            click(100, 25)
            delay(500)
            keys("Up", "Return")
            eventually("Banana, chosen with the keys") { value() == "\"b\"" }
        },
        Check("the-wheel-scrolls-the-page") {
            val engine = engine()
            live(engine, solidPage("tall", "#ffffff", body = "<div style='height:4000px;background:linear-gradient(#fff,#000)'></div>"))
            wheel(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2, 3)
            eventually("the page scrolled down") { (engine.evaluate("scrollY")?.toDoubleOrNull() ?: 0.0) > 0.0 }
        },
        Check("a-click-focuses-the-page") {
            val engine = engine()
            val events = live(engine, solidPage("focus", "#ffffff"))
            click(10, 10)
            events.await("focus") { it == EngineEvent.FocusChanged(true) }
        },
    )

    /** The handoff from the cover, and snapshots (Task 25). */
    val handoff: List<Check> = listOf(
        Check("first-paint-waits-for-the-screen") {
            // Off screen even the 3 s fallback waits: the first paint means drawn in place.
            val engine = engine()
            val events = record(engine)
            start(engine, page("paint"))
            assertNull(engine.snapshot(), "no snapshot before the first paint")
            events.expectNone("first paint off screen", from = 0, millis = 4_000) { it == EngineEvent.FirstPaint }
            show(engine)
            events.await("first paint on screen") { it == EngineEvent.FirstPaint }
            val image = assertNotNull(engine.snapshot())
            assertEquals(SURFACE_WIDTH to SURFACE_HEIGHT, image.width to image.height)
        },
        Check("the-page-is-on-screen-at-its-first-paint") {
            // A new engine shows its floor first: a first paint said before the page's own frame reached the screen
            // would find white here, not the page.
            val engine = engine()
            val events = record(engine)
            show(engine)
            start(engine, solidPage("blue", "#3366cc"))
            events.await("first paint") { it == EngineEvent.FirstPaint }
            val seen = pixel(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            assertTrue(near(0x3366cc, seen), "the screen at the first paint: #${seen.toString(16)}")
        },
        Check("first-paint-waits-for-the-pages-content") {
            // A page with nothing to show but an image, served 1.5 s late: parsed at once, it paints only with its image.
            val engine = engine()
            val events = record(engine)
            val bundle = SlowImageBundle("late-image", delayMillis = 1_500)
            show(engine)
            start(engine, bundle.content)
            events.await("first paint", millis = 10_000) { it == EngineEvent.FirstPaint }
            val painted = bundle.elapsed()
            val served = assertNotNull(bundle.servedAt, "the first paint ($painted) came before the image was served")
            assertTrue(painted > served, "the first paint at $painted, the image served at $served")
        },
        Check("a-page-that-never-finishes-is-shown-after-three-seconds") {
            // Nothing contentful ever paints and it never loads (its image never comes): shown once 3 s have passed.
            val engine = engine()
            val events = record(engine)
            val bundle = SlowImageBundle("never-image", delayMillis = null)
            show(engine)
            start(engine, bundle.content)
            events.await("first paint", millis = 10_000) { it == EngineEvent.FirstPaint }
            val painted = bundle.elapsed()
            assertTrue(painted >= 2.9.seconds && painted <= 6.seconds, "the first paint at $painted")
        },
        Check("a-snapshot-is-the-page-as-drawn") {
            val engine = engine()
            live(engine, solidPage("blue", "#3366cc"))
            val image = assertNotNull(engine.snapshot())
            assertEquals(SURFACE_WIDTH to SURFACE_HEIGHT, image.width to image.height)
            val color = image.toPixelMap()[SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2].toArgb() and 0xFFFFFF
            assertTrue(near(0x3366cc, color), "the snapshot's colour: #${color.toString(16)}")
            show(null)
            delay(300)
            assertNull(engine.snapshot(), "no surface, no snapshot")
        },
    )

    /** Reset, restore, profiles, release, and a hidden page (Task 26). */
    val lifecycle: List<Check> = listOf(
        Check("reset-refuses-the-history-before-it") {
            val engine = engine(WebProfile.Default)
            live(engine, page("before"))
            engine.evaluate("location.hash = 'two'; 1")
            delay(300)
            assertTrue(engine.reset(), "reset")
            val after = page("after")
            val events = live(engine, after)
            val history = events.events.filterIsInstance<EngineEvent.HistoryChanged>().last()
            assertFalse(history.canGoBack, "history begins with the first load after the reset")
            val before = events.events.size
            engine.evaluate("history.back(); 1")
            engine.goBack()
            events.expectNone("a navigation into the history before the reset", from = before) { it is EngineEvent.UrlChanged }
            assertEquals("\"${after.origin.url}\"", engine.evaluate("location.href"))
        },
        Check("reset-wipes-its-own-ephemeral-store-only") {
            val content = page("storage")
            val first = engine(WebProfile.Ephemeral)
            val second = engine(WebProfile.Ephemeral)
            live(first, content)
            first.evaluate("localStorage.setItem('k', 'first'); 1")
            live(second, content)
            second.evaluate("localStorage.setItem('k', 'second'); 1")
            assertTrue(first.reset(), "reset")
            live(first, content)
            assertEquals("null", first.evaluate("localStorage.getItem('k')"))
            assertEquals("\"second\"", second.evaluate("localStorage.getItem('k')"))
        },
        Check("restore-brings-back-a-bundles-history") {
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
            restored.await("history to go back to") { it is EngineEvent.HistoryChanged && it.canGoBack }
            second.goBack()
            restored.await("the entry") { it == EngineEvent.UrlChanged("https://plugin-restore.invalid/index.html") }
        },
        Check("a-retry-after-a-failed-load-loads-the-content-again") {
            // The final review's item 3, iOS's rule: WebKit's own reload reloaded the floor, which the rules refuse, so a
            // retry after a failed first load did nothing; and the floor's history was saved under the content's key.
            val offline = WebContent.Url("https://nowhere.invalid/page")
            val engine = engine()
            val events = record(engine)
            show(engine)
            start(engine, offline)
            events.await("the load's error", millis = 20_000) { it is EngineEvent.Error }
            assertNull(engine.saveState(), "WebKit's state is the floor's: restored, it would show nothing of the page")
            val before = events.events.size
            engine.reload()
            val again = events.await("the retry's error", from = before, millis = 20_000) { it is EngineEvent.Error }
            assertEquals(offline.url, (again as EngineEvent.Error).error.url, "the retry loads the content")
        },
        Check("in-memory-html-saves-no-engine-state") {
            val engine = engine()
            live(engine, page("html-state"))
            assertNull(engine.saveState())
        },
        Check("named-profiles-are-separate") {
            val content = page("profiles")
            val a = engine(WebProfile.Named("check-a"))
            val b = engine(WebProfile.Named("check-b"))
            live(a, content)
            a.evaluate("localStorage.setItem('who', 'a'); 1")
            live(b, content)
            assertEquals("null", b.evaluate("localStorage.getItem('who')"))
            a.evaluate("localStorage.clear(); 1")
        },
        Check("a-named-profiles-engines-share-one-session") {
            // The final review's item 6: each engine of a Named profile had a network session of its own on the profile's
            // folders, so a login in one live view wasn't another's, and two storage managers had one folder. One
            // session per profile, as Default's: what one engine stores, and the cookies it gets, the other has at once.
            val content = page("shared-profile")
            val a = engine(WebProfile.Named("check-shared"))
            val b = engine(WebProfile.Named("check-shared"))
            live(a, content)
            live(b, content)
            a.evaluate("localStorage.setItem('who', 'a'); 1")
            eventually("a's storage, in b") { b.evaluate("localStorage.getItem('who')") == "\"a\"" }
            a.evaluate("localStorage.clear(); 1")
            loadServerPage(a, "/cookie")
            loadServerPage(b, "/page")
            val cookies = b.evaluate("document.cookie").orEmpty()
            assertTrue("kept=v4f2" in cookies && "session=s1" in cookies, "a's login, in b: $cookies")
        },
        Check("a-recycled-engine-shows-nothing-of-the-last-lease") {
            // I5: under a transparent page's cover (a snapshot keeps its alpha) what the surface draws shows through, so
            // until the next page has a frame of its own the surface draws nothing: never the last lease's page, nor white.
            val engine = engine(WebProfile.Default)
            live(engine, solidPage("last", "#cc3333"))
            eventually("the last lease's page on screen") { near(0xcc3333, pixel(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)) }
            show(null)
            assertTrue(engine.reset(), "reset")
            val next = WebContent.Url("${LocalServer.origin}/slow")
            engine.configure(setupFor(next).copy(transparentBackground = true))
            show(engine)
            engine.load(next)
            val seen = mutableListOf<Int>()
            repeat(25) {
                delay(60)
                seen += pixel(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            }
            val leaked = seen.filter { near(0xcc3333, it, 24) || near(0xffffff, it, 24) }
            assertTrue(leaked.isEmpty(), "the surface showed ${leaked.map { "#" + it.toString(16) }} while the next page loaded")
        },
        Check("a-persistent-profiles-cookies-are-written-to-disk") {
            // I4: WebKit kept cookies in memory unless told where to write them: a login in a Default or Named profile was
            // gone after a restart. A profile's lasting cookies go to its own file; an Ephemeral profile's nowhere.
            val named = engine(WebProfile.Named("cookie-check"))
            loadServerPage(named, "/cookie")
            assertTrue(named.evaluate("document.cookie")?.contains("kept=v4f2") == true, "the cookie, set")
            eventually("the lasting cookie in the profile's file") { cookieFiles().any { "cookie-check" in it.path && containsAscii(it, "v4f2") } }
            val ephemeral = engine(WebProfile.Ephemeral)
            loadServerPage(ephemeral, "/cookie?gone")
            assertTrue(ephemeral.evaluate("document.cookie")?.contains("gone=e9x1") == true, "the cookie, set")
            delay(1_000)
            assertTrue(cookieFiles().none { containsAscii(it, "e9x1") }, "an ephemeral profile's cookie on disk")
        },
        Check("a-login-survives-a-restart") {
            // Two runs (linux-engine-checks.sh with KEEP_HOME=1 for the second): the first logs in, the second, a new
            // process on the same home, finds the lasting cookie and not the session's.
            val marker = File(System.getProperty("user.home"), "login-restart-pending")
            val engine = engine(WebProfile.Named("login-check"))
            if (!marker.exists()) {
                loadServerPage(engine, "/cookie")
                assertTrue(engine.evaluate("document.cookie")?.contains("kept=v4f2") == true, "logged in")
                marker.writeText(LocalServer.origin)
                println("CHECK NOTE a-login-survives-a-restart: logged in; run it again in a new process (KEEP_HOME=1)")
                return@Check
            }
            marker.delete()
            loadServerPage(engine, "/page")
            val cookies = engine.evaluate("document.cookie").orEmpty()
            assertTrue("kept=v4f2" in cookies, "the login after a restart: $cookies")
            assertFalse("session=s1" in cookies, "a session cookie after a restart: $cookies")
        },
        Check("a-second-process-of-the-app-gets-no-profile-the-first-has") {
            // I8: profiles are the app's own, and one process of it at a time has one: two of WebKit's network processes
            // on one store would corrupt it. Another app's process of the same name has its own.
            val engine = engine(WebProfile.Named("shared-check"))
            live(engine, page("first"))
            val same = probe("shared-check", app = null)
            assertTrue(same.startsWith("PROBE Unavailable") && "in use" in same, same)
            assertEquals("PROBE Created", probe("shared-check", app = "another-app"))
        },
        Check("release-is-final") {
            val engine = engine()
            engine.release()
            assertFalse(engine.isUsable)
            engine.load(page("after release"))
            assertNull(engine.evaluate("1"))
        },
        Check("a-hidden-page-is-hidden-from-its-scripts") {
            val engine = engine()
            live(engine, page("hidden"))
            engine.setVisible(false)
            eventually("hidden") { engine.evaluate("document.visibilityState") == "\"hidden\"" }
            engine.setVisible(true)
            eventually("visible again") { engine.evaluate("document.visibilityState") == "\"visible\"" }
        },
    )

    /** [engine] on screen with the local server's page at [path], once it has painted. */
    private suspend fun CheckScope.loadServerPage(engine: WebEngine, path: String) {
        val events = record(engine)
        show(engine)
        start(engine, WebContent.Url("${LocalServer.origin}$path"))
        events.await("first paint") { it == EngineEvent.FirstPaint }
    }

    /**
     * Another process of this app (the same main class and classpath, so the same app as [dev.supermux.webview]'s
     * Linux engine names it), or of the one named [app], asking for [profile]: its `PROBE` line.
     */
    private suspend fun probe(profile: String, app: String?): String = withContext(Dispatchers.IO) {
        val launcher = ProcessHandle.current().info().command().orElse("java")
        val inherited = listOf("dev.supermux.webview.wpe.runtime", "dev.supermux.webview.wpe.sandbox", "user.home", "sun.java2d.uiScale")
            .mapNotNull { key -> System.getProperty(key)?.let { "-D$key=$it" } }
        val command = listOf(launcher, "-cp", System.getProperty("java.class.path"), "-Dwebview.sample.probe=$profile") +
            listOfNotNull(app?.let { "-Ddev.supermux.webview.app=$it" }) + inherited + "dev.supermux.webview.sample.MainKt"
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
        out.lines().firstOrNull { it.startsWith("PROBE") } ?: "no PROBE line: ${out.takeLast(400)}"
    }

    /** Every profile's cookie file under the home folder, with SQLite's journal beside it. */
    private fun cookieFiles(): List<File> =
        File(System.getProperty("user.home")).walk().filter { it.isFile && it.name.startsWith("cookies.sqlite") }.toList()

    private fun containsAscii(file: File, text: String): Boolean = runCatching { String(file.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("").contains(text)

    /** The PIDs of this JVM's WebKit web processes (whose argv[0] is WPEWebProcess: bubblewrap's name it too). */
    private fun webProcesses(): List<Long> = ProcessHandle.current().descendants().toList().filter { process ->
        runCatching { File("/proc/${process.pid()}/cmdline").readText().substringBefore('\u0000') }.getOrDefault("").endsWith("/WPEWebProcess")
    }.map { it.pid() }

    /** The CPU time process [pid] has used, in clock ticks; null once it is gone or a zombie. */
    private fun cpuTicks(pid: Long): Long? {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        val fields = stat.substringAfterLast(')').trim().split(' ')
        if (fields[0] == "Z" || fields[0] == "X") return null
        return fields[11].toLong() + fields[12].toLong()
    }

    /** Hangs [engine]'s page in a loop, and returns its web process: the one that then uses a CPU. */
    private suspend fun hang(engine: WebEngine): Long {
        engine.evaluate("setTimeout(function () { for (;;) {} }, 0); 1")
        val before = webProcesses().associateWith { cpuTicks(it) ?: 0 }
        delay(600)
        val spinning = before.entries.maxByOrNull { (pid, ticks) -> (cpuTicks(pid) ?: 0) - ticks }
        assertNotNull(spinning, "no web process")
        assertTrue((cpuTicks(spinning.key) ?: 0) - spinning.value >= 30, "the page didn't hang: ${before.keys}")
        return spinning.key
    }

    /** A page that stops answering: its process goes with its engine, and isn't kept (I2). */
    val hangs: List<Check> = listOf(
        Check("a-hung-pages-process-is-gone-once-released") {
            val engine = engine()
            val content = page("hung")
            live(engine, content)
            val spinning = hang(engine)
            // A script call that is never answered, as every postMessage to a hung page is: it can't keep the page.
            engine.postMessage("{}", content.origin.origin)
            engine.release()
            eventually("the hung page's process gone", millis = 1_000) { cpuTicks(spinning) == null }
        },
        Check("a-reset-keeps-no-hung-page") {
            val engine = engine()
            live(engine, page("hung"))
            val spinning = hang(engine)
            val reused = engine.reset()
            eventually("the hung page's process gone after the reset (reused: $reused)", millis = 2_000) { cpuTicks(spinning) == null }
        },
        Check("a-page-that-stops-answering-is-ended-and-reported-crashed") {
            // WebKit finds a page unresponsive 3 s after input it waits on: the engine ends its process, as Android's does.
            val engine = engine()
            val events = live(engine, page("stuck"))
            val spinning = hang(engine)
            click(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            events.await("the crash", millis = 12_000) { it == EngineEvent.Crashed }
            assertFalse(engine.isUsable)
            eventually("the stuck page's process gone", millis = 1_000) { cpuTicks(spinning) == null }
        },
    )

    /** Parks [engine]'s live page as the runtime does when its view leaves: unbound, hidden, no surface. */
    private fun CheckScope.park(engine: WebEngine) {
        stopRecording(engine)
        engine.client = null
        engine.setVisible(false)
        show(null)
    }

    /** A new view adopts [engine]'s parked page as the runtime does: subscribed anew, replayed, on screen, shown. */
    private fun CheckScope.adopt(engine: WebEngine): Recorder {
        val events = record(engine)
        engine.replayState()
        show(engine)
        engine.setVisible(true)
        return events
    }

    /** Keeping pages alive: a parked page, adopted by a new view, unreloaded (Task 26). */
    val keepAlive: List<Check> = listOf(
        Check("a-parked-page-is-adopted-live-with-its-state") {
            // A plugin's page: loaded with a request, so a same-document URL is an entry in WebKit's history too.
            val bundle = CheckBundle("plugin-kept")
            val engine = engine()
            val events = live(engine, bundle.content)
            val nonce = nonceOf(events.await("\$ready", matches = ::isReady))
            // What only this document has: a value, a scroll position, a same-document URL and a title.
            val steps = listOf(
                "window.__kept = 42",
                "document.body.style.height = '3000px'",
                "scrollTo(0, 600)",
                "history.pushState({}, '', '#kept')",
                "document.title = 'kept now'",
            )
            for (step in steps) assertEquals("\"ok\"", engine.evaluate("try { $step; 'ok' } catch (e) { String(e) }"), step)
            val moved = EngineEvent.UrlChanged("https://plugin-kept.invalid/index.html#kept")
            events.await("the same-document URL") { it == moved }
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
        Check("an-adopted-pages-first-paint-waits-for-the-screen") {
            val engine = engine()
            val content = page("parked")
            live(engine, content)
            park(engine)
            delay(500)
            val events = record(engine)
            // Adopted by a view that isn't on screen yet: the page is told, but it can't have been drawn there.
            engine.replayState()
            events.await("the replayed commit") { it == EngineEvent.UrlChanged(content.origin.url) }
            events.expectNone("a first paint off screen", from = 0) { it == EngineEvent.FirstPaint }
            show(engine)
            engine.setVisible(true)
            events.await("the first paint on screen") { it == EngineEvent.FirstPaint }
            assertNotNull(engine.snapshot(), "a snapshot, once drawn again")
        },
    )

    /** The page's requests: navigations, private schemes, windows, dialogs, permissions, files, downloads (Task 27). */
    val requests: List<Check> = listOf(
        Check("only-page-navigations-ask-the-client") {
            val engine = engine()
            val client = CheckClient(navigation = NavigationDecision.Block)
            engine.client = client
            val events = live(engine, page("nav"))
            val target = "${LocalServer.origin}/page"
            val before = events.events.size
            engine.evaluate("location.href = '$target'; 1")
            events.expectNone("a navigation the client blocked", from = before) { it == EngineEvent.UrlChanged(target) }
            assertTrue(client.asked.any { it is NavigationRequest && it.url == target && it.isMainFrame }, "${client.asked}")
        },
        Check("a-subframe-is-asked-about-as-one") {
            // W4: the frame is known at the response, so a subframe's navigation is asked as one, and then refused.
            val engine = engine()
            val client = CheckClient(navigation = NavigationDecision.Block)
            engine.client = client
            val events = record(engine)
            show(engine)
            start(engine, WebContent.Url("${LocalServer.origin}/framed"))
            events.await("first paint") { it == EngineEvent.FirstPaint }
            val sub = "${LocalServer.origin}/sub"
            eventually("the subframe asked about") { client.asked.any { it is NavigationRequest && it.url == sub && !it.isMainFrame } }
            assertTrue(client.asked.none { it is NavigationRequest && it.url.endsWith("/framed") }, "the engine's own load isn't asked")
        },
        Check("an-iframe-answered-first-cannot-take-the-main-frames-question") {
            // The review's exploit (I1): the page sends its main frame to a URL, then makes an iframe of the same URL; the
            // server answers the iframe at once and the main document later. The iframe's response, a subframe's, must
            // not stand in for the main frame's, which is asked about as the main frame's, and here refused.
            val engine = engine()
            val asked = mutableListOf<NavigationRequest>()
            engine.client = object : EngineClient by CheckClient() {
                override fun onNavigation(request: NavigationRequest): NavigationDecision {
                    asked += request
                    return if (request.isMainFrame) NavigationDecision.Block else NavigationDecision.Allow
                }
            }
            // A network page, as an attacker's: an iframe of http from an https page would be blocked as mixed content.
            val events = record(engine)
            show(engine)
            start(engine, WebContent.Url("${LocalServer.origin}/start"))
            events.await("first paint") { it == EngineEvent.FirstPaint }
            val target = "${LocalServer.origin}/race"
            LocalServer.raced.clear()
            val before = events.events.size
            engine.evaluate(
                "location.href = '$target'; setTimeout(function () { var f = document.createElement('iframe'); " +
                    "f.src = '$target'; document.body.appendChild(f); }, 200); 1",
            )
            try {
                eventually("the server answered the main document after the iframe", millis = 6_000) { LocalServer.raced.toList() == listOf("iframe", "document") }
            } catch (failure: AssertionError) {
                throw AssertionError("${failure.message}: the server answered ${LocalServer.raced.toList()}, the client was asked ${asked.toList()}")
            }
            eventually("the main frame asked about") { asked.any { it.url == target && it.isMainFrame } }
            events.expectNone("the main frame on the server's page", from = before, millis = 2_500) { it == EngineEvent.UrlChanged(target) }
            assertEquals("\"start\"", engine.evaluate("document.title"), "the page stayed")
        },
        Check("a-redirect-of-the-apps-own-load-is-followed-unasked") {
            // The final review's item 4: the server's redirect of the app's own load was asked about with no gesture,
            // and a blocking client left the view under its cover for good.
            val engine = engine()
            val client = CheckClient(navigation = NavigationDecision.Block)
            engine.client = client
            val events = record(engine)
            show(engine)
            start(engine, WebContent.Url("${LocalServer.origin}/moved"))
            events.await("the redirected page's commit") { it == EngineEvent.UrlChanged("${LocalServer.origin}/page") }
            events.await("its first paint") { it == EngineEvent.FirstPaint }
            assertTrue(client.asked.none { it is NavigationRequest }, "the app's own load's redirect was asked about: ${client.asked}")
        },
        Check("a-redirect-of-the-apps-own-load-off-the-web-ends-it-with-an-error") {
            val engine = engine()
            val client = CheckClient(navigation = NavigationDecision.Allow)
            engine.client = client
            val events = record(engine)
            show(engine)
            start(engine, WebContent.Url("${LocalServer.origin}/away"))
            val ended = events.await("the load's end") { it is EngineEvent.Error || it is EngineEvent.UrlChanged }
            assertEquals(EngineEvent.Error(WebError.BlockedByPolicy("myapp://callback?code=1")), ended)
            assertTrue(client.asked.none { it is NavigationRequest }, "${client.asked}")
        },
        Check("private-schemes-are-refused-whatever-the-client-says") {
            val engine = engine()
            engine.client = CheckClient(navigation = NavigationDecision.Allow)
            val events = live(engine, page("private"))
            val before = events.events.size
            engine.evaluate("location.href = 'webview-floor://reset/'; 1")
            engine.evaluate("location.href = 'webview-bundle://other.invalid/'; 1")
            events.expectNone("a navigation to a private scheme", from = before) { it is EngineEvent.UrlChanged }
        },
        Check("new-windows-ask-the-client") {
            val engine = engine()
            val client = CheckClient()
            engine.client = client
            val button = "<button style='position:fixed;left:0;top:0;width:100%;height:100%' " +
                "onclick=\"window.open('https://example.org/popup')\">open</button>"
            live(engine, solidPage("popup", "#ffffff", body = button))
            click(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            eventually("the client asked") {
                client.asked.any { it is NewWindowRequest && it.url == "https://example.org/popup" && it.isUserGesture }
            }
        },
        Check("dialogs-reach-the-client") {
            val engine = engine()
            val client = CheckClient(dialog = CompletableDeferred(DialogResult.Confirmed))
            engine.client = client
            live(engine, page("dialogs"))
            assertEquals("true", engine.evaluate("confirm('Sure?')"))
            assertEquals(WebDialog.Confirm("Sure?", page("dialogs").origin.url), client.asked.filterIsInstance<WebDialog>().single())
        },
        Check("reset-answers-a-pending-dialog") {
            val engine = engine()
            engine.client = CheckClient(dialog = CompletableDeferred())
            live(engine, page("stuck"))
            coroutineScope {
                // The alert holds the page's script until it is answered: the reset answers it, so the evaluation ends.
                val alert = async { engine.evaluate("alert('never answered'); 'done'") }
                delay(500)
                assertTrue(engine.reset(), "reset")
                withTimeout(5_000) { alert.await() }
            }
        },
        Check("permissions-ask-the-client") {
            // WebKit asks about notifications only from a user gesture ("Notification prompting can only be done from a
            // user gesture") and only in a persistent session: an ephemeral one (the checks' default) is denied at once,
            // never asked (WebNotificationClient::requestPermission). So a named profile, and a real click.
            val engine = engine(WebProfile.Named("permission-check"))
            val client = CheckClient()
            engine.client = client
            val button = "<button style='position:fixed;left:0;top:0;width:100%;height:100%' " +
                "onclick=\"Notification.requestPermission().then(function (p) { document.title = p; })\">ask</button>"
            val content = solidPage("permission", "#ffffff", body = button)
            live(engine, content)
            click(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            eventually("the page told no") { engine.evaluate("document.title") == "\"denied\"" }
            assertTrue(PermissionRequest(content.origin.origin, setOf(PermissionKind.Notifications)) in client.asked, "${client.asked}")
        },
        Check("a-file-chooser-asks-the-client-and-takes-its-files") {
            val picked = File(System.getProperty("user.home"), "pick.txt").apply { writeText("picked") }
            val asked = mutableListOf<FileChooserRequest>()
            val engine = engine()
            engine.client = object : EngineClient by CheckClient() {
                override suspend fun onFileChooser(request: FileChooserRequest): List<String> {
                    asked += request
                    return listOf(picked.toURI().toString())
                }
            }
            val input = "<input type=file id=f accept='text/plain' style='position:fixed;left:0;top:0;width:100%;height:100%'>"
            live(engine, solidPage("files", "#ffffff", body = input))
            click(SURFACE_WIDTH / 2, SURFACE_HEIGHT / 2)
            val name = "(function (f) { return f.files.length ? f.files[0].name : ''; })(document.getElementById('f'))"
            eventually("the chosen file") { engine.evaluate(name) == "\"pick.txt\"" }
            assertEquals(listOf(FileChooserRequest(listOf("text/plain"), allowMultiple = false)), asked)
        },
        Check("a-download-is-refused-and-the-client-told") {
            val engine = engine()
            val client = CheckClient()
            engine.client = client
            val events = live(engine, page("download"))
            val target = "${LocalServer.origin}/file.zip"
            engine.evaluate("location.href = '$target'; 1")
            eventually("the client told") { client.asked.any { it is DownloadRequest && it.url == target } }
            assertEquals("file.zip", client.asked.filterIsInstance<DownloadRequest>().single().suggestedFileName)
            events.expectNone("a commit of the download", from = 0, millis = 500) { it == EngineEvent.UrlChanged(target) }
        },
    )

    /**
     * The NSpid line of each of this JVM's WebKit web processes: one PID per PID namespace it is in. bubblewrap's
     * processes name the web process in their arguments too: a web process is one whose argv[0] it is.
     */
    private fun webProcessPids(): List<List<String>> = ProcessHandle.current().descendants().toList().mapNotNull { process ->
        val proc = File("/proc/${process.pid()}")
        val argv0 = runCatching { File(proc, "cmdline").readText().substringBefore('\u0000') }.getOrDefault("")
        if (!argv0.endsWith("/WPEWebProcess")) return@mapNotNull null
        runCatching { File(proc, "status").readLines() }.getOrDefault(emptyList())
            .firstOrNull { it.startsWith("NSpid:") }?.removePrefix("NSpid:")?.trim()?.split(Regex("\\s+"))
    }

    /** The runtime's own libraries, for a page: its libavif, which has the dav1d decoder only (after Task 29). */
    val runtime: List<Check> = listOf(
        Check("pages-run-in-webkits-sandbox-unless-it-is-off") {
            // WebKit's bubblewrap sandbox gives a page's process a PID namespace of its own: two PIDs, the host's and its
            // own. The sandbox is on unless the app opts out (WpeRuntime.SANDBOX_PROPERTY).
            val engine = engine()
            live(engine, page("sandboxed"))
            val pids = webProcessPids()
            assertTrue(pids.isNotEmpty(), "no web process of this JVM's")
            val expected = if (System.getProperty("dev.supermux.webview.wpe.sandbox") == "off") 1 else 2
            assertTrue(pids.all { it.size == expected }, "PID namespaces of the web processes: $pids, expected $expected each")
        },
        Check("an-avif-image-decodes") {
            // AVIF_HALVES, drawn unsmoothed over the whole surface. WebKit reads an AVIF's size from its header alone, so
            // it's the two colours on screen that show the image decoded.
            val engine = engine()
            val image = "<img src='data:image/avif;base64,$AVIF_HALVES' " +
                "style='position:fixed;left:0;top:0;width:100%;height:100%;image-rendering:pixelated'>"
            live(engine, solidPage("avif", "#ffffff", body = image))
            val size = "(function (i) { return i.naturalWidth + 'x' + i.naturalHeight; })(document.images[0])"
            assertEquals("\"16x16\"", engine.evaluate(size))
            var seen = 0 to 0
            try {
                eventually("the image's two halves on screen") {
                    seen = pixel(SURFACE_WIDTH / 4, SURFACE_HEIGHT / 2) to pixel(SURFACE_WIDTH * 3 / 4, SURFACE_HEIGHT / 2)
                    near(0xcc3333, seen.first, AVIF_TOLERANCE) && near(0x3366cc, seen.second, AVIF_TOLERANCE)
                }
            } catch (failure: AssertionError) {
                throw AssertionError("${failure.message}: #${seen.first.toString(16)} and #${seen.second.toString(16)}")
            }
        },
    )

    /** Every check, in the order of the tasks that brought them. */
    val all: List<Check> get() = bridge + surface + handoff + lifecycle + keepAlive + requests + runtime + hangs
}

/** A web server on the container's loopback, for what a page navigates to: WebKit asks about it at its response (W4). */
internal object LocalServer {
    private class Reply(val type: String, val body: String, val headers: Map<String, String> = emptyMap())

    private val replies = mapOf(
        "/page" to Reply("text/html; charset=utf-8", "<!doctype html><title>server</title><p>page</p>"),
        "/start" to Reply("text/html; charset=utf-8", "<!doctype html><title>start</title><p>start</p>"),
        "/framed" to Reply("text/html; charset=utf-8", "<!doctype html><title>framed</title><iframe src='/sub'></iframe>"),
        "/sub" to Reply("text/html; charset=utf-8", "<!doctype html><p>sub</p>"),
        "/file.zip" to Reply("application/zip", "PK-not-really", mapOf("Content-Disposition" to "attachment; filename=\"file.zip\"")),
    )

    /** What `/race` answered, in order: each request's `Sec-Fetch-Dest`, once its response went out. */
    val raced: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
            // A thread a request: /race holds a document while it answers an iframe.
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            // A login's cookies: one kept a day, one for the session only. /cookie?gone sets one an ephemeral profile drops.
            createContext("/cookie") { exchange ->
                if (exchange.requestURI.query == "gone") {
                    exchange.responseHeaders.add("Set-Cookie", "gone=e9x1; Max-Age=86400; Path=/")
                } else {
                    exchange.responseHeaders.add("Set-Cookie", "kept=v4f2; Max-Age=86400; Path=/")
                    exchange.responseHeaders.add("Set-Cookie", "session=s1; Path=/")
                }
                val body = "<!doctype html><title>cookie</title><p>cookie</p>".encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            // Server redirects: /moved to /page on this server, /away off the web.
            for ((path, location) in listOf("/moved" to "/page", "/away" to "myapp://callback?code=1")) {
                createContext(path) { exchange ->
                    exchange.responseHeaders.add("Location", location)
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }
            }
            createContext("/slow") { exchange ->
                Thread.sleep(2_000)
                val body = "<!doctype html><title>slow</title><body style='margin:0;background:transparent'><p>slow</p>".encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/race") { exchange ->
                val dest = exchange.requestHeaders.getFirst("Sec-Fetch-Dest") ?: "none"
                if (dest != "iframe") Thread.sleep(1_500)
                val body = "<!doctype html><title>raced</title><p>$dest</p>".encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                raced += dest
            }
            createContext("/") { exchange ->
                val reply = replies[exchange.requestURI.path]
                val body = (reply?.body ?: "not found").encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", reply?.type ?: "text/plain")
                reply?.headers?.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                exchange.sendResponseHeaders(if (reply != null) 200 else 404, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    /** `http://127.0.0.1:<port>`, the server started on first use. */
    val origin: String get() = "http://127.0.0.1:${server.address.port}"
}
