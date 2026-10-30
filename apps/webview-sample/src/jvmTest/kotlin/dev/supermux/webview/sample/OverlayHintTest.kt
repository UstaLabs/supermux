package dev.supermux.webview.sample

import kotlin.test.Test
import kotlin.test.assertEquals

/** The overlay demo says what each engine does with Compose content over its page. */
class OverlayHintTest {
    @Test fun eachEngineGetsTheHintForWhatItDoesWithOverlays() {
        assertEquals(FAKE_ENGINE_HINT, overlayHint(FAKE_ENGINE_LABEL), "the fake engine outlines its cut-outs")
        assertEquals(NATIVE_VIEW_HINT, overlayHint(ANDROID_ENGINE_LABEL), "Compose draws over Android's WebView")
        assertEquals(NATIVE_VIEW_HINT, overlayHint(IOS_ENGINE_LABEL), "Compose draws over iOS's WKWebView")
        assertEquals(NATIVE_VIEW_HINT, overlayHint(LINUX_ENGINE_LABEL), "Compose draws over WPE's frames")
        assertEquals(CUTOUT_HINT, overlayHint("iframe engine"), "the web's iframe sits above Compose")
        assertEquals(MAC_HINT, overlayHint(MACOS_ENGINE_LABEL), "Compose draws over the Mac's page, whose marked overlays keep their clicks")
    }
}
