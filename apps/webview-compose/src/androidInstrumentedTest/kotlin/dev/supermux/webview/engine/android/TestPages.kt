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

    /**
     * All one colour but for a small white mark in its top-left corner: a snapshot's centre shows the colour, and a page
     * all of one colour would be taken for a blank draw (Snapshots.kt `isBlank`).
     */
    fun marked(color: String, title: String = "marked $color"): WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>$title</title>
<style>html,body{margin:0;height:100%;background:$color}#m{position:fixed;left:0;top:0;width:32px;height:32px;background:#fff}</style>
</head><body><div id="m"></div></body></html>""",
    )

    /**
     * White but for one 3 × 3 px black dot, 100 px from its top-left corner: a sparse page, which a draw finds blank
     * unless it reads every pixel.
     */
    val sparse: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>sparse</title>
<style>html,body{margin:0;height:100%;background:#fff}#d{position:fixed;left:100px;top:100px;width:3px;height:3px;background:#000}</style>
</head><body><div id="d"></div></body></html>""",
    )

    /** A WebGL canvas over the whole page, cleared red: content WebView's software draw can't reach. */
    val webGl: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>webgl</title>
<style>html,body{margin:0;height:100%;background:#fff}canvas{display:block;width:100%;height:100%}</style></head><body>
<canvas id="c"></canvas><script>
var c = document.getElementById('c'); c.width = innerWidth; c.height = innerHeight;
var gl = c.getContext('webgl'); gl.clearColor(1, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT);
document.title = gl ? 'webgl on' : 'webgl off';
</script></body></html>""",
    )

    /** An agent demo as the perf lab has them: a heading, text, a bar chart, a list. For timing the page's own draw. */
    val demo: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>demo</title>
<style>body{margin:0;padding:16px;font:15px/1.4 sans-serif;background:#f6f8ff;color:#123}h1{font-size:20px}
li{margin:4px 0}.bar{fill:#4f7cff}</style></head><body><h1>Quarterly numbers</h1>
<p>The agent pulled the figures and drew them. Revenue rose in every month but one; the dip in May followed a pricing
change that was rolled back in June.</p>
<svg width="300" height="120" role="img" aria-label="bars">
<rect class="bar" x="0" y="60" width="30" height="60"/><rect class="bar" x="40" y="30" width="30" height="90"/>
<rect class="bar" x="80" y="45" width="30" height="75"/><rect class="bar" x="120" y="10" width="30" height="110"/>
<rect class="bar" x="160" y="70" width="30" height="50"/><rect class="bar" x="200" y="20" width="30" height="100"/></svg>
<ul><li>January: up 4 %</li><li>February: up 6 %</li><li>March: up 2 %</li><li>April: up 9 %</li></ul>
</body></html>""",
    )

    /**
     * A text-heavy page, as agents write them: headings, paragraphs, inline code, a dark code block, a table, a link.
     * Sharp edges everywhere, the hardest case for a JPEG: for the snapshot quality bar.
     */
    val article: WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>article</title>
<style>body{margin:0;padding:16px;font:15px/1.5 sans-serif;color:#1d2433;background:#fff}
h1{font-size:22px;margin:0 0 8px}h2{font-size:17px;margin:16px 0 6px}a{color:#2257d6}
code{font:13px monospace;background:#eef1f6;padding:1px 4px}
pre{font:13px/1.4 monospace;background:#0f172a;color:#e2e8f0;padding:10px;border-radius:8px}
table{border-collapse:collapse;width:100%}td,th{border:1px solid #d5dbe5;padding:4px 6px;text-align:left}
th{background:#f2f5fa}</style></head><body><h1>Why the build broke</h1>
<p>The agent traced the failure to a <code>timeout</code> in the cache layer: under load, <a href="#">two requests</a>
raced for the same key, and the second waited on a lock the first never released.</p>
<h2>The fix</h2>
<pre>fun get(key: Key): Value {
    val entry = entries[key] ?: return load(key)
    return entry.value
}</pre>
<p>Requests for the same key now share one load, so nothing waits on a lock at all.</p>
<table><tr><th>Run</th><th>p50</th><th>p99</th></tr><tr><td>before</td><td>41 ms</td><td>2.3 s</td></tr>
<tr><td>after</td><td>12 ms</td><td>38 ms</td></tr></table>
<p>Numbers from the nightly run, 2,000 requests each.</p></body></html>""",
    )

    /** A page that logs a line from an inline script as it parses, and another at onload. Each [n] is its own page. */
    fun earlyConsole(n: Int): WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>early console $n</title>
<script>console.log('inline line $n');</script></head><body>
<script>window.onload = function () { console.log('onload line $n'); };</script></body></html>""",
    )

    /** A page whose inline script, as it parses, alerts, then confirms, and titles the answer. Each [n] is its own page. */
    fun earlyDialogs(n: Int): WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>early dialogs $n</title>
<script>alert('hello $n'); var c = confirm('sure $n?'); document.title = 'confirm ' + c + ' $n';</script></head><body></body></html>""",
    )

    /** A page whose inline script asks for the camera as it parses, and titles the outcome. Each [n] is its own page. */
    fun earlyCamera(n: Int): WebContent.Html = WebContent.Html(
        """<!doctype html><html><head><title>early camera $n</title>
<script>navigator.mediaDevices.getUserMedia({video: true}).then(
  function () { document.title = 'camera granted $n'; },
  function (e) { document.title = 'camera ' + e.name + ' $n'; });</script></head><body></body></html>""",
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
