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

/** Logs where the focus is as [test] starts (#166: FocusGuardTest's frames never got the keyboard on CI, now and then). */
internal fun logFocus(test: String) {
    clearFocusTries()
    val line = "$test: ${focusState()}"
    println("[webview-test] $line")
    serverLogWithTargets(line)
}

/** Starts (once) collecting what the frames say their focus() did (the test DRIVER's `focusTried`), from empty. */
private fun clearFocusTries(): Unit = js("""{
  if (!window.__focusTriesOn) {
    window.__focusTriesOn = true;
    window.addEventListener('message', function (e) { if (e.data && e.data.focusTried) window.__focusTries.push(JSON.stringify(e.data)); });
  }
  window.__focusTries = [];
}""")

private fun serverLogWithTargets(line: String): Unit =
    js("{ fetch('/webview-test/log?targets=1&line=' + encodeURIComponent(line)).catch(function () {}); }")

/** Where the focus is, for the build's output: this frame's and the top page's view of it. */
internal fun focusState(): String = js(
    """(function () {
      var a = document.activeElement;
      var top = 'other origin';
      try { top = String(window.top.document.hasFocus()); } catch (e) {}
      return 'hasFocus=' + document.hasFocus() + ' top.hasFocus=' + top + ' visibility=' + document.visibilityState +
        ' active=' + (a && a.localName) + ' inTop=' + (window === window.top) + ' at=' + location.pathname +
        ' iframes=' + document.querySelectorAll('iframe').length + ' (' + (function () {
          var kinds = {};
          document.querySelectorAll('iframe').forEach(function (f) {
            var p = f.parentElement, k = f.getAttribute('src') ? 'src' : 'no-src';
            k += ' in ' + (p ? p.localName + (p.id ? '#' + p.id : '') + (p.getAttribute('data-click-test') !== null ? '[click-test]' : '') +
              (p.parentElement ? '<' + p.parentElement.localName : '') : 'detached') + (f.isConnected ? '' : ' disconnected') +
              ' ' + (f.style.display || '') + (f.style.visibility || '');
            kinds[k] = (kinds[k] || 0) + 1;
          });
          return Object.keys(kinds).map(function (k) { return kinds[k] + '× ' + k; }).join(', ');
        })() + ')';
    })()""",
)

/**
 * Logs [line] where a failed CI run keeps it: the test's own output (the test results' system-out) and the Karma
 * server's log file (karma.config.d/webview-trusted-input.js). Never fails.
 */
internal fun testLog(line: String) {
    println("[webview-test] $line")
    serverLog(line)
}

private fun serverLog(line: String): Unit = js("{ fetch('/webview-test/log?line=' + encodeURIComponent(line)).catch(function () {}); }")

internal fun waitMillis(millis: Int): Promise<JsAny?> = js("new Promise(function (resolve) { setTimeout(resolve, millis); })")
