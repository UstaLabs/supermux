package dev.supermux.editor.syntax

import dev.supermux.editor.core.Decoration
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Our spans against tree-sitter's own highlighter (`tree-sitter-cli@0.25.10 highlight`, run by
 * tools/highlight-oracle.py over the same grammar sources, queries and samples): golden/oracle/.
 * Compared unit by unit; the only accepted differences are listed in [ACCEPTED] with their reason.
 */
class OracleTest {
    private val backend = testBackend()

    /** One class (or null) per UTF-16 unit from `start-end tok-class` lines. */
    private fun units(lines: List<Pair<IntRange, String>>, n: Int): Array<String?> {
        val out = arrayOfNulls<String>(n)
        for ((r, c) in lines) for (i in r) if (i < n) out[i] = c
        return out
    }

    @Test fun ourSpansAgreeWithTreeSitterHighlight() {
        val problems = ArrayList<String>()
        for (name in CORE) {
            val (lang, text) = HighlightSamples.ALL.getValue(name)
            val oracle = testResource("golden/oracle/$name.txt").decodeToString().lines().filter { it.isNotBlank() }.map { l ->
                val (range, cls) = l.split(' ')
                val (a, b) = range.split('-').map { it.toInt() }
                (a until b) to cls
            }
            val ours = highlight(backend, lang, text).map { (it.from until it.to) to (it.value as Decoration.Mark).classes.single() }
            val o = units(oracle, text.length)
            val u = units(ours, text.length)
            var i = 0
            while (i < text.length) {
                if (o[i] == u[i]) { i++; continue }
                var j = i + 1
                while (j < text.length && o[j] != u[j] && o[j] == o[i] && u[j] == u[i]) j++
                val token = text.substring(i, j)
                val why = ACCEPTED[name]?.firstOrNull { (t, pair) -> t == token && pair == (o[i] to u[i]) }
                if (why == null) problems += "$name $i-$j '${token.replace("\n", "\\n")}': tree-sitter ${o[i]}, ours ${u[i]}"
                i = j
            }
        }
        assertTrue(problems.isEmpty(), "spans that differ from tree-sitter's highlighter:\n" + problems.joinToString("\n"))
    }

    companion object {
        val CORE = listOf("json", "kotlin", "typescript", "tsx", "python", "go", "scala", "glsl", "pascal")

        /**
         * name -> (covered text, (tree-sitter's class, ours)) differences we accept, each explained:
         * - `_helper` captures never compete: Helix's `(simple_identifier) @function @_import` gives
         *   one node two captures of one pattern; tree-sitter-highlight takes the last (`@_import`,
         *   which no theme draws), we skip `_` names first, so the import's last name is a function.
         * - `@none` clears the colour under it (Helix's meaning: interpolations are not string);
         *   tree-sitter-highlight has no `none` highlight, so the outer `@string` shows through.
         * - Injections: the oracle runs without them; GLSL's `#version 330 core` argument is
         *   injected as GLSL (Helix's injections.scm), which colours `330` and `core`.
         */
        val ACCEPTED: Map<String, List<Pair<String, Pair<String?, String?>>>> = mapOf(
            "kotlin" to listOf("max" to (null to "tok-function"), " " to ("tok-string" to null)),
            "scala" to listOf("p" to ("tok-string" to null)),
            "glsl" to listOf("330" to (null to "tok-number"), "core" to (null to "tok-variable")),
        )
    }
}
