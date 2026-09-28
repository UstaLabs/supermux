package dev.supermux.editor.plugins.lsp

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.plugins.autocomplete.CompletionContext
import dev.supermux.editor.plugins.autocomplete.autocompletion
import dev.supermux.editor.plugins.lint.lint
import dev.supermux.editor.plugins.lsp.fake.FakeLspServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A 5,000-item completion response (the size a big TypeScript or Kotlin project answers with): the
 * JSON parse and the mapping to options, timed on each platform (JVM, iOS simulator, the browser).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompletionParsePerfTest {
    private fun response(n: Int): String = buildJsonObject {
        put("isIncomplete", false)
        put("items", JsonArray((0 until n).map { i ->
            buildJsonObject {
                put("label", "someIdentifier$i"); put("kind", 1 + i % 25); put("detail", "fun someIdentifier$i(a: Int, b: String): List<Map<String, Int>>")
                put("sortText", "000$i"); put("filterText", "someIdentifier$i"); put("insertText", "someIdentifier$i")
                put("documentation", buildJsonObject { put("kind", "markdown"); put("value", "Documentation for **item $i** with `code` and a [link](https://example.com).") })
            }
        }))
    }.toString()

    @Test fun fiveThousandItemsParseAndMap() = runTest {
        val server = FakeLspServer(backgroundScope)
        val client = LspClient(server.transport, backgroundScope, LspClientConfig(parseOnWorker = false))
        val view = EditorView(EditorState.create("some", EditorSelection.cursor(4), extensionOf(autocompletion(), lint(), client.plugin("file:///p.toy", "toy"))))
        view.startPlugins(backgroundScope)
        advanceTimeBy(1_000); runCurrent()
        val doc = client.workspace.viewFor(view)!!
        val text = response(5000)
        val ctx = CompletionContext(view.state, 4, explicit = true)
        var bestParse = Long.MAX_VALUE; var bestMap = Long.MAX_VALUE
        repeat(6) {
            val t0 = TimeSource.Monotonic.markNow()
            val el = Json.parseToJsonElement(text)
            val parse = t0.elapsedNow().inWholeMicroseconds
            val t1 = TimeSource.Monotonic.markNow()
            val r = doc.completionResult(el, ctx, view.state.doc, client.features.value, PositionEncoding.UTF16)
            val map = t1.elapsedNow().inWholeMicroseconds
            assertEquals(5000, r!!.options.size)
            if (it > 0) { bestParse = minOf(bestParse, parse); bestMap = minOf(bestMap, map) }
        }
        // The browser's sliced parser: the same tree, no piece of work longer than ~4 ms.
        var longest = 0L
        var slices = 0
        var total = 0L
        repeat(3) {
            var last = TimeSource.Monotonic.markNow()
            val t = TimeSource.Monotonic.markNow()
            var worst = 0L
            var count = 0
            val tree = SlicedJson.parse(text, 4) { worst = maxOf(worst, last.elapsedNow().inWholeMicroseconds); count++; last = TimeSource.Monotonic.markNow() }
            worst = maxOf(worst, last.elapsedNow().inWholeMicroseconds)
            if (it == 0) assertEquals(Json.parseToJsonElement(text), tree, "the sliced parser's tree")
            else { longest = worst; slices = count; total = t.elapsedNow().inWholeMicroseconds }
        }
        println("LSP-SLICED-PARSE 5000 items: ${total / 1000.0} ms in ${slices + 1} slices, the longest ${longest / 1000.0} ms")
        assertEquals(SlicedJson.parse("{\"a\":[1,2.5,-3e2,true,false,null,\"x\\n\\u00e7\"],\"b\":{}}", 4) {}, Json.parseToJsonElement("{\"a\":[1,2.5,-3e2,true,false,null,\"x\\n\\u00e7\"],\"b\":{}}"))
        println("LSP-PARSE-PERF 5000 items (${text.length / 1024} KB): parse ${bestParse / 1000.0} ms, map ${bestMap / 1000.0} ms (best of 5)")
        assertTrue(bestParse + bestMap < 2_000_000, "5,000 items took ${(bestParse + bestMap) / 1000} ms")
    }
}
