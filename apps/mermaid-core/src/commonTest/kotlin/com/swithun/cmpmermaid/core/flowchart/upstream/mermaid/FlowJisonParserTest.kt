package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlowJisonParserTest {
    @Test
    fun lexesUsingTheGeneratedMermaidRules() {
        var firstGraph = true
        val lexer = FlowJisonLexer(
            source = "flowchart TD\nA[Hello]:::hot -->|yes| B((Done))",
            firstGraph = {
                firstGraph.also { firstGraph = false }
            },
        )

        val tokens = buildList {
            while (true) {
                val token = assertIs<GMResult.Ok<JisonToken>>(lexer.next()).value
                add(token.name to token.text)
                if (token.symbol == 1) {
                    break
                }
            }
        }

        assertEquals(
            listOf(
                "GRAPH" to "flowchart",
                "DIR" to " TD",
                "NEWLINE" to "\n",
                "NODE_STRING" to "A",
                "SQS" to "[",
                "TEXT" to "Hello",
                "SQE" to "]",
                "STYLE_SEPARATOR" to ":::",
                "NODE_STRING" to "hot",
                "LINK" to " -->",
                "PIPE" to "|",
                "TEXT" to "yes",
                "PIPE" to "|",
                "SPACE" to " ",
                "NODE_STRING" to "B",
                "PS" to "(",
                "PS" to "(",
                "TEXT" to "Done",
                "PE" to ")",
                "PE" to ")",
                "EOF" to "",
                "\$end" to "",
            ),
            tokens,
        )
    }

    @Test
    fun parsesBasicGraphThroughTheUpstreamJisonTable() {
        val parsed = assertIs<GMResult.Ok<FlowDb>>(
            FlowJisonParser().parse(
                """
                    flowchart LR
                    A --> B
                """.trimIndent(),
            ),
        ).value

        assertEquals("LR", parsed.getDirection())
        assertEquals(listOf("A", "B"), parsed.vertices().keys.toList())
        assertEquals(1, parsed.edges().size)
        assertEquals("A", parsed.edges().single().start)
        assertEquals("B", parsed.edges().single().end)
        assertEquals("arrow_point", parsed.edges().single().type)
    }

    @Test
    fun preservesMermaidMultiNodeDeclarationAndEdgeOrder() {
        val result = FlowJisonParser().parse(
            """
                flowchart LR
                  A:::source & B:::source --> C & D
                  classDef source fill:#e0f2fe,stroke:#0369a1
            """.trimIndent(),
        )

        val db = assertIs<GMResult.Ok<FlowDb>>(result, result.toString()).value
        assertEquals(listOf("A", "B", "C", "D"), db.vertices().keys.toList())
        assertEquals(listOf("A", "B", "C", "D"), db.getData().nodes.map { it.id })
        assertEquals(
            listOf("A" to "C", "A" to "D", "B" to "C", "B" to "D"),
            db.getData().edges.map { edge -> edge.start to edge.end },
        )
    }

    @Test
    fun parsesSubgraphsStylesAndMetadataThroughFlowDb() {
        val result = FlowJisonParser().parse(
            """
                flowchart TB
                subgraph G [Group]
                  direction LR
                  A@{ shape: hex, label: "Alpha" } --> B[Beta]
                end
                classDef hot fill:#fee,stroke:#f00
                class A hot
                A e1@-. retry .-> B
                e1@{ animate: true, curve: linear }
            """.trimIndent(),
        )
        val parsed = assertIs<GMResult.Ok<FlowDb>>(
            result,
            result.toString(),
        ).value

        assertEquals("hex", parsed.vertices().getValue("A").type)
        assertEquals("Alpha", parsed.vertices().getValue("A").text)
        assertEquals(listOf("hot"), parsed.vertices().getValue("A").classes)
        assertEquals("LR", parsed.subgraphs().single().direction)
        assertEquals(listOf("B", "A"), parsed.subgraphs().single().nodes)
        assertEquals(true, parsed.edges().last().animate)
        assertEquals("linear", parsed.edges().last().interpolate)
    }

    @Test
    fun parsesMultilineYamlMetadata() {
        val result = FlowJisonParser().parse(
            """
                flowchart TB
                  A@{
                    label: |
                      This is a
                      multiline string
                    other: "clock"
                  }
            """.trimIndent(),
        )

        val db = assertIs<GMResult.Ok<FlowDb>>(result, result.toString()).value
        assertEquals("This is a\nmultiline string\n", db.getData().nodes.single().label)
    }

    @Test
    fun preservesClosingBraceInsideMetadataString() {
        val result = FlowJisonParser().parse(
            """
                flowchart TB
                  A@{
                    label: "This is a string with }"
                    other: "clock"
                  }
            """.trimIndent(),
        )

        val db = assertIs<GMResult.Ok<FlowDb>>(result, result.toString()).value
        assertEquals("This is a string with }", db.getData().nodes.single().label)
    }

    @Test
    fun rejectsUnknownAndInternalOnlyMetadataShapes() {
        val unknown = FlowJisonParser().parse(
            "flowchart TB\nA@{ shape: this-shape-does-not-exist }",
        )
        val unknownError = assertIs<GMResult.Err<MermaidError>>(unknown).error
        assertContains(unknownError.message, "No such shape: this-shape-does-not-exist.")

        val internal = FlowJisonParser().parse(
            "flowchart TB\nA@{ shape: rect_left_inv_arrow }",
        )
        val internalError = assertIs<GMResult.Err<MermaidError>>(internal).error
        assertContains(internalError.message, "Shape names should be lowercase.")
    }

    @Test
    fun assignsGroupedEdgeIdsLikeFlowDb() {
        val result = FlowJisonParser().parse(
            """
                flowchart TD
                  A & B e1@--> C & D
                  A1 e2@--> C1 & D1
            """.trimIndent(),
        )

        val db = assertIs<GMResult.Ok<FlowDb>>(result, result.toString()).value
        assertEquals(
            listOf("L_A_C_0", "L_A_D_0", "e1", "L_B_D_0", "e2", "L_A1_D1_0"),
            db.getData().edges.map { edge -> edge.id },
        )
    }

    @Test
    fun flowDbConsumesTheResolvedMermaidConfiguration() {
        val options = MermaidRenderOptions(
            flowchartPadding = 27f,
            minNodeWidth = 175f,
            curve = "step",
            look = "classic",
            inheritDirection = true,
        )
        val result = FlowJisonParser(
            config = options,
            diagramTitle = "Configured",
        ).parse(
            """
                flowchart LR
                  subgraph Group
                    A --> B
                  end
            """.trimIndent(),
        )

        val db = assertIs<GMResult.Ok<FlowDb>>(result, result.toString()).value
        val data = db.getData()
        assertEquals("Configured", db.diagramTitle)
        assertEquals("LR", db.subgraphs().single().direction)
        assertEquals(27f, data.nodes.first { it.id == "A" }.padding)
        assertEquals(175f, data.nodes.first { it.id == "A" }.minWidth)
        assertEquals("classic", data.nodes.first { it.id == "A" }.look)
        assertEquals("step", data.edges.single().curve)
    }

    @Test
    fun flowDbEnforcesConfiguredEdgeLimit() {
        val result = FlowJisonParser(
            config = MermaidRenderOptions(maxEdges = 1),
        ).parse(
            """
                flowchart LR
                  A --> B
                  B --> C
            """.trimIndent(),
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        assertContains(error.message, "limit is 1")
    }
}
