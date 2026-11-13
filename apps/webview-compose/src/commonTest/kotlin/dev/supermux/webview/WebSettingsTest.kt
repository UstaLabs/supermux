package dev.supermux.webview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.pageSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebSettingsTest {
    private val page = WebContent.Url("https://a.test/")
    private val demo = WebContent.Html("<p/>", WebOrigin("demo"))

    @Test fun htmlDefaultsToAnEphemeralProfileUnlessSettingsNameOne() {
        assertEquals(WebProfile.Ephemeral, WebSettings.Default.profileFor(demo))
        assertEquals(WebProfile.Default, WebSettings.Default.profileFor(page))
        assertEquals(WebProfile.Named("x"), WebSettings(profile = WebProfile.Named("x")).profileFor(demo))
    }

    @Test fun aPageSetupTakesThePerLeaseSettingsAndTheResolvedValues() {
        val settings = WebSettings(
            javaScriptEnabled = false,
            inspectable = true,
            darkMode = DarkMode.Light,
            transparentBackground = true,
            userAgentSuffix = "demo/1",
            profile = WebProfile.Named("x"),
            mediaPlaybackRequiresGesture = false,
            bridgeOrigins = setOf("https://ignored.test"),
            userAgent = "Phone/1",
        )
        assertEquals(
            PageSetup(
                javaScriptEnabled = false,
                inspectable = true,
                dark = true,
                transparentBackground = true,
                userAgentSuffix = "demo/1",
                bridgeOrigins = setOf("https://p.invalid"),
                userAgent = "Phone/1",
            ),
            settings.pageSetup(dark = true, bridgeOrigins = setOf("https://p.invalid")),
        )
    }

    @Test fun namedProfilesTakeOnlyIdsEveryEngineAccepts() {
        WebProfile.Named("work_1-A")
        listOf("", "has space", "../x", "a".repeat(65), "ü").forEach {
            assertFailsWith<IllegalArgumentException>(it) { WebProfile.Named(it) }
        }
    }

    /** Task 16b: the bridge compares origins exactly, so one written any other way than a browser's would never match. */
    @Test fun bridgeOriginsMustBeOriginsAsBrowsersWriteThem() {
        WebSettings(bridgeOrigins = setOf("https://app.example.com", "http://localhost:8080", "https://[::1]:8443"))
        val bad = listOf(
            "https://app.example.com/", "https://app.example.com/path", "https://app.example.com:443",
            "HTTPS://App.Example.com", "app.example.com", "https://", "https://host:", "https://host:port",
            "https://user@host", " https://app.example.com", "null", "*",
        )
        for (origin in bad) {
            val e = assertFailsWith<IllegalArgumentException>(origin) { WebSettings(bridgeOrigins = setOf(origin)) }
            assertTrue("'$origin'" in e.message.orEmpty() && "scheme://host[:port]" in e.message.orEmpty(), e.message)
        }
        val slash = assertFailsWith<IllegalArgumentException> { WebSettings(bridgeOrigins = setOf("https://app.example.com/")) }
        assertTrue("Did you mean 'https://app.example.com'?" in slash.message.orEmpty(), slash.message)
    }

    @Test fun activationAndSizingRejectNonsense() {
        assertFailsWith<IllegalArgumentException> { Activation.WhenVisible(settleMillis = -1) }
        assertFailsWith<IllegalArgumentException> { WebSizing.WrapContentHeight(max = 0.dp) }
        assertFailsWith<IllegalArgumentException> { WebSizing.WrapContentHeight(max = 100.dp, initial = (-1).dp) }
    }

    /** #122's device mode: a whole user agent in place of the engine's, the suffix still after it. */
    @Test fun aUserAgentReplacesTheEnginesAndKeepsTheSuffix() {
        val base = "Mozilla/5.0 Engine"
        assertEquals(null, WebSettings.Default.pageSetup(dark = false, bridgeOrigins = emptySet()).agent(base))
        assertEquals("Mozilla/5.0 Engine demo/1", WebSettings(userAgentSuffix = "demo/1").pageSetup(false, emptySet()).agent(base))
        assertEquals("Phone/1", WebSettings(userAgent = "Phone/1").pageSetup(false, emptySet()).agent(base))
        assertEquals("Phone/1 demo/1", WebSettings(userAgent = "Phone/1", userAgentSuffix = "demo/1").pageSetup(false, emptySet()).agent(base))
        assertEquals("demo/1", WebSettings(userAgentSuffix = "demo/1").pageSetup(false, emptySet()).agent(null))
    }

    @Test fun theUserAgentIsPartOfEqualityAndCopy() {
        assertEquals(WebSettings(userAgent = "a"), WebSettings.Default.copy(userAgent = "a"))
        assertTrue(WebSettings(userAgent = "a") != WebSettings(userAgent = "b"))
    }

    /** #122 2c: a phone's "Desktop site" is a per-lease setting the engines read. */
    @Test fun aDesktopSiteIsPartOfThePageSetup() {
        assertEquals(false, WebSettings.Default.pageSetup(false, emptySet()).desktopSite)
        assertEquals(true, WebSettings(desktopSite = true).pageSetup(false, emptySet()).desktopSite)
        assertTrue(WebSettings(desktopSite = true) != WebSettings.Default)
        assertEquals(WebSettings(desktopSite = true), WebSettings.Default.copy(desktopSite = true))
    }

    /** What Android does for it, as Chrome Android's Desktop site: a desktop Chrome's agent and a 1280 px layout. */
    @Test fun theDesktopSiteIsADesktopAgentAndAWideLayout() {
        assertTrue("Mobile" !in dev.supermux.webview.engine.DesktopSite.AGENT && "Android" !in dev.supermux.webview.engine.DesktopSite.AGENT)
        assertTrue("X11; Linux x86_64" in dev.supermux.webview.engine.DesktopSite.AGENT)
        assertTrue("width=1280" in dev.supermux.webview.engine.DesktopSite.VIEWPORT_SCRIPT)
    }

    /**
     * Where a page lies under the Compose canvas (macOS, iOS, the web), a transparent page shows what is under the canvas,
     * not what the app drew behind the view: the black of a Mac window's backing behind an agent's card in a light chat.
     * The app names the colour it draws there, and the setup carries it to the engine for a transparent page only.
     */
    @Test fun anUnderlayReachesTheSetupOfATransparentPageOnly() {
        val chat = Color(0xFFF4F4F5)
        assertEquals(null, WebSettings.Default.underlay)
        assertEquals(null, WebSettings(transparentBackground = true).pageSetup(false, emptySet()).underlay, "none unless named")
        assertEquals(chat, WebSettings(transparentBackground = true, underlay = chat).pageSetup(false, emptySet()).underlay)
        assertEquals(null, WebSettings(underlay = chat).pageSetup(false, emptySet()).underlay, "an opaque page paints its own")
    }

    @Test fun theUnderlayIsPartOfEqualityAndCopy() {
        val chat = Color(0xFFF4F4F5)
        assertEquals(WebSettings(underlay = chat), WebSettings.Default.copy(underlay = chat))
        assertEquals(WebSettings(underlay = chat).hashCode(), WebSettings.Default.copy(underlay = chat).hashCode())
        assertTrue(WebSettings(underlay = chat) != WebSettings(underlay = Color.Black))
        assertTrue(WebSettings(underlay = chat) != WebSettings.Default)
        assertEquals(chat, WebSettings(underlay = chat).copy(darkMode = DarkMode.Dark).underlay, "kept by a copy that names another")
        assertTrue("underlay=" in WebSettings(underlay = chat).toString())
    }

    /** It stands for what the app draws behind the view, so it hides what the canvas has under it: an opaque colour. */
    @Test fun anUnderlayIsAnOpaqueColour() {
        for (bad in listOf(Color.Unspecified, Color.Transparent, Color.White.copy(alpha = 0.5f))) {
            val e = assertFailsWith<IllegalArgumentException>("$bad") { WebSettings(underlay = bad) }
            assertTrue("underlay" in e.message.orEmpty(), e.message)
        }
    }
}
