package dev.supermux.webview.engine.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.Activation
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebSettings
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.WebViewState
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** A load that only changes the fragment (review item 2), on the phone, through the runtime as an app uses it. */
@RunWith(AndroidJUnit4::class)
class EngineFragmentLoadTest {
    /** At `…#a`, `navigator.load("…#b")`: the view ends Live, and a bridge call still reaches the page. */
    @Test fun aLoadThatOnlyChangesTheFragmentEndsLiveAndKeepsItsBridge() = TestServer().use { server ->
        server.page(
            "/echo",
            """<!doctype html><html><head><title>echo</title>
<script>hostBridge.handle('echo', function (p) { return p; });</script></head>
<body style="font:16px sans-serif"><p id="a">a</p><div style="height:3000px"></div><p id="b">b</p></body></html>""",
        )
        val state = WebViewState(WebContent.Url("${server.origin}/echo#a"))
        runtimeOnAndroid(
            content = {
                WebView(
                    state,
                    Modifier.fillMaxSize(),
                    settings = WebSettings(bridgeOrigins = setOf(server.origin)),
                    activation = Activation.WhenVisible(settleMillis = 0),
                )
            },
        ) {
            waitFor("Live at #a") { state.phase == WebViewPhase.Live }
            assertEquals(JsonPrimitive("one"), onMain { state.bridge.call("echo", JsonPrimitive("one")) })
            onMain { state.navigator.load("${server.origin}/echo#b") }
            waitFor("the commit at #b") { state.url == "${server.origin}/echo#b" }
            waitFor("Live at #b", millis = 5_000) { state.phase == WebViewPhase.Live }
            assertEquals(JsonPrimitive("two"), onMain { state.bridge.call("echo", JsonPrimitive("two"), timeoutMillis = 5_000) })
            assertEquals(1, server.requested.count { it == "/echo" }, "the page itself loaded once: ${server.requested}")
        }
    }
}
