package com.swithun.cmpmermaid.core.agentflow.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/agentflow/parser/agentflow.spec.ts
 * packages/mermaid/src/diagrams/agentflow/parser/agentflow-containment-cycle.spec.ts
 * packages/mermaid/src/diagrams/agentflow/parser/agentflow-flow-self-reference.spec.ts
 * packages/mermaid/src/diagrams/agentflow/parser/agentflow-global.spec.ts
 * packages/mermaid/src/diagrams/agentflow/containerOrder.spec.ts
 * packages/mermaid/src/diagrams/agentflow/agentflow-semantic-model.spec.ts
 */
class AgentflowLayoutTest {
    @Test
    fun collapsedFlowHidesDescendantsAndRedirectsOnlyBoundaryEdges() {
        val data = parse(
            """
                agentflow-beta TB
                  external["External"]
                  flow pipeline["Pipeline"]
                    a["A"]
                    b["B"]
                    a --> b
                    a --> a
                  end
                  external --> a
                  b --> external
                  pipeline@{ view: collapsed }
            """.trimIndent(),
        ).getData()

        assertEquals(setOf("external", "pipeline"), data.nodes.mapTo(linkedSetOf()) { it.id })
        val pipeline = assertNotNull(data.nodes.find { it.id == "pipeline" })
        assertEquals("collapsedGroup", pipeline.shape)
        assertFalse(pipeline.isGroup)
        assertEquals("flow", pipeline.metadata["containerType"])
        assertEquals(
            listOf(
                "pipeline" to "pipeline",
                "external" to "pipeline",
                "pipeline" to "external",
            ),
            data.edges.map { it.start to it.end },
        )
    }

    @Test
    fun globalMembershipIsOrderIndependentAndKeepsEdges() {
        val db = parse(
            """
                agentflow-beta TB
                  flow pipeline["Pipeline"]
                    shared --> local
                  end
                  global
                    shared --> external
                  end
            """.trimIndent(),
        )

        assertEquals(listOf("local"), db.subgraphs().single().nodes)
        val data = db.getData()
        assertNull(assertNotNull(data.nodes.find { it.id == "shared" }).parentId)
        assertNull(assertNotNull(data.nodes.find { it.id == "external" }).parentId)
        assertEquals(
            "pipeline",
            assertNotNull(data.nodes.find { it.id == "local" }).parentId,
        )
        assertTrue(data.edges.any { it.start == "shared" && it.end == "local" })
        assertTrue(data.edges.any { it.start == "shared" && it.end == "external" })
        assertEquals(listOf("pipeline"), data.nodes.filter { it.isGroup }.map { it.id })
    }

    @Test
    fun containmentCycleKeepsFirstParentAndReportsDroppedNesting() {
        val db = parse(
            """
                agentflow-beta TB
                  flow A["Flow A"]
                    a1["Task A1"]
                    a1 --> B
                  end
                  flow B["Flow B"]
                    b1["Task B1"]
                    b1 --> A
                  end
            """.trimIndent(),
        )

        val parentById = db.getData().nodes.associate { it.id to it.parentId }
        assertEquals("B", parentById["A"])
        assertNull(parentById["B"])
        assertEquals("A", parentById["a1"])
        assertEquals("B", parentById["b1"])
        parentById.keys.forEach { id ->
            val seen = mutableSetOf<String>()
            var parent = parentById[id]
            while (parent != null) {
                assertTrue(seen.add(parent), "Containment cycle from $id via $parent")
                parent = parentById[parent]
            }
        }
        val diagnostic = assertNotNull(
            db.diagnostics().find {
                it.id == AgentflowDiagnosticId.ContainmentViolation && it.nodeId == "B"
            },
        )
        assertEquals(AgentflowDiagnosticSeverity.Warning, diagnostic.severity)
    }

    @Test
    fun collapsedContainmentCycleStillDrawsTheRequestedRoot() {
        val data = parse(
            """
                agentflow-beta TB
                  flow A["Flow A"]@{ view: collapsed }
                    a1["Task A1"]
                    a1 --> B
                  end
                  flow B["Flow B"]
                    b1["Task B1"]
                    b1 --> A
                  end
            """.trimIndent(),
        ).getData()

        val collapsed = assertNotNull(data.nodes.find { it.id == "A" })
        assertEquals("collapsedGroup", collapsed.shape)
        assertNull(collapsed.parentId)
    }

    @Test
    fun nestedFlowSelfReferenceDoesNotBreakTheRealParentChain() {
        val db = parse(
            """
                agentflow-beta TB
                  flow top["Top"]
                    flow mid["Mid"]
                      flow leafFlow["Leaf"]
                        c["Task C"]
                      end
                      m["Task M"]
                      m --> mid
                    end
                  end
            """.trimIndent(),
        )

        assertFalse(assertNotNull(db.subgraphs().find { it.id == "mid" }).nodes.contains("mid"))
        val parentById = db.getData().nodes.associate { it.id to it.parentId }
        assertEquals("mid", parentById["leafFlow"])
        assertEquals("top", parentById["mid"])
        assertNull(parentById["top"])
        assertEquals("mid", parentById["m"])
        assertEquals("leafFlow", parentById["c"])
        assertTrue(db.getData().edges.any { it.start == "m" && it.end == "mid" })
    }

    @Test
    fun containerSlotsFollowDeclarationPreorderAndIncludeCollapsedFlows() {
        val data = parse(
            """
                agentflow-beta TB
                  flow outer["Outer"]
                    flow inner["Inner"]
                      a["A"]
                    end
                  end
                  flow collapsed["Collapsed"]
                    b["B"]
                  end
                  collapsed@{ view: collapsed }
                  flow sibling["Sibling"]
                    c["C"]
                  end
            """.trimIndent(),
        ).getData(paletteLength = 12)

        val slots = data.nodes
            .filter { it.shape == "flowGroup" || it.shape == "collapsedGroup" }
            .associate { it.id to it.colorIndex }
        assertEquals(7, slots["outer"])
        assertEquals(8, slots["inner"])
        assertEquals(9, slots["collapsed"])
        assertEquals(10, slots["sibling"])
    }

    @Test
    fun kindSlotsAndAgentflowEdgeRenderingStayDistinct() {
        val data = parse(
            """
                agentflow-beta TB
                  task["Task"]@{ shape: task }
                  tool["Tool"]@{ shape: tool }
                  input["Input"]@{ shape: input }
                  decision["Decision"]@{ shape: decision }
                  ref["Reference"]@{ shape: refdoc }
                  action["Action"]@{ shape: action }
                  connector api["API"]
                  task --> tool
                  tool -.- ref
                  decision --x action
            """.trimIndent(),
        ).getData(paletteLength = 12)

        val slots = data.nodes.associate { it.id to it.colorIndex }
        assertEquals(1, slots["task"])
        assertEquals(0, slots["tool"])
        assertEquals(3, slots["input"])
        assertEquals(2, slots["decision"])
        assertEquals(4, slots["ref"])
        assertEquals(6, slots["action"])
        assertEquals(5, slots["api"])

        val reference = assertNotNull(data.edges.find { it.start == "tool" })
        assertEquals("dotted", reference.pattern)
        assertEquals("none", reference.arrowTypeEnd)
        val failure = assertNotNull(data.edges.find { it.start == "decision" })
        assertEquals("normal", failure.pattern)
        assertEquals("arrow_cross", failure.arrowTypeEnd)
    }

    @Test
    fun semanticModelIgnoresStylingAndCollapsedPresentationState() {
        val expanded = parse(
            """
                agentflow-beta TB
                  flow pipeline["Pipeline"]
                    a --> b
                  end
            """.trimIndent(),
        ).semanticModel()
        val styledAndCollapsed = parse(
            """
                agentflow-beta TB
                  classDef highlight fill:#f9f,stroke:#333,stroke-width:2px
                  flow pipeline["Pipeline"]
                    a:::highlight --> b
                  end
                  style a fill:#0f0
                  linkStyle 0 stroke:red,stroke-width:3px
                  pipeline@{ view: collapsed }
            """.trimIndent(),
        ).semanticModel()

        assertEquals(
            expanded.copy(diagnostics = emptyList()),
            styledAndCollapsed.copy(diagnostics = emptyList()),
        )
    }

    private fun parse(source: String): AgentflowDb =
        assertIs<GMResult.Ok<AgentflowDb>>(AgentflowJisonParser().parse(source)).value
}
