package com.swithun.cmpmermaid.core.treemap

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidTreemapOptions
import com.swithun.cmpmermaid.core.treemap.upstream.mermaid.TreemapDb
import com.swithun.cmpmermaid.core.treemap.upstream.mermaid.TreemapParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TreemapParserTest {
    @Test
    fun parsesHierarchyValuesClassesAndBothHeaders() {
        listOf("treemap", "treemap-beta").forEach { header ->
            val db = parse(
                """
                $header
                "Root"
                    "Branch":::hot
                        "Leaf A": 1,234.5
                        "Leaf B", 20:::hot
                classDef hot fill:red,color:blue,stroke:#FFD600;
                """.trimIndent(),
            )

            assertEquals(
                listOf("Root", "Branch", "Leaf A", "Leaf B"),
                db.getNodes().map { node -> node.name },
            )
            assertEquals(listOf(0, 1, 2, 2), db.getLevels().map { pair -> pair.second })
            assertEquals(1234.5, db.getNodes()[2].value)
            assertEquals(20.0, db.getNodes()[3].value)
            assertEquals(
                listOf("fill:red", "color:blue", "stroke:#FFD600"),
                db.getNodes()[1].cssCompiledStyles,
            )
        }
    }

    @Test
    fun preservesMultipleOuterNodesLikeTheMermaidPopulationPath() {
        val db = parse(
            """
            treemap-beta
            "One"
                "A": 1
            "Two"
                "B": 2
            """.trimIndent(),
        )

        assertEquals(listOf("One", "Two"), db.getRoot().children?.map { node -> node.name })
    }

    @Test
    fun parsesCommonMetadataAndFrontmatterTitlePrecedence() {
        val db = parse(
            """
            treemap
            title Source title
            accTitle: Accessible title
            accDescr {
              First line
              Second line
            }
            "Root"
                "Leaf": 1
            """.trimIndent(),
            diagramTitle = "Frontmatter title",
        )

        assertEquals("Source title", db.diagramTitle)
        assertEquals("Accessible title", db.accessibilityTitle)
        assertEquals("First line\nSecond line", db.accessibilityDescription)
    }

    @Test
    fun returnsStructuredErrorsWithFrontmatterOffsets() {
        val error = assertIs<GMResult.Err<MermaidError>>(
            parser(lineOffset = 5).parse(
                """
                treemap-beta
                "Root"
                    "Leaf": nope
                """.trimIndent(),
            ),
        ).error

        assertEquals(8, assertIs<MermaidError.Parse>(error).line)
    }

    private fun parse(
        source: String,
        diagramTitle: String? = null,
    ): TreemapDb = assertIs<GMResult.Ok<TreemapDb>>(
        parser(diagramTitle = diagramTitle).parse(source),
    ).value

    private fun parser(
        diagramTitle: String? = null,
        lineOffset: Int = 0,
    ): TreemapParser = TreemapParser(
        config = MermaidTreemapOptions(),
        diagramTitle = diagramTitle,
        lineOffset = lineOffset,
    )
}
