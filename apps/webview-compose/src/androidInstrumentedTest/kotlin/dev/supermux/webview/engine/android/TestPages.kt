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
