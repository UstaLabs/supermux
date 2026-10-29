package dev.supermux.webview.sample

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResource
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine

/** What a chat message in the perf lab embeds. */
enum class DemoKind(val label: String) {
    /** An interactive demo an agent wrote: in-memory HTML on its own origin, in an ephemeral engine. */
    Html("agent demo"),

    /** A web page, as a link preview: the default profile's engine. */
    Url("web page"),

    /** A plugin: a bundle of files on its own origin. */
    Bundle("plugin"),
}

/** The [index]th message of the perf lab's chat: its [text], and the [content] it embeds, of [kind]. */
class Demo(val index: Int, val kind: DemoKind, val content: WebContent, val text: String)

/** The perf lab's mix: every tenth message embeds a plugin, every fifth a web page, and the rest an agent's demo. */
fun demoKind(index: Int): DemoKind = when {
    index % 10 == 9 -> DemoKind.Bundle
    index % 5 == 2 -> DemoKind.Url
    else -> DemoKind.Html
}

/** The [index]th message. Its content is the same every time, so page memory and snapshots find it again. */
fun demoAt(index: Int): Demo {
    val kind = demoKind(index)
    val content = when (kind) {
        DemoKind.Html -> WebContent.Html(demoHtml(index))
        DemoKind.Url -> WebContent.Url("https://example.com/articles/${index + 1}")
        DemoKind.Bundle -> WebContent.Bundle(WebOrigin("plugin-${index + 1}"), PluginFiles)
    }
    return Demo(index, kind, content, messageText(index))
}

/** The shortest page a demo reports, in CSS px. */
const val MIN_PAGE_HEIGHT = 120

/** How tall a demo's page reports it is, in CSS px: 120 to 359, fixed per content, so the list's heights vary. */
fun pageHeight(content: WebContent): Float = (MIN_PAGE_HEIGHT + content.key.hashCode().mod(240)).toFloat()

/** The fake engine as the sample runs it: each page reports its [pageHeight] once loaded. */
val SampleEngineBehavior: FakeEngineBehavior = FakeEngineBehavior(contentHeight = ::pageHeight)

/**
 * Fake engines for the desktop app, numbered from 1. Unlike the library's `FakeWebEngineFactory`, which lists every
 * engine it ever made so tests can inspect them, this keeps none: a perf lab left scrolling for an hour would
 * otherwise hold every engine it churned through, and measure itself.
 */
fun fakeEngines(behavior: FakeEngineBehavior = SampleEngineBehavior): WebEngineFactory {
    var made = 0
    return WebEngineFactory { config ->
        made++
        WebEngineResult.Created(FakeWebEngine(made, behavior = behavior, config = config))
    }
}

/** A plugin's files: one page. */
private object PluginFiles : WebResourceProvider {
    override suspend fun load(path: String): WebResource? =
        if (path == "index.html") WebResource(PLUGIN_PAGE.encodeToByteArray(), "text/html") else null
}

private const val PLUGIN_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width"><title>Plugin</title>
<style>:root{color-scheme:light dark;font:14px/1.4 system-ui,sans-serif}body{margin:0;padding:12px 14px}</style>
</head><body><h3>A plugin</h3><p>Served from its own origin, talking to the app over the bridge.</p>
<button onclick="hostBridge.post({clicked:Date.now()})">Tell the app</button></body></html>"""

/** An agent's demo: a counter, a chart, a sortable list or an animation, numbered so each is its own page. */
private fun demoHtml(index: Int): String {
    val n = index + 1
    val body = when (index % 4) {
        0 -> """<p>Click to count.</p><button onclick="this.textContent=+this.textContent+1">0</button>"""
        1 -> barChart(index)
        2 -> """<p>Click to sort.</p><ol id="l">${(1..5).joinToString("") { "<li>${(it * 37 + index) % 97}</li>" }}</ol>
<button onclick="const l=document.getElementById('l');[...l.children].sort((a,b)=>a.textContent-b.textContent).forEach(e=>l.append(e))">Sort</button>"""
        else -> """<div class="spin"></div><p>A CSS animation, running while the page is live.</p>"""
    }
    return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Demo $n</title><style>:root{color-scheme:light dark;font:14px/1.4 system-ui,sans-serif}
body{margin:0;padding:12px 14px}h3{margin:0 0 8px;font-size:15px}button{font:inherit;padding:4px 12px}
.spin{width:28px;height:28px;background:#4f7cff;animation:s 1.2s linear infinite}@keyframes s{to{transform:rotate(1turn)}}
</style></head><body><h3>Demo $n</h3>$body</body></html>"""
}

private fun barChart(index: Int): String {
    val bars = (0 until 6).joinToString("") { i ->
        val h = 20 + (index * 13 + i * 29) % 80
        """<rect x="${i * 34}" y="${100 - h}" width="26" height="$h" fill="#4f7cff"/>"""
    }
    return """<svg width="210" height="100" role="img" aria-label="A bar chart">$bars</svg>"""
}

private val SENTENCES = listOf(
    "Here is a small demo of the idea.",
    "Try it: it runs right in the message.",
    "The numbers come from the run above.",
    "I kept it to one file, with nothing to install.",
    "Scroll inside it if it is taller than the message.",
)

private fun messageText(index: Int): String =
    (0..index % 3).joinToString(" ") { SENTENCES[(index + it) % SENTENCES.size] }
