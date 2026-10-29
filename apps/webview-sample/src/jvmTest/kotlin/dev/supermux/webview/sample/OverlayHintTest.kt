package dev.supermux.webview.sample

import kotlin.test.Test
import kotlin.test.assertEquals

/** The overlay demo says what each engine does with Compose content over its page. */
class OverlayHintTest {
    @Test fun eachEngineGetsTheHintForWhatItDoesWithOverlays() {
        assertEquals(FAKE_ENGINE_HINT, overlayHint(FAKE_ENGINE_LABEL), "the fake engine outlines its cut-outs")
        assertEquals(NATIVE_VIEW_HINT, overlayHint(ANDROID_ENGINE_LABEL), "Compose draws over Android's WebView")
        assertEquals(CUTOUT_HINT, overlayHint("iframe engine"), "the web's iframe sits above Compose")
    }
}
