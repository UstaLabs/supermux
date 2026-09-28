package com.swithun.cmpmermaid.core.agentflow.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentflowJisonParserTest {
    @Test
    fun parsesTheThreeAgentflowEdgeSemantics() {
        val db = parse(
            """
                agentflow-beta LR
                  a --> b
                  b -.- doc
                  b --x failed
            """.trimIndent(),
        )

        assertEquals("LR", db.getDirection())
        assertEquals(
            listOf(
                AgentflowEdgeSemantic.Sequence,
                AgentflowEdgeSemantic.Reference,
                AgentflowEdgeSemantic.Failure,
            ),
            db.edges().map { it.semantic },
        )
        assertEquals(
            listOf("arrow_point", "arrow_open", "arrow_cross"),
            db.edges().map { it.type },
        )
        assertEquals(listOf("normal", "dotted", "normal"), db.edges().map { it.stroke })
    }

    @Test
    fun parsesFlowsConnectorsGlobalsAndDomainMetadata() {
        val db = parse(
            """
                agentflow-beta TB
                  connector llm["LLM"]
                  llm@{ protocol: "http" }
                  global
                    shared["Shared"]
                  end
                  flow team["Team"]
                    input["city"]@{ shape: input, value: "Paris" }
                    tool["lookup"]@{ shape: tool, connectorRef: "llm.chat", retry: 2 }
                    shared --> input --> tool
                  end
                  team@{ model: "model-a", view: "collapsed" }
            """.trimIndent(),
        )

        assertEquals(listOf("llm"), db.connectors().map { it.id })
        assertEquals("http", db.connectors().single().metadata["protocol"])
        assertEquals(listOf("input", "tool"), db.subgraphs().single().nodes)
        assertEquals("collapsed", db.subgraphs().single().metadata["view"])
        assertEquals(2L, db.vertices().getValue("tool").metadata["retry"])

        val model = db.semanticModel()
        assertEquals(AgentflowVertexKind.Input, model.vertices.first { it.id == "input" }.kind)
        assertEquals(AgentflowVertexKind.Tool, model.vertices.first { it.id == "tool" }.kind)
        assertEquals("model-a", model.subgraphs.single().metadata["model"])
        assertNull(model.subgraphs.single().metadata["view"])
    }

    @Test
    fun normalizesRemovedAndUnsupportedShapesWithStructuredDiagnostics() {
        val db = parse(
            """
                agentflow-beta TB
                  removed["Removed"]@{ shape: cylinder }
                  unsupported["Unsupported"]@{ shape: triangle }
            """.trimIndent(),
        )

        val data = db.getData()
        assertEquals(
            listOf("roundedRect", "roundedRect"),
            data.nodes.map { it.shape },
        )
        assertEquals(
            listOf(
                AgentflowDiagnosticId.ShapeRemoved to AgentflowDiagnosticSeverity.Error,
                AgentflowDiagnosticId.ShapeUnsupported to AgentflowDiagnosticSeverity.Warning,
            ),
            db.diagnostics().map { it.id to it.severity },
        )
        assertTrue(db.diagnostics().all { it.position != null })

        db.getData()
        assertEquals(2, db.diagnostics().size)
    }

    @Test
    fun recordsSourceMappingsAndRetypesStandaloneMetadataAsAttachment() {
        val db = parse(
            """
                agentflow-beta TB
                  a["Alpha"]
                  a@{ description: "metadata" }
                  flow group["Group"]
                    b["Beta"]
                  end
            """.trimIndent(),
            frontmatterLineOffset = 3,
        )

        assertEquals(
            AgentflowStatementType.Attachment,
            db.elementMappings().first { it.id == "a" && it.type == AgentflowStatementType.Attachment }.type,
        )
        val flow = db.elementMappings().single { it.type == AgentflowStatementType.Subgraph }
        assertEquals(7, flow.position.startLine)
        assertEquals(9, flow.position.endLine)
        assertEquals(
            AgentflowStatementType.Vertex,
            db.getElementAtPosition(8, 5)?.type,
        )
        assertEquals(4, db.mappingStats().totalElements)
    }

    @Test
    fun rejectsUnknownMetadataShapeAsStructuredParseError() {
        val result = AgentflowJisonParser().parse(
            """
                agentflow-beta TB
                  a@{ shape: unknown-shape }
            """.trimIndent(),
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        assertContains(error.message, "No such shape: unknown-shape.")
        assertEquals(2, assertIs<MermaidError.Parse>(error).line)
    }

    @Test
    fun rejectsUnterminatedFlowThroughTheGeneratedParseTable() {
        val result = AgentflowJisonParser().parse(
            """
                agentflow-beta TB
                  flow orphan["Orphan"]
                    a --> b
            """.trimIndent(),
        )

        assertIs<GMResult.Err<MermaidError>>(result)
    }

    @Test
    fun preservesBlankLinePositionsAfterMetadataBlocks() {
        // Mermaid.js 12.0.0:
        // agentflow/parser/agentflow-blank-line-fold.spec.ts
        val db = parse(
            """
                agentflow-beta
                flow f["F"]
                  a["A"]@{ shape: rounded }

                  b["B"]
                end
            """.trimIndent(),
        )

        assertEquals(5, db.getElementById("b")?.position?.startLine)
    }

    @Test
    fun stripsHorizontalWhitespaceAfterMetadataClosingBrace() {
        // The escaped closing brace keeps the upstream regex valid on Android ICU.
        val db = parse(
            "agentflow-beta TB\n  a[\"A\"]@{ shape: rounded }   \n\n  b[\"B\"]",
        )

        assertEquals(4, db.getElementById("b")?.position?.startLine)
    }

    private fun parse(
        source: String,
        frontmatterLineOffset: Int = 0,
    ): AgentflowDb =
        assertIs<GMResult.Ok<AgentflowDb>>(
            AgentflowJisonParser(frontmatterLineOffset = frontmatterLineOffset).parse(source),
        ).value
}
