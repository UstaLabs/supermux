package com.swithun.cmpmermaid.core.sankey

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidSankeyOptions
import com.swithun.cmpmermaid.core.sankey.upstream.mermaid.SankeyDb
import com.swithun.cmpmermaid.core.sankey.upstream.mermaid.SankeyParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SankeyParserTest {
    @Test
    fun parsesBothHeadersAndPreservesFirstSeenNodeOrder() {
        listOf("sankey", "SANKEY-BETA").forEach { header ->
            val db = parse(
                """
                $header

                A,B,10
                A,C,5
                B,D,7
                """.trimIndent(),
            )
            assertEquals(listOf("A", "B", "C", "D"), db.getNodes().map { it.id })
            assertEquals(listOf(10.0, 5.0, 7.0), db.getLinks().map { it.value })
        }
    }

    @Test
    fun parsesQuotedCommasQuotesAndNewlines() {
        val db = parse(
            listOf(
                "sankey",
                "\"source, one\",\"target \"\"quoted\"\"\",12.5kg",
                "\"multi",
                "line\",last,2",
            ).joinToString("\n"),
        )

        assertEquals(
            listOf("source, one", "target \"quoted\"", "multi\nline", "last"),
            db.getNodes().map { it.id },
        )
        assertEquals(listOf(12.5, 2.0), db.getLinks().map { it.value })
    }

    @Test
    fun returnsStructuredErrorsForInvalidCsv() {
        listOf(
            "sankey",
            "sankey\nA,B",
            "sankey\nA,B,1,extra",
            "sankey\n\"A,B,1",
            "sankey A,B,1",
        ).forEach { source ->
            assertIs<GMResult.Err<MermaidError>>(parser().parse(source), source)
        }
    }

    @Test
    fun preservesJavaScriptParseFloatSemanticsAtTheDatabaseBoundary() {
        val db = parse("sankey\nA,B,12.75units\nB,C,not-a-number")

        assertEquals(12.75, db.getLinks()[0].value)
        assertTrue(db.getLinks()[1].value.isNaN())
    }

    @Test
    fun appliesFrontmatterLineOffsetToErrors() {
        val error = assertIs<GMResult.Err<MermaidError>>(
            parser(lineOffset = 4).parse("sankey\nA,B"),
        ).error
        assertEquals(6, assertIs<MermaidError.Parse>(error).line)
    }

    private fun parse(source: String): SankeyDb =
        assertIs<GMResult.Ok<SankeyDb>>(parser().parse(source)).value

    private fun parser(lineOffset: Int = 0): SankeyParser = SankeyParser(
        config = MermaidSankeyOptions(),
        diagramTitle = null,
        lineOffset = lineOffset,
    )
}
