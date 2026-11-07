package dev.supermux.webview.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.webview.Activation
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The probe screen's background, the placeholder's colour, and the view's test tag: web-check reads them in pixels. */
private val PROBE_BACKGROUND = Color(0xFF203040)
private val PROBE_PLACEHOLDER = Color(0xFFFF00FF)
private const val PROBE_VIEW = "probe-view"

/** A page of one colour, #2e7d32, for the handoff. */
private const val HANDOFF_PAGE = "<!doctype html><html><body style=\"margin:0;background:#2e7d32\"></body></html>"

/** A page of one colour, #c62828, which the app may change (`color`) while the page is off screen. */
private const val RESHOW_PAGE = "<!doctype html><html><body style=\"margin:0;background:#c62828\"><script>" +
    "hostBridge.on('color', function (c) { document.body.style.background = c; });</script></body></html>"

/** A demo that takes the keyboard every 150 ms, and tells the app how many keys it got (`keys`). */
private const val THIEF_PAGE = "<!doctype html><html><body style=\"margin:0;background:#fff3e0\"><input id=\"i\" placeholder=\"thief\">" +
    "<script>var keys = 0; addEventListener('keydown', function () { keys++; hostBridge.post('keys', keys); }, true);" +
    "setInterval(function () { document.getElementById('i').focus(); }, 150);</script></body></html>"

/** A demo with a field of its own, which tells the app when it has the keyboard (`focused`) and what it holds (`typed`). */
private const val TARGET_PAGE = "<!doctype html><html><body style=\"margin:0;background:#e3f2fd\">" +
    "<input id=\"t\" placeholder=\"target\" style=\"display:block;width:280px;height:60px;font-size:20px\"><script>" +
    "var t = document.getElementById('t');" +
    "t.addEventListener('focus', function () { hostBridge.post('focused', true); });" +
    "t.addEventListener('input', function () { hostBridge.post('typed', t.value); });</script></body></html>"

/** A demo with a link, at its top, to a place far down in itself, which tells the app its fragment (`hash`). */
private const val ANCHOR_PAGE = "<!doctype html><html><body style=\"margin:0;background:#f1f8e9\">" +
    "<a id=\"in\" href=\"#here\" style=\"display:block;height:60px;font:20px sans-serif\">jump down</a>" +
    "<div style=\"height:2000px\"></div><p id=\"here\">here</p><script>" +
    "addEventListener('hashchange', function () { hostBridge.post('hash', location.hash); });</script></body></html>"

/**
 * The browser sample's probes, which `web-check/run.mjs` drives to check with real input and real frames what Karma
 * can't (`?screen=probe&case=…`). Each publishes `window.__webviewSample.probe`: `show()`, `hide()`, `color(css)` and
 * `state()`, its state as an object.
 * - `handoff`: a page appears, of one colour, over a placeholder of another: no frame may show neither.
 * - `reshow`: a page goes off screen, still bound, its colour changes, and it comes back: what its first frames show.
 * - `focus`: the app's own text field, a demo that takes the keyboard every 150 ms, and a demo with a field of its own;
 *   `focus-control`: the same, with a demo that never takes it.
 * - `anchor`: a demo with a link to a place in itself.
 */
@Composable
internal fun ProbeScreen(case: String) {
    Box(Modifier.fillMaxSize().background(PROBE_BACKGROUND)) {
        when (case) {
            "handoff" -> HandoffProbe()
            "reshow" -> ReshowProbe()
            "focus" -> FocusProbe(steals = true)
            "focus-control" -> FocusProbe(steals = false)
            "anchor" -> AnchorProbe()
            else -> BasicText("No probe '$case'.", style = TextStyle(color = Color.White))
        }
    }
}

@Composable
private fun Placeholder() = Box(Modifier.fillMaxSize().background(PROBE_PLACEHOLDER))

@Composable
private fun HandoffProbe() {
    var shown by remember { mutableStateOf(false) }
    val state = remember { WebViewState(WebContent.Html(HANDOFF_PAGE)) }
    DisposableEffect(Unit) {
        publishProbe(
            show = { shown = true },
            hide = { shown = false },
            setColor = {},
            state = { buildJsonObject { put("phase", state.phase.toString()) }.toString() },
        )
        onDispose {}
    }
    if (shown) {
        WebView(
            state,
            Modifier.offset(40.dp, 40.dp).size(320.dp, 240.dp).testTag(PROBE_VIEW),
            activation = Activation.Eager,
            placeholder = { Placeholder() },
        )
    }
}

@Composable
private fun ReshowProbe() {
    var onScreen by remember { mutableStateOf(true) }
    val state = remember { WebViewState(WebContent.Html(RESHOW_PAGE)) }
    DisposableEffect(Unit) {
        publishProbe(
            show = { onScreen = true },
            hide = { onScreen = false },
            setColor = { state.bridge.post("color", JsonPrimitive(it)) },
            state = { buildJsonObject { put("phase", state.phase.toString()); put("onScreen", onScreen) }.toString() },
        )
        onDispose {}
    }
    // Off screen, the view stays composed and bound: its engine is told it is hidden, and its frame draws nothing.
    WebView(
        state,
        Modifier.offset(if (onScreen) 40.dp else 2_000.dp, 40.dp).size(320.dp, 240.dp).testTag(PROBE_VIEW),
        activation = Activation.Eager,
        placeholder = { Placeholder() },
    )
}

@Composable
private fun FocusProbe(steals: Boolean) {
    var text by remember { mutableStateOf("") }
    var stolen by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var targetFocused by remember { mutableStateOf(false) }
    // The control's demo is the same page, but it never takes the keyboard.
    val thief = remember { WebViewState(WebContent.Html(if (steals) THIEF_PAGE else THIEF_PAGE.replace("setInterval", "void"))) }
    val target = remember { WebViewState(WebContent.Html(TARGET_PAGE)) }
    DisposableEffect(Unit) {
        val stops = listOf(
            thief.bridge.on("keys") { stolen = it.jsonPrimitive.int },
            target.bridge.on("focused") { targetFocused = true },
            target.bridge.on("typed") { typed = it.jsonPrimitive.content },
        )
        publishProbe(
            show = {},
            hide = {},
            setColor = {},
            state = {
                buildJsonObject {
                    put("text", text)
                    put("stolen", stolen)
                    put("typed", typed)
                    put("targetFocused", targetFocused)
                    put("thief", thief.phase.toString())
                    put("target", target.phase.toString())
                }.toString()
            },
        )
        onDispose { stops.forEach { it() } }
    }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        BasicTextField(
            text,
            { text = it },
            Modifier.width(320.dp).height(48.dp).background(Color.White).padding(8.dp).testTag("probe-field"),
            textStyle = TextStyle(fontSize = 20.sp),
        )
        WebView(thief, Modifier.size(320.dp, 100.dp).testTag("probe-thief"), activation = Activation.Eager)
        WebView(target, Modifier.size(320.dp, 100.dp).testTag("probe-target"), activation = Activation.Eager)
    }
}

@Composable
private fun AnchorProbe() {
    var hash by remember { mutableStateOf("") }
    val state = remember { WebViewState(WebContent.Html(ANCHOR_PAGE)) }
    DisposableEffect(Unit) {
        val stop = state.bridge.on("hash") { hash = it.jsonPrimitive.content }
        publishProbe(
            show = {},
            hide = {},
            setColor = {},
            state = { buildJsonObject { put("phase", state.phase.toString()); put("hash", hash) }.toString() },
        )
        onDispose { stop() }
    }
    WebView(state, Modifier.offset(40.dp, 40.dp).size(320.dp, 240.dp).testTag(PROBE_VIEW), activation = Activation.Eager)
}

private fun publishProbe(show: () -> Unit, hide: () -> Unit, setColor: (String) -> Unit, state: () -> String): Unit = js("""{
  window.__webviewSample.probe = {
    show: function () { show(); },
    hide: function () { hide(); },
    color: function (c) { setColor(String(c)); },
    state: function () { return JSON.parse(state()); }
  };
}""")
