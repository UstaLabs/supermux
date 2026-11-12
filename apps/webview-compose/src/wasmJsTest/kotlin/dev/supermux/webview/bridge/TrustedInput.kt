package dev.supermux.webview.bridge

import kotlinx.coroutines.await
import kotlin.js.Promise

// Real, trusted input for the click-to-send tests, made by Chrome itself through the DevTools protocol (Karma's
// /webview-test/click and /webview-test/key, karma.config.d/webview-trusted-input.js). A test can't make a trusted
// event from JavaScript: el.click() and dispatchEvent are untrusted, which is the point.

/** An iframe pinned to the top-left of the viewport, above everything, so a click at its place hits it. */
internal fun pinnedFrame(left: Int = 0, width: Int = 320, height: Int = 240): JsAny =
    js("(function () { var f = document.createElement('iframe'); f.setAttribute('data-click-test', ''); f.style.cssText = 'position:fixed;top:0;border:0;background:#fff;z-index:2147483647;left:' + left + 'px;width:' + width + 'px;height:' + height + 'px'; document.body.appendChild(f); return f.contentWindow; })()")

/** Removes every frame [pinnedFrame] made, so the next test's frames are the ones on top. */
internal fun removePinnedFrames(): Unit = js("{ var fs = document.querySelectorAll('iframe[data-click-test]'); for (var i = 0; i < fs.length; i++) fs[i].remove(); }")

/** The centre of [selector] in [w], in CSS px of the top page's viewport, as "x,y". */
private fun centreOf(w: JsAny, selector: String): String = js(
    """(function () {
      var el = w.document.querySelector(selector), r = el.getBoundingClientRect();
      var x = r.left + r.width / 2, y = r.top + r.height / 2, win = w;
      while (win !== win.top) { var fe = win.frameElement, fr = fe.getBoundingClientRect(); x += fr.left + fe.clientLeft; y += fr.top + fe.clientTop; win = win.parent; }
      return x + ',' + y;
    })()""",
)

private fun fetchText(url: String): Promise<JsAny?> =
    js("fetch(url).then(function (r) { return r.text().then(function (t) { if (!r.ok) throw new Error(url + ': ' + t); return t; }); })")

/** A real left click, by Chrome, at the centre of [selector] in [w]. Returns once Chrome has dispatched it. */
internal suspend fun trustedClick(w: JsAny, selector: String) {
    val (x, y) = centreOf(w, selector).split(",")
    fetchText("/webview-test/click?x=$x&y=$y").await<JsAny?>()
}

/** A real key press ("Enter" or "Space"), by Chrome, to whatever has the focus. */
internal suspend fun trustedKey(key: String) {
    fetchText("/webview-test/key?key=$key").await<JsAny?>()
}

internal fun waitMillis(millis: Int): Promise<JsAny?> = js("new Promise(function (resolve) { setTimeout(resolve, millis); })")
