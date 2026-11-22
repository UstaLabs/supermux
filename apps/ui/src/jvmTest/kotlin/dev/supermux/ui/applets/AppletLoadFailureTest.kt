package dev.supermux.ui.applets

import dev.supermux.webview.WebError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #117: an applet page that couldn't load says why in its window, never a blank one. */
class AppletLoadFailureTest {
    @Test fun aNetworkOrCertificateFailureIsSaidInTheEnginesWords() {
        // macOS refusing plain http for a direct-http host (App Transport Security), as WebKit words it.
        val refused = WebError.Network(
            "http://192.168.1.5:9898/a/notes/",
            "The resource could not be loaded because the App Transport Security policy requires the use of a secure connection",
        )
        assertEquals(
            "The resource could not be loaded because the App Transport Security policy requires the use of a secure connection.",
            appletLoadFailure(refused),
        )
        assertEquals("The certificate is invalid.", appletLoadFailure(WebError.Certificate("https://h.test/a/x/", "The certificate is invalid.")))
    }

    @Test fun aLoadedPageOrTheServersOwnErrorPageSaysNothing() {
        assertNull(appletLoadFailure(null))
        assertNull(appletLoadFailure(WebError.Http("https://h.test/a/x/", 401)))
    }
}
