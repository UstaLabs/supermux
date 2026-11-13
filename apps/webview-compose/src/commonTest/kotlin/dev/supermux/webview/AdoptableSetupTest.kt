package dev.supermux.webview

import androidx.compose.ui.graphics.Color
import dev.supermux.webview.engine.pageSetup
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which views may adopt a live page as it is: what a document set when it started, it keeps. */
class AdoptableSetupTest {
    private val started = WebSettings.Default.pageSetup(dark = false, bridgeOrigins = setOf("https://a.test"))

    @Test fun aThemeTheInspectorOrMoreBridgeOriginsALivePageTakesOn() {
        assertTrue(started.adoptableBy(started))
        assertTrue(started.adoptableBy(started.copy(dark = true, inspectable = true)))
        assertTrue(started.adoptableBy(started.copy(bridgeOrigins = started.bridgeOrigins + "https://b.test")))
    }

    /** The colour behind a transparent page follows the app's theme mid-lease, as the theme does. */
    @Test fun anotherUnderlayALivePageTakesOn() {
        val clear = started.copy(transparentBackground = true, underlay = Color.White)
        assertTrue(clear.adoptableBy(clear.copy(underlay = Color.Black)))
        assertTrue(clear.adoptableBy(clear.copy(underlay = null)))
    }

    /** A view with scripts off never adopts a page running them, nor the other way round. */
    @Test fun scriptsOnOrOffAreTheDocuments() {
        assertFalse(started.adoptableBy(started.copy(javaScriptEnabled = false)))
        assertFalse(started.copy(javaScriptEnabled = false).adoptableBy(started))
    }

    @Test fun theUserAgentAndTheBackgroundAreTheDocuments() {
        assertFalse(started.adoptableBy(started.copy(userAgentSuffix = "demo/2")))
        assertFalse(started.adoptableBy(started.copy(userAgent = "Phone/1")))
        assertFalse(started.adoptableBy(started.copy(desktopSite = true)))
        assertFalse(started.adoptableBy(started.copy(transparentBackground = true)))
    }

    /** A page whose origin left the bridge origins would talk to a bridge that no longer takes it. */
    @Test fun aBridgeOriginTheViewNoLongerListsIsTheDocuments() {
        assertFalse(started.adoptableBy(started.copy(bridgeOrigins = emptySet())))
        assertFalse(started.adoptableBy(started.copy(bridgeOrigins = setOf("https://b.test"))))
    }
}
