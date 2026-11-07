package dev.supermux.webview

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
        )
        assertEquals(
            PageSetup(
                javaScriptEnabled = false,
                inspectable = true,
                dark = true,
                transparentBackground = true,
                userAgentSuffix = "demo/1",
                bridgeOrigins = setOf("https://p.invalid"),
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
}
