package dev.supermux.webview.engine.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.FindResult
import dev.supermux.webview.InspectorResult
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebCookie
import dev.supermux.webview.engine.EngineEvent
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The browser operations (find, zoom, cookies, the inspector; #105 plan 2b) on a real WebView, on the phone. */
@RunWith(AndroidJUnit4::class)
class EngineBrowserOpsTest {
    private val manyMatches = WebContent.Html(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>find</title></head>
<body><p>needle one</p><p>needle two</p><p>needle three</p><p>hay</p></body></html>""",
    )

    @Test fun findCountsMatchesAndStepsThroughThem() = EngineHarness.run {
        loadAndPaint(manyMatches)
        assertEquals(FindResult(found = true, count = 3, current = 1), main { find("needle", backwards = false) })
        assertEquals(FindResult(found = true, count = 3, current = 2), main { find("needle", backwards = false) })
        assertEquals(FindResult(found = true, count = 3, current = 1), main { find("needle", backwards = true) })
        assertEquals(FindResult(found = true, count = 3, current = 3), main { find("needle", backwards = true) })
    }

    @Test fun findOfNothingIsNotFoundAndANewQueryStartsOver() = EngineHarness.run {
        loadAndPaint(manyMatches)
        assertEquals(FindResult(found = false, count = 0, current = null), main { find("absent", backwards = false) })
        assertEquals(FindResult(found = true, count = 1, current = 1), main { find("hay", backwards = false) })
        main { clearFind() }
        assertEquals(FindResult(found = true, count = 3, current = 1), main { find("needle", backwards = false) })
        assertNull(main { find("", backwards = false) })
    }

    @Test fun aCookieSetBeforeALoadReachesTheServersRequest() = TestServer().use { server ->
        server.page("/secret", titledPage("secret"))
        EngineHarness.run {
            val url = server.origin + "/secret"
            assertTrue(main { setCookie(WebCookie("cmux_preview", "tok123", url, path = "/", secure = false)) })
            loadAndPaint(WebContent.Url(url))
            seenOrAwait("the title") { it == EngineEvent.TitleChanged("secret") }
            assertEquals("cmux_preview=tok123", server.cookies.last())
        }
    }

    @Test fun aCookieThatCouldInjectAnAttributeIsRefused() = EngineHarness.run {
        assertFalse(main { setCookie(WebCookie("a", "b; Domain=evil.test", "http://127.0.0.1/", secure = false)) })
    }

    @Test fun zoomSetsTheTextZoom() = EngineHarness.run {
        loadAndPaint(manyMatches)
        assertTrue(main { setZoom(1.5f) })
        assertEquals(150, main { webView.settings.textZoom })
        assertTrue(main { setZoom(1f) })
        assertEquals(100, main { webView.settings.textZoom })
    }

    @Test fun theInspectorIsElsewhereOnlyForAnInspectablePage() = EngineHarness.run {
        main { configure(setupFor(manyMatches).copy(inspectable = false)) }
        assertEquals(InspectorResult.Unsupported, main { openInspector() })
        main { configure(setupFor(manyMatches).copy(inspectable = true)) }
        assertEquals(
            InspectorResult.Elsewhere("chrome://inspect on a computer, with the phone connected"),
            main { openInspector() },
        )
        main { configure(setupFor(manyMatches).copy(inspectable = false)) }
    }
}
