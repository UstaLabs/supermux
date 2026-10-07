package com.swithun.cmpmermaid.core.block

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.block.upstream.mermaid.BlockDb
import com.swithun.cmpmermaid.core.block.upstream.mermaid.BlockJisonParser
import com.swithun.cmpmermaid.core.block.upstream.mermaid.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BlockParserTest {
    @Test
    fun parsesOfficialNodesColumnsAndNestedComposites() {
        val db = parse(
            """
            block
              columns 3
              a["A label"] b:2
              block:group:3
                columns 2
                c(("Circle"))
                d{{"Decision"}}
              end
            """.trimIndent(),
        )

        assertEquals(3, db.getColumns(BlockDb.ROOT_ID))
        assertEquals(listOf("a", "b", "group"), db.getBlocks().map { block -> block.id })
        assertEquals(BlockType.Square, db.getBlock("a")?.type)
        assertEquals(2, db.getBlock("b")?.widthInColumns)
        assertEquals(BlockType.Composite, db.getBlock("group")?.type)
        assertEquals(3, db.getBlock("group")?.widthInColumns)
        assertEquals(2, db.getColumns("group"))
        assertEquals(BlockType.Circle, db.getBlock("c")?.type)
        assertEquals(BlockType.Hexagon, db.getBlock("d")?.type)
    }

    @Test
    fun parsesAllDocumentedShapesAndBlockArrowDirections() {
        val db = parse(
            """
            block-beta
              square["Square"]
              round("Round")
              stadium(["Stadium"])
              subroutine[["Subroutine"]]
              cylinder[("Database")]
              circle(("Circle"))
              asymmetric>"Asymmetric"]
              diamond{"Diamond"}
              hexagon{{"Hexagon"}}
              leanRight[/"Lean right"/]
              leanLeft[\"Lean left"\]
              trapezoid[/"Trapezoid"\]
              inverse[\"Inverse"/]
              double((("Double")))
              arrow<["Arrow"]>(x, down)
            """.trimIndent(),
        )

        assertEquals(BlockType.Square, db.getBlock("square")?.type)
        assertEquals(BlockType.Round, db.getBlock("round")?.type)
        assertEquals(BlockType.Stadium, db.getBlock("stadium")?.type)
        assertEquals(BlockType.Subroutine, db.getBlock("subroutine")?.type)
        assertEquals(BlockType.Cylinder, db.getBlock("cylinder")?.type)
        assertEquals(BlockType.Circle, db.getBlock("circle")?.type)
        assertEquals(BlockType.RectangleLeftInverseArrow, db.getBlock("asymmetric")?.type)
        assertEquals(BlockType.Diamond, db.getBlock("diamond")?.type)
        assertEquals(BlockType.Hexagon, db.getBlock("hexagon")?.type)
        assertEquals(BlockType.LeanRight, db.getBlock("leanRight")?.type)
        assertEquals(BlockType.LeanLeft, db.getBlock("leanLeft")?.type)
        assertEquals(BlockType.Trapezoid, db.getBlock("trapezoid")?.type)
        assertEquals(BlockType.InverseTrapezoid, db.getBlock("inverse")?.type)
        assertEquals(BlockType.DoubleCircle, db.getBlock("double")?.type)
        assertEquals(listOf("x", "down"), db.getBlock("arrow")?.directions?.map(String::trim))
    }

    @Test
    fun expandsSpacesAndPreservesEdgeAndStyleSemantics() {
        val db = parse(
            """
            block
              columns 4
              A["Start"] space:2 B["Stop"]
              A -- "normal" --> B
              A == "thick" ==> B
              A -. "dotted" .-> B
              A --x B
              classDef blue fill:#6e6ce6,stroke:#333,color:#fff;
              class A blue
              style B fill:#bbf,stroke:#f66,stroke-width:2px
            """.trimIndent(),
        )

        assertEquals(4, db.getBlocks().count { block -> block.type != BlockType.Edge })
        assertEquals(2, db.getBlocks().count { block -> block.type == BlockType.Space })
        assertEquals(4, db.getEdges().size)
        assertEquals("normal", db.getEdges()[0].thickness)
        assertEquals("thick", db.getEdges()[1].thickness)
        assertEquals("dotted", db.getEdges()[2].pattern)
        assertEquals("arrow_cross", db.getEdges()[3].arrowTypeEnd)
        assertTrue(db.getBlock("A")?.classes == listOf("blue"))
        assertTrue(db.getBlock("B")?.styles?.contains("stroke-width:2px") == true)
        assertEquals("color:#fff", db.getClasses().getValue("blue").styles.last())
    }

    @Test
    fun mergesRepeatedDeclarationsLikeUpstreamDatabase() {
        val db = parse(
            """
            block
              A
              A["Renamed"]
              A(("Final"))
            """.trimIndent(),
        )

        assertEquals(1, db.getBlocks().size)
        assertEquals("Final", db.getBlock("A")?.label)
        assertEquals(BlockType.Circle, db.getBlock("A")?.type)
    }

    @Test
    fun matchesOfficialDatabaseSanitizationAndDuplicateDirectionSemantics() {
        val db = parse(
            """
            block
              A<["<script>ignored</script><b>First</b>"]>(right)
              A<["Second"]>(left)
              B["<script>ignored</script><b>Bold</b>"]
            """.trimIndent(),
        )

        assertEquals("Second", db.getBlock("A")?.label)
        assertEquals(listOf("right"), db.getBlock("A")?.directions)
        assertEquals("<b>Bold</b>", db.getBlock("B")?.label)
    }

    @Test
    fun parsesAnonymousCompositesAndPrototypeLikeIds() {
        val db = parse(
            """
            block
              block
                __proto__
                constructor
              end
            """.trimIndent(),
        )

        val composite = db.getBlocks().single()
        assertEquals(BlockType.Composite, composite.type)
        assertTrue(composite.id.startsWith("composite-generated-"))
        assertEquals(listOf("__proto__", "constructor"), composite.children.map { it.id })
        assertEquals("__proto__", db.getBlock("__proto__")?.label)
        assertEquals("constructor", db.getBlock("constructor")?.label)
    }

    @Test
    fun matchesOfficialEdgeStyleAndMarkerClassification() {
        assertEquals("normal", BlockDb.edgeStringToThickness("-->"))
        assertEquals("normal", BlockDb.edgeStringToThickness("-.->"))
        assertEquals("thick", BlockDb.edgeStringToThickness("==>"))

        assertEquals("solid", BlockDb.edgeStringToPattern("-->"))
        assertEquals("solid", BlockDb.edgeStringToPattern("==>"))
        assertEquals("dotted", BlockDb.edgeStringToPattern("-.->"))

        assertEquals("arrow_point", BlockDb.edgeStringToStart("<-->"))
        assertEquals("arrow_point", BlockDb.edgeStringToEnd("<-->"))
        assertEquals("arrow_point", BlockDb.edgeStringToStart("<==>"))
        assertEquals("arrow_point", BlockDb.edgeStringToEnd("<==>"))
        assertEquals("arrow_open", BlockDb.edgeStringToStart("-->"))
        assertEquals("arrow_point", BlockDb.edgeStringToEnd("-->"))
        assertEquals("arrow_cross", BlockDb.edgeStringToEnd("--x"))
        assertEquals("arrow_circle", BlockDb.edgeStringToEnd("--o"))
    }

    @Test
    fun preservesOfficialClassTargetWhitespaceLookupOrder() {
        val db = parse(
            """
            block
              A B
              class A, B red
              classDef red fill:#f00
            """.trimIndent(),
        )

        assertEquals(listOf("red"), db.getBlocks()[0].classes)
        assertTrue(db.getBlocks()[1].classes.isEmpty())
        assertEquals(BlockType.Na, db.getBlock("B")?.type)
        assertTrue(db.getBlock("B")?.classes == listOf("red"))
    }

    @Test
    fun returnsTypedErrorsForMalformedInputAndEdgeLimits() {
        val malformed = BlockJisonParser().parse("block\nA[\"unterminated")
        assertIs<MermaidError.Parse>(
            assertIs<GMResult.Err<MermaidError>>(malformed).error,
        )

        listOf(
            "block\nA[plain]",
            "block\nA[\"`**bold**`\"]",
            "block\nA -- \"`**edge**`\" --> B",
            "block\nstyle A fill:#f00\nA",
        ).forEach { source ->
            assertIs<MermaidError.Parse>(
                assertIs<GMResult.Err<MermaidError>>(
                    BlockJisonParser().parse(source),
                    source,
                ).error,
                source,
            )
        }

        val limited = BlockJisonParser(
            config = MermaidRenderOptions(maxEdges = 1),
        ).parse(
            """
            block
              A --> B
              C --> D
            """.trimIndent(),
        )
        assertIs<MermaidError.ResourceLimit>(
            assertIs<GMResult.Err<MermaidError>>(limited).error,
        )
    }

    private fun parse(source: String): BlockDb {
        val result = BlockJisonParser().parse(source)
        return assertIs<GMResult.Ok<BlockDb>>(result, "$source\n$result").value
    }
}
