package dev.supermux.webview.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.webview.Activation
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebClient
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebSizing
import dev.supermux.webview.WebView
import dev.supermux.webview.rememberWebViewState

/**
 * The user-gesture probe (launched with `-gesture-probe`, driven by device-checks/ios-sim.sh): an embed in a scrolling
 * column, as in a chat, whose page tries to leave the app by itself every 250 ms for its first 8 s, and has a link. Its
 * client blocks every navigation and counts them: those the engine calls the user's, and the page's own. A drag that
 * scrolls the column over the page must leave every one of the page's own; a tap on the link is the user's.
 */
@Composable
fun GestureProbe() {
    var own by remember { mutableStateOf(0) }
    var theUsers by remember { mutableStateOf(listOf<String>()) }
    val client = remember {
        object : WebClient {
            override fun onNavigation(request: NavigationRequest, content: WebContent): NavigationDecision {
                if (request.isUserGesture) theUsers = theUsers + request.url else own++
                return NavigationDecision.Block
            }
        }
    }
    val state = rememberWebViewState(WebContent.Html(PROBE_PAGE))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "page's own $own · the user's ${theUsers.size}: ${theUsers.joinToString()}",
            Modifier.padding(16.dp).testTag("probe-status"),
        )
        WebView(
            state,
            Modifier.fillMaxWidth().testTag("probe-page"),
            client = client,
            activation = Activation.Eager,
            sizing = WebSizing.WrapContentHeight(max = 360.dp, initial = 320.dp),
        )
        // Something to scroll over the page.
        Spacer(Modifier.height(1600.dp))
    }
}

private const val PROBE_PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
<title>probe</title></head><body style="margin:0;padding:16px;height:300px;box-sizing:border-box;font:16px system-ui;background:#fff3cd">
<p id="state">trying to leave by itself</p>
<p><a href="https://example.com/link" style="font-size:28px">Open example.com</a></p>
<script>
var tries = 0, timer = setInterval(function () {
  if (++tries > 32) { clearInterval(timer); document.getElementById('state').textContent = 'stopped trying'; return; }
  location.href = 'https://example.org/own';
}, 250);
</script></body></html>"""
