package dev.supermux.webview.engine.android

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResource
import dev.supermux.webview.WebResourceProvider

/** The pages the instrumented tests load. Each is in-memory: only EngineLoadTest's Url case uses the network. */
internal object TestPages {
    /** All one colour, so a snapshot can be checked by its pixels. */
    fun solid(color: String, title: String = "solid $color"): WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>$title</title>
<style>html,body{margin:0;height:100%;background:$color}</style></head><body></body></html>""",
    )

    /** A page that keeps changing its title, every 10 ms, for as long as it lives. */
    val restless: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>A 0</title></head><body>restless
<script>setInterval(function () { document.title = 'A ' + Date.now(); }, 10);</script></body></html>""",
    )

    /**
     * A page that blocks its renderer for good when told to (`hang()`). On demand, after it painted: a page that blocked
     * half a second after it loaded did so before this phone's WebView reported its first paint.
     */
    val hang: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>hang</title></head><body>
<script>function hang() { setTimeout(function () { for (;;) {} }, 100); }</script></body></html>""",
    )

    /** A page that titles what prefers-color-scheme says, now and whenever it changes. */
    val scheme: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>scheme</title></head><body>
<script>var q = matchMedia('(prefers-color-scheme: dark)');
function report() { document.title = 'dark ' + q.matches; }
report();
q.addEventListener('change', report);</script></body></html>""",
    )

    /** A page that logs one console line once loaded. */
    val console: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>console</title></head><body>
<script>window.onload = function () { console.log('hello from the page'); };</script></body></html>""",
    )

    /** A page that stores a cookie and local storage on its origin, then titles itself "stored". */
    val store: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>storing</title></head><body>
<script>localStorage.setItem('x', '1');
document.cookie = 'kept=1; Secure; SameSite=Lax; Max-Age=3600';
document.title = 'stored';</script></body></html>""",
        WebOrigin("store-test"),
    )

    /** A page of the same origin as [store] that titles what it finds there. */
    val storeReader: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>reading</title></head><body>
<script>document.title = 'read local=' + localStorage.getItem('x') + ' cookie=' + document.cookie;</script></body></html>""",
        WebOrigin("store-test"),
    )

    /** A page that leaves for example.com by itself, 300 ms after it loads. */
    val leaving: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>leaving</title></head><body>
<script>setTimeout(function () { location.href = 'https://example.com/'; }, 300);</script></body></html>""",
    )

    /** A page that asks a confirm and a prompt once loaded, and titles the answers. */
    val dialogs: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>dialogs</title></head><body>
<script>window.onload = function () {
  var c = confirm('sure?');
  var p = prompt('name?', 'x');
  document.title = 'confirm ' + c + ' prompt ' + p;
};</script></body></html>""",
    )

    /** A page that asks for the camera and titles the outcome. */
    val camera: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>camera</title></head><body>
<script>navigator.mediaDevices.getUserMedia({video: true}).then(
  function () { document.title = 'camera granted'; },
  function (e) { document.title = 'camera ' + e.name; });</script></body></html>""",
    )

    /** A page whose shim answers `echo` with its params. */
    val echo: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>echo</title>
<script>hostBridge.handle('echo', function (p) { return p; });</script></head><body>echo</body></html>""",
    )

    /** A page whose same-origin subframe tries to speak for it through the channel object. */
    val framed: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>framed</title></head><body>
<iframe srcdoc="<script>var c = window.__hostBridgeChannel; if (c) c.postMessage('AAAAAAAAAAAAAAAAAAAAAAAA' + JSON.stringify({t: 'event', n: 'spoof', d: null, from: 'x'}));</script>"></iframe>
</body></html>""",
    )

    /** A plugin of two files whose script fetches a missing file and a traversal, and titles the statuses. */
    val bundle: WebContent.Bundle = WebContent.Bundle(
        WebOrigin("plugin-test"),
        WebResourceProvider { path ->
            when (path) {
                "index.html" -> WebResource(
                    """<!doctype html><html><head><title>loading</title><script src="app.js"></script></head><body>plugin</body></html>""".encodeToByteArray(),
                    "text/html",
                )
                "app.js" -> WebResource(
                    """Promise.all([fetch('missing.js'), fetch('%2e%2e%2fsecret')].map(function (p) {
  return p.then(function (r) { return r.status; }, function () { return 'failed'; });
})).then(function (s) { document.title = 'statuses ' + s.join(','); });""".encodeToByteArray(),
                    "application/javascript",
                )
                else -> null
            }
        },
    )
}
