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

/** Waits until [condition] holds, checking once a frame, for [timeoutMillis] of real time at most. */
internal suspend fun waitUntil(what: String, timeoutMillis: Int = 10_000, condition: () -> Boolean) {
    val deadline = now() + timeoutMillis
    while (!condition()) {
        check(now() < deadline) { "timed out after $timeoutMillis ms waiting for $what" }
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
