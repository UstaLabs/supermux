package com.swithun.cmpmermaid.core.venn

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidVennOptions
import com.swithun.cmpmermaid.core.venn.upstream.mermaid.VennDb
import com.swithun.cmpmermaid.core.venn.upstream.mermaid.VennParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VennParserTest {
    @Test
    fun parsesOfficialSetsUnionsLabelsSizesAndQuotedIdentifiers() {
        val db = parse(
            """
            venn-beta
              title Product fit
              set "Customer Need"["Desirable"]:20
              set Feasible[Buildable]:12
              set Viable
              union "Customer Need",Feasible["Prototype"]:5.3
              union Viable,"Customer Need",Feasible["Ship it"]:1
            """.trimIndent(),
        )

        assertEquals("Product fit", db.diagramTitle)
        assertEquals(
            listOf(
                listOf("Customer Need") to 20.0,
                listOf("Feasible") to 12.0,
                listOf("Viable") to 10.0,
                listOf("Customer Need", "Feasible") to 5.3,
                listOf("Customer Need", "Feasible", "Viable") to 1.0,
            ),
            db.getSubsetData().map { data -> data.sets to data.size },
        )
        assertEquals(
            listOf("Desirable", "Buildable", null, "Prototype", "Ship it"),
            db.getSubsetData().map { data -> data.label },
        )
    }

    @Test
    fun parsesIndentedAndExplicitTextWithMergedStyles() {
        val db = parse(
            """
            venn-beta
              set A["Frontend"]
                text A1["React"]
                text "A 2"["Design Systems"]
              set B
              union A,B["Shared"]
                text AB1["OpenAPI"]
            text A,B Explicit["Contract"]
              style A fill:#ff6b6b, color:#333, stroke-width:5px
              style A,B fill:rgba(255, 0, 128, 0.5)
              style A1 color:red
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Triple(listOf("A"), "A1", "React"),
                Triple(listOf("A"), "A 2", "Design Systems"),
                Triple(listOf("A", "B"), "AB1", "OpenAPI"),
                Triple(listOf("A", "B"), "Explicit", "Contract"),
            ),
            db.getTextData().map { data -> Triple(data.sets, data.id, data.label) },
        )
        assertEquals("#ff6b6b", db.getStyleData()[0].styles["fill"])
        assertEquals("rgba(255, 0, 128, 0.5)", db.getStyleData()[1].styles["fill"])
        assertEquals("red", db.getStyleData()[2].styles["color"])
    }

    @Test
    fun returnsStructuredErrorsForInvalidUnionAndFrontmatterOffset() {
        val unknown = assertIs<GMResult.Err<MermaidError>>(
            parser(lineOffset = 4).parse(
                """
                venn-beta
                  set A
                  union A,B
                """.trimIndent(),
            ),
        ).error
        assertEquals(7, assertIs<MermaidError.Parse>(unknown).line)

        val single = assertIs<GMResult.Err<MermaidError>>(
            parser().parse("venn-beta\nunion A"),
        ).error
        assertEquals(
            true,
            assertIs<MermaidError.Parse>(single).message.contains(
                "union requires multiple identifiers",
            ),
        )

        assertIs<GMResult.Err<MermaidError>>(
            parser().parse(
                """
                venn-beta
                  set A
                  set B
                  union A,B
                  text A,B Explicit["Contract"]
                """.trimIndent(),
            ),
        )
    }

    private fun parse(source: String): VennDb =
        assertIs<GMResult.Ok<VennDb>>(parser().parse(source)).value

    private fun parser(lineOffset: Int = 0): VennParser = VennParser(
        config = MermaidVennOptions(),
        diagramTitle = null,
        lineOffset = lineOffset,
        maximumStatements = 1_000,
    )
}
