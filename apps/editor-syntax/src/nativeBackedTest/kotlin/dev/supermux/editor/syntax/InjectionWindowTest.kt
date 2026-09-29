package dev.supermux.editor.syntax

import dev.supermux.editor.core.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Injection queries run in windows ([Highlighter.injectionWindow]): a match crossing a window edge
 * is found from both windows and kept once. The layers (sites) and spans must not depend on the
 * window size, here with a fenced block straddling the default window's edge (32,768).
 */
class InjectionWindowTest {
    private val backend = testBackend()

    private fun document(): Pair<String, Int> {
        val edge = Highlighter.INJECTION_WINDOW
        val sb = StringBuilder("# Windows\n\n")
        var i = 0
        while (sb.length < edge - 40) sb.append("Paragraph ${i++} with `code` and *emphasis*.\n\n")
        val fence = sb.length
        sb.append("```kotlin\n")
        repeat(40) { sb.append("fun f$it(x: Int) = x * $it // kotlin inside markdown\n") }
        sb.append("```\n\n")
        while (sb.length < 3 * edge) sb.append("Paragraph ${i++} with `code` and <b>html</b>.\n\n")
        return sb.toString() to fence
    }

    private fun parseWith(window: Int, text: String): Pair<List<String>, String> {
        val r = RopeText(Rope.of(text))
        return Highlighter(backend, "markdown").use { h ->
            h.injectionWindow = window
            h.parse(r, text.length, null, r).use { d ->
                d.layerSignature to h.spans(d, 0, text.length, r).joinToString("\n") { "${it.from}-${it.to} ${it.value}" }
            }
        }
    }

    @Test fun sitesAndSpansDoNotDependOnTheWindow() {
        val (text, fence) = document()
        val edge = Highlighter.INJECTION_WINDOW
        assertTrue(fence < edge && text.indexOf("```", fence + 3) > edge, "the fence straddles $edge")
        val whole = parseWith(Int.MAX_VALUE, text)
        assertTrue(whole.first.any { it.contains(">kotlin#") && it.contains("[${fence + 10},") }, "the fenced kotlin layer: ${whole.first.take(3)}")
        assertEquals(whole, parseWith(edge, text), "32k windows")
        assertEquals(whole, parseWith(1000, text), "1k windows")
        println("InjectionWindow: ${whole.first.size} layers, ${text.length} units, fence at $fence")
    }
}
