package com.swithun.cmpmermaid.core.agentflow.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/agentflow/conformance/runner.ts
 */
class AgentflowConformanceTest {
    @Test
    fun matchesEveryUpstreamAgentflowConformanceFixture() {
        assertEquals(8, agentflowConformanceCases.size)

        agentflowConformanceCases.forEach { fixture ->
            when (val parsed = AgentflowJisonParser().parse(fixture.source)) {
                is GMResult.Err -> {
                    assertEquals(
                        "parse-error",
                        fixture.outcome,
                        "${fixture.name}: unexpected parse failure ${parsed.error}",
                    )
                    fixture.parseErrorContains?.let { expected ->
                        assertContains(
                            parsed.error.message,
                            expected,
                            message = fixture.name,
                        )
                    }
                }

                is GMResult.Ok -> {
                    assertTrue(
                        fixture.outcome != "parse-error",
                        "${fixture.name}: expected parse error but parsing succeeded",
                    )
                    val db = parsed.value
                    db.getData()
                    val diagnostics = db.diagnostics()
                    val actualOutcome = when {
                        diagnostics.any {
                            it.severity == AgentflowDiagnosticSeverity.Error
                        } -> "error"
                        diagnostics.any {
                            it.severity == AgentflowDiagnosticSeverity.Warning
                        } -> "warning"
                        else -> "valid"
                    }
                    assertEquals(fixture.outcome, actualOutcome, fixture.name)
                    assertDiagnostics(fixture, diagnostics)
                    assertSemanticModel(fixture, db.semanticModel())
                }
            }
        }
    }

    private fun assertDiagnostics(
        fixture: AgentflowConformanceCase,
        diagnostics: List<AgentflowDiagnostic>,
    ) {
        val expectedDiagnostics = fixture.diagnostics ?: return
        expectedDiagnostics.forEach { expected ->
            assertTrue(
                diagnostics.any { actual ->
                    actual.id.wireName() == expected.id &&
                        (expected.nodeId == null || actual.nodeId == expected.nodeId) &&
                        (expected.edgeId == null || actual.edgeId == expected.edgeId) &&
                        (expected.line == null || actual.position?.startLine == expected.line)
                },
                "${fixture.name}: missing diagnostic $expected; actual=$diagnostics",
            )
        }
        if (!fixture.allowExtraDiagnostics) {
            assertEquals(
                expectedDiagnostics.size,
                diagnostics.size,
                "${fixture.name}: unexpected diagnostics $diagnostics",
            )
        }
    }

    private fun assertSemanticModel(
        fixture: AgentflowConformanceCase,
        model: AgentflowSemanticModel,
    ) {
        fixture.vertices.forEach { expected ->
            val actual = assertNotNull(
                model.vertices.firstOrNull { it.id == expected.id },
                "${fixture.name}: missing semantic vertex ${expected.id}",
            )
            expected.vertexKind?.let { kind ->
                assertEquals(kind, actual.kind.wireName(), "${fixture.name}:${expected.id}")
            }
            expected.metadata?.forEach { (key, value) ->
                assertEquals(
                    value,
                    actual.metadata[key],
                    "${fixture.name}:${expected.id}.metadata.$key",
                )
            }
        }
        fixture.edges.forEach { expected ->
            val actual = assertNotNull(
                model.edges.firstOrNull { edge ->
                    when {
                        expected.id != null -> edge.id == expected.id
                        expected.start != null && expected.end != null ->
                            edge.start == expected.start && edge.end == expected.end
                        else -> false
                    }
                },
                "${fixture.name}: missing semantic edge $expected",
            )
            expected.edgeSemantic?.let { semantic ->
                assertEquals(
                    semantic,
                    actual.semantic?.wireName(),
                    "${fixture.name}:${actual.start}->${actual.end}",
                )
            }
        }
    }
}

private fun AgentflowDiagnosticId.wireName(): String = when (this) {
    AgentflowDiagnosticId.ShapeUnsupported -> "SHAPE_UNSUPPORTED"
    AgentflowDiagnosticId.ShapeRemoved -> "SHAPE_REMOVED"
    AgentflowDiagnosticId.EdgeOperatorUnsupported -> "EDGE_OPERATOR_UNSUPPORTED"
    AgentflowDiagnosticId.ReferenceEdgeLabelRejected -> "REFERENCE_EDGE_LABEL_REJECTED"
    AgentflowDiagnosticId.ConnectorRefUnresolved -> "CONNECTOR_REF_UNRESOLVED"
    AgentflowDiagnosticId.ConnectorRefNotAConnector -> "CONNECTOR_REF_NOT_A_CONNECTOR"
    AgentflowDiagnosticId.MetadataKeyMisapplied -> "METADATA_KEY_MISAPPLIED"
    AgentflowDiagnosticId.DuplicateIdNode -> "DUPLICATE_ID_NODE"
    AgentflowDiagnosticId.ReservedSyntheticId -> "RESERVED_SYNTHETIC_ID"
    AgentflowDiagnosticId.ContainmentViolation -> "CONTAINMENT_VIOLATION"
    AgentflowDiagnosticId.EdgeSemanticContradiction -> "EDGE_SEMANTIC_CONTRADICTION"
    AgentflowDiagnosticId.FlowNoInput -> "FLOW_NO_INPUT"
}

private fun AgentflowVertexKind.wireName(): String = when (this) {
    AgentflowVertexKind.Tool -> "tool"
    AgentflowVertexKind.Action -> "action"
    AgentflowVertexKind.Input -> "input"
    AgentflowVertexKind.ReferenceDocument -> "refdoc"
    AgentflowVertexKind.Decision -> "decision"
    AgentflowVertexKind.Connector -> "connector"
    AgentflowVertexKind.Task -> "task"
}

private fun AgentflowEdgeSemantic.wireName(): String = when (this) {
    AgentflowEdgeSemantic.Sequence -> "sequence"
    AgentflowEdgeSemantic.Reference -> "reference"
    AgentflowEdgeSemantic.Failure -> "failure"
}
