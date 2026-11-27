package dev.supermux.webview.engine.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import kotlinx.coroutines.await
import org.w3c.dom.HTMLElement
import kotlin.js.Promise

private fun newViewportContainer(width: Int, height: Int): HTMLElement = js("""(function () {
  var d = document.createElement('div');
  d.setAttribute('data-test-viewport', '');
  d.style.position = 'fixed';
  d.style.left = '0px';
  d.style.top = '0px';
  d.style.width = width + 'px';
  d.style.height = height + 'px';
  document.body.appendChild(d);
  return d;
})()""")

private fun animationFrame(): Promise<JsAny?> =
    js("new Promise(function (resolve) { requestAnimationFrame(function () { resolve(null); }); })")

private fun timeout(millis: Int): Promise<JsAny?> = js("new Promise(function (resolve) { setTimeout(resolve, millis); })")

/** Milliseconds on the page's clock (`performance.now()`). */
internal fun now(): Double = js("performance.now()")

/** Waits for [count] animation frames of real time. Compose draws in animation frames. */
internal suspend fun frames(count: Int = 1) {
    repeat(count) { animationFrame().await<JsAny?>() }
}

/** Waits [millis] of real time. */
internal suspend fun pause(millis: Int) {
    timeout(millis).await<JsAny?>()
}

/** How many times the app's window lost the keyboard (to a frame, or away), counted from the first call. */
private fun windowBlurs(): Int = js("""(function () {
  if (window.__blurs === undefined) { window.__blurs = 0; window.addEventListener('blur', function () { window.__blurs++; }); }
  return window.__blurs;
})()""")

/** What the frames said their focus() did since the test began ([dev.supermux.webview.bridge.logFocus] clears it). */
private fun focusTries(): String = js("(function () { var t = (window.__focusTries || []).join(' '); return t || 'none'; })()")

/** Whether a frame told it called focus() and the browser left its document without the focus (DRIVER's focusTried). */
internal fun aFrameSaidItsFocusFailed(): Boolean =
    js("(window.__focusTries || []).some(function (t) { return JSON.parse(t).hasFocus === false; })")

private fun activeName(): String = js("String(document.activeElement && document.activeElement.localName)")

/** Each iframe's address and whether its permissions policy lets it take the focus without the user's activation. */
private fun framePolicies(): String = js(
    """(function () {
      var out = [];
      document.querySelectorAll('iframe').forEach(function (f) {
        var p = f.featurePolicy || f.permissionsPolicy;
        var allows = p && p.allowsFeature ? String(p.allowsFeature('focus-without-user-activation')) : 'n/a';
        out.push(String(f.src || f.getAttribute('src') || '').slice(0, 40) + ' allow=' + (f.getAttribute('allow') || '') + ' focusWithoutActivation=' + allows);
      });
      return out.join(' | ');
    })()""",
)

/** Waits until [condition] holds, checking once a frame, for [timeoutMillis] of real time at most. */
internal suspend fun waitUntil(what: String, timeoutMillis: Int = 10_000, condition: () -> Boolean) {
    val deadline = now() + timeoutMillis
    // What had the keyboard while it waited (#166: did a frame get it and lose it, or never get it?).
    val seen = LinkedHashSet<String>()
    val blursBefore = windowBlurs()
    while (!condition()) {
        seen += activeName()
        if (now() >= deadline) {
            // The build's output names only the exception's type: say what it was, and where the focus was.
            val message = "timed out after $timeoutMillis ms waiting for $what"
            dev.supermux.webview.bridge.testLog(
                "$message; ${dev.supermux.webview.bridge.focusState()}; active while waiting: $seen; window blurs: ${windowBlurs() - blursBefore}; the frames said: ${focusTries()}; frames: ${framePolicies()}",
            )
            error(message)
        }
        frames(1)
    }
}

/**
 * A real Compose viewport in the test page, [WIDTH] × [HEIGHT] CSS px at its top-left. Headless Chrome's density is 1,
 * so window px, CSS px and dp are the same numbers here.
 *
 * `runComposeUiTest` draws off screen and provides no interop container (plan fact F6), so web views are tested in
 * this one. One viewport serves the whole run, as browsers cap WebGL contexts: each test shows its content in it with
 * [show], and [clear]s it after.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal object TestViewport {
    const val WIDTH: Int = 800
    const val HEIGHT: Int = 600

    private var content: @Composable () -> Unit by mutableStateOf<@Composable () -> Unit>({})
    private var composed: Any? = null
    private var container: HTMLElement? = null

    /** The element `ComposeViewport` was given. */
    val element: HTMLElement get() = checkNotNull(container) { "nothing was shown yet" }

    /** Shows [body], alone, and returns once Compose has composed it and drawn two more frames. */
    suspend fun show(body: @Composable () -> Unit) {
        if (container == null) {
            val created = newViewportContainer(WIDTH, HEIGHT)
            container = created
            ComposeViewport(created) {
                val current = content
                Box(Modifier.fillMaxSize()) { current() }
                SideEffect { composed = current }
            }
        }
        content = body
        // The first show waits for skiko's wasm too.
        waitUntil("Compose to compose the test's content", timeoutMillis = 30_000) { composed === body }
        frames(2)
    }

    /** Shows nothing, so whatever the last test showed leaves the composition. */
    suspend fun clear() = show(EMPTY)

    private val EMPTY: @Composable () -> Unit = {}
}
