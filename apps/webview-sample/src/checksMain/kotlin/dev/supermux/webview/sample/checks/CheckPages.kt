package dev.supermux.webview.sample.checks

import dev.supermux.webview.DialogResult
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResource
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.engine.EngineClient
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.TimeSource

/** A page of in-memory HTML on its own origin, with [script] run in its body. */
fun page(title: String, body: String = "", script: String = ""): WebContent.Html = WebContent.Html(
    """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>$title</title><style>body{font:16px system-ui;margin:16px;background:#e8f0fe}</style></head>
<body><h1>$title</h1>$body<script>$script</script></body></html>""",
)

/** A page that answers the host's `ping` event with a `pong` carrying the same data. */
val echoPage: WebContent.Html = page("echo", script = "hostBridge.on('ping', function (d) { hostBridge.post('pong', d); });")

/** A page like an article, what a cover has to show sharply: a heading, text at a few sizes, a gradient and colours. */
val articlePage: WebContent.Html = page(
    "An article",
    body = """<p style="font-size:15px;line-height:1.5">A cover stands in for the page while it wakes: it should look like the
page itself, its text as crisp, its gradients as smooth. Small type shows a soft picture first, so this paragraph keeps
to fifteen pixels, and the next one is smaller still.</p>
<div style="height:72px;border-radius:12px;background:linear-gradient(90deg,#ff6b6b,#ffd93d 50%,#4f7cff)"></div>
<p style="font-size:12px;line-height:1.4;color:#333">Twelve-pixel text, grey on blue: 0123456789 — the quick brown fox jumps
over the lazy dog. ÇŞĞİÖÜ çşğıöü 日本語のテキスト.</p>
<p><b>Bold</b>, <i>italic</i> and <code>monospace</code>, with a link: <a href="#">example.com</a>.</p>
<div style="display:flex;gap:8px"><span style="flex:1;height:40px;background:#1b998b"></span>
<span style="flex:1;height:40px;background:#ed217c"></span><span style="flex:1;height:40px;background:#2d3047"></span></div>""",
)

/** The setup a lease gives [content]: JavaScript on, the bridge for its own local origin. */
fun setupFor(content: WebContent, javaScript: Boolean = true): PageSetup = PageSetup(
    javaScriptEnabled = javaScript,
    inspectable = true,
    dark = false,
    transparentBackground = false,
    userAgentSuffix = null,
    bridgeOrigins = setOfNotNull(
        when (content) {
            is WebContent.Html -> content.origin.origin
            is WebContent.Bundle -> content.origin.origin
            is WebContent.Url -> null
        },
    ),
)

/** A plugin's files, and every path it was asked for. */
class CheckBundle(host: String) : WebResourceProvider {
    val asked = mutableListOf<String>()
    val content: WebContent.Bundle = WebContent.Bundle(WebOrigin(host), this)

    override suspend fun load(path: String): WebResource? {
        asked += path
        return when (path) {
            "index.html" -> html(
                "<h1>plugin</h1><a id=next href='page2.html'>next</a>" +
                    "<script>hostBridge.on('ping', function (d) { hostBridge.post('pong', d); });</script>",
            )
            "page2.html" -> html("<h1>page 2</h1>")
            "data.json" -> WebResource("{\"ok\":true}".encodeToByteArray(), "application/json")
            else -> null
        }
    }

    private fun html(body: String) = WebResource(
        "<!doctype html><html><head><meta charset='utf-8'><title>plugin</title></head><body>$body</body></html>".encodeToByteArray(),
        "text/html; charset=utf-8",
    )
}

/**
 * A plugin whose page shows nothing but an image, which it serves [delayMillis] after it is asked for, or never (null):
 * a page with nothing to show until then. [elapsed] and [servedAt] count from the bundle's making.
 */
class SlowImageBundle(host: String, private val delayMillis: Long?) : WebResourceProvider {
    private val made = TimeSource.Monotonic.markNow()
    val content: WebContent.Bundle = WebContent.Bundle(WebOrigin(host), this)

    /** When the image was served; null until it is. */
    var servedAt: Duration? = null
        private set

    /** The time since the bundle was made. */
    fun elapsed(): Duration = made.elapsedNow()

    override suspend fun load(path: String): WebResource? = when (path) {
        "index.html" -> WebResource(
            ("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'>" +
                "<title>image</title></head><body style='margin:0;background:#fff'><img src='late.png' width='200' height='100'>" +
                "</body></html>").encodeToByteArray(),
            "text/html; charset=utf-8",
        )
        "late.png" -> {
            if (delayMillis == null) awaitCancellation()
            delay(delayMillis)
            servedAt = made.elapsedNow()
            WebResource(ONE_PIXEL_PNG, "image/png")
        }
        else -> null
    }

    private companion object {
        /** A 1 x 1 PNG, drawn 200 x 100. */
        @OptIn(ExperimentalEncodingApi::class)
        val ONE_PIXEL_PNG: ByteArray =
            Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
    }
}

/** A client that answers navigations with [navigation], dialogs from [dialog], and records what it was asked. */
class CheckClient(
    var navigation: NavigationDecision = NavigationDecision.Allow,
    var newWindow: NewWindowDecision = NewWindowDecision.Block,
    var dialog: CompletableDeferred<DialogResult> = CompletableDeferred(DialogResult.Dismissed),
) : EngineClient {
    val asked = mutableListOf<Any>()

    override fun onNavigation(request: NavigationRequest): NavigationDecision = navigation.also { asked += request }
    override suspend fun onDialog(dialog: WebDialog): DialogResult {
        asked += dialog
        return this.dialog.await()
    }
    override suspend fun onFileChooser(request: FileChooserRequest): List<String>? = null.also { asked += request }
    override suspend fun onPermission(request: PermissionRequest): Boolean = false.also { asked += request }
    override suspend fun onDownload(request: DownloadRequest): Boolean = false.also { asked += request }
    override fun onNewWindow(request: NewWindowRequest): NewWindowDecision = newWindow.also { asked += request }
}

/** Whether [event] is a page's `$ready`. */
fun isReady(event: EngineEvent): Boolean = event is EngineEvent.PageMessage && event.json.contains("\"n\":\"\$ready\"")

/** The nonce a `$ready` announced. */
fun nonceOf(ready: EngineEvent): String =
    Json.parseToJsonElement((ready as EngineEvent.PageMessage).json).jsonObject["d"]!!.jsonObject["nonce"]!!.jsonPrimitive.content

/** The host's event [name] with number [data], addressed to the document of [nonce], as the bridge encodes it. */
fun hostEvent(name: String, data: Int, nonce: String): String = "{\"t\":\"event\",\"n\":\"$name\",\"d\":$data,\"to\":\"$nonce\"}"

/** Whether [event] is a page's `pong` carrying [data]. */
fun isPong(event: EngineEvent, data: Int): Boolean =
    event is EngineEvent.PageMessage && event.json.startsWith("{\"t\":\"event\",\"n\":\"pong\",\"d\":$data")
