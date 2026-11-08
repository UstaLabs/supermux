package dev.supermux.webview.engine.linux

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResource
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.engine.webkit.WebKitUrls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class LinuxWebEngineSchemeTest {
    private val bundle = WebContent.Bundle(
        WebOrigin("app"),
        WebResourceProvider { path ->
            if (path == "index.html") WebResource("<p/>".encodeToByteArray(), "text/html") else null
        },
    )

    @Test fun anotherBundleFailsAsAnUnknownHostAndWhatIsMissingIsA404() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(bundle, WebKitUrls.bundleUrl(bundle.origin, "index.html"))
        rig.engine.onEvent(WpeEvent.Scheme(3, WebKitUrls.bundleUrl(WebOrigin("other"), "index.html")))
        rig.engine.onEvent(WpeEvent.Scheme(4, WebKitUrls.bundleUrl(bundle.origin, "missing.js")))
        rig.engine.onEvent(WpeEvent.Scheme(5, WebKitUrls.bundleUrl(bundle.origin, "%2e%2e%2fsecret")))
        assertEquals(0, rig.view.finished[3]?.first, "a load error")
        assertEquals(404, rig.view.finished[4]?.first)
        assertEquals(404, rig.view.finished[5]?.first, "a path normalizeBundlePath refuses")
        rig.close()
    }

    @Test fun aFileCarriesItsTypeAndItsOwnHeaders() {
        val resource = WebResource(
            ByteArray(0), "text/html",
            mapOf(
                "Content-Security-Policy" to "default-src 'self'",
                "Content-Type" to "text/plain",
                "Content-Length" to "9",
                "Bad\nName" to "v",
            ),
        )
        assertEquals("text/html\nContent-Security-Policy: default-src 'self'", LinuxWebEngine.typeAndHeaders(resource))
    }
}
