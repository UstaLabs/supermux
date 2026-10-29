package dev.supermux.webview

import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.pageSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test fun activationAndSizingRejectNonsense() {
        assertFailsWith<IllegalArgumentException> { Activation.WhenVisible(settleMillis = -1) }
        assertFailsWith<IllegalArgumentException> { WebSizing.WrapContentHeight(max = 0.dp) }
        assertFailsWith<IllegalArgumentException> { WebSizing.WrapContentHeight(max = 100.dp, initial = (-1).dp) }
    }
}
