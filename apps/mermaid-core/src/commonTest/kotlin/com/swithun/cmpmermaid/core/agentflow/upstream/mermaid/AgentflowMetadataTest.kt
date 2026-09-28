package com.swithun.cmpmermaid.core.agentflow.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/agentflow/parser/agentflow-metadata-trailing-comma.spec.ts
 * packages/mermaid/src/diagrams/agentflow/parser/agentflow-empty-metadata.spec.ts
 * packages/mermaid/src/diagrams/agentflow/parser/agentflow-metadata-prototype-keys.spec.ts
 * packages/mermaid/src/diagrams/agentflow/parser/agentflow-attachment-mapping.spec.ts
 * packages/mermaid/src/diagrams/agentflow/parser/agentflow-subgraph-span.spec.ts
 * packages/mermaid/src/diagrams/agentflow/agentflow-link-style.spec.ts
 */
class AgentflowMetadataTest {
    @Test
    fun multilineMetadataAllowsTrailingCommasWithoutChangingContentCommas() {
        val db = parse(
            """
                agentflow-beta TB
                  connector github["GitHub"]@{
                    protocol: "mcp",
                    endpoint: "https://api.github.com",
                    token_required: true,
                  }
                  a["Alpha"]@{
                    instruction: |
                      Do a thing,
                      then stop
                    tools: ["read",
                      "write"],
                    note: 'first,
                      second',
                  }
            """.trimIndent(),
        )

        assertEquals(
            mapOf<String, Any?>(
                "protocol" to "mcp",
                "endpoint" to "https://api.github.com",
                "token_required" to true,
            ),
            db.connectors().single().metadata,
        )
        val metadata = db.vertices().getValue("a").metadata
        assertEquals("Do a thing,\nthen stop\n", metadata["instruction"])
        assertEquals(listOf("read", "write"), metadata["tools"])
        assertEquals("first, second", metadata["note"])
    }

    @Test
    fun emptyMetadataBlocksAreNoOpsForVerticesAndFlows() {
        val single = parse(
            """
                agentflow-beta TB
                  flow f["F"]
                    a["A"]@{}
                  end
                  f@{}
            """.trimIndent(),
        )
        val multiline = parse(
            """
                agentflow-beta TB
                  flow f["F"]
                    a["A"]@{
                    }
                  end
                  f@{
                  }
            """.trimIndent(),
        )

        assertEquals(single.vertices().getValue("a").metadata, multiline.vertices().getValue("a").metadata)
        assertEquals(single.vertices().getValue("a").type, multiline.vertices().getValue("a").type)
        assertEquals(single.subgraphs().single().metadata, multiline.subgraphs().single().metadata)
    }

    @Test
    fun prototypeShapedMetadataKeysAreRemovedRecursively() {
        val db = parse(
            """
                agentflow-beta TB
                  a["A"]@{
                    constructor: "drop",
                    prototype: "drop",
                    params:
                      - name: first
                        __proto__:
                          polluted: true
                    description: "kept",
                  }
            """.trimIndent(),
        )

        val metadata = db.vertices().getValue("a").metadata
        assertEquals(setOf("params", "description"), metadata.keys)
        assertEquals("kept", metadata["description"])
        val params = assertIs<List<*>>(metadata["params"])
        val first = assertIs<Map<*, *>>(params.single())
        assertEquals("first", first["name"])
        assertFalse("__proto__" in first)
    }

    @Test
    fun malformedMetadataReturnsPositionedParseErrorWithFrontmatterOffset() {
        val result = AgentflowJisonParser(frontmatterLineOffset = 3).parse(
            """
                agentflow-beta TB
                  a["A"]@{
                :
                  }
            """.trimIndent(),
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        val parseError = assertIs<MermaidError.Parse>(error)
        assertTrue(parseError.line >= 5)
        assertTrue(parseError.column >= 1)
    }

    @Test
    fun standaloneAttachmentsHaveTheirOwnTypeAndFullSpan() {
        val db = parse(
            """
                agentflow-beta TB
                  connector github["GitHub"]
                  github@{ protocol: "mcp" }
                  flow pipeline["Pipeline"]
                    a["Alpha"]
                    a e1@--> b
                  end
                  pipeline@{ view: collapsed }
                  e1@{
                    instruction: "hand off"
                  }
            """.trimIndent(),
        )

        assertEquals(
            listOf(AgentflowStatementType.Connector, AgentflowStatementType.Attachment),
            db.elementMappings().filter { it.id == "github" }.map { it.type },
        )
        assertEquals(
            listOf(AgentflowStatementType.Subgraph, AgentflowStatementType.Attachment),
            db.elementMappings().filter { it.id == "pipeline" }.map { it.type },
        )
        val edgeAttachment = assertNotNull(
            db.elementMappings().find {
                it.id == "e1" && it.type == AgentflowStatementType.Attachment
            },
        )
        assertEquals(9, edgeAttachment.position.startLine)
        assertEquals(11, edgeAttachment.position.endLine)
        assertEquals(
            AgentflowStatementType.Attachment,
            db.getElementAtPosition(10, 8)?.type,
        )
    }

    @Test
    fun siblingAndNestedFlowMappingsEndAtTheirOwnEndKeyword() {
        val siblings = parse(
            """
                agentflow-beta TB

                  flow first["First"]
                    a --> b
                  end

                  flow second["Second"]
                    c --> d
                  end
            """.trimIndent(),
        )
        val first = assertNotNull(siblings.getElementById("first"))
        val second = assertNotNull(siblings.getElementById("second"))
        assertEquals(3, first.position.startLine)
        assertEquals(5, first.position.endLine)
        assertEquals(7, second.position.startLine)
        assertEquals(9, second.position.endLine)
        assertTrue(first.position.endLine < second.position.startLine)

        val nested = parse(
            """
                agentflow-beta TB
                  flow outer["Outer"]
                    flow inner["Inner"]
                      a["Alpha"]
                    end
                    c --> d
                  end
            """.trimIndent(),
        )
        assertEquals(5, assertNotNull(nested.getElementById("inner")).position.endLine)
        assertEquals(7, assertNotNull(nested.getElementById("outer")).position.endLine)
        assertEquals("a", nested.getElementAtPosition(4, 8)?.id)
        assertEquals("inner", nested.getElementAtPosition(3, 5)?.id)
    }

    @Test
    fun linkStyleRejectsOutOfBoundsIndicesAndAppliesValidCurves() {
        val invalidStyle = AgentflowJisonParser().parse(
            """
                agentflow-beta TB
                  a --> b
                  linkStyle 99 stroke:red
            """.trimIndent(),
        )
        val styleError = assertIs<GMResult.Err<MermaidError>>(invalidStyle).error
        assertContains(styleError.message, "index 99 for linkStyle is out of bounds")

        val invalidCurve = AgentflowJisonParser().parse(
            """
                agentflow-beta TB
                  a --> b
                  linkStyle 99 interpolate basis
            """.trimIndent(),
        )
        val curveError = assertIs<GMResult.Err<MermaidError>>(invalidCurve).error
        assertContains(curveError.message, "index 99 for linkStyle is out of bounds")

        val valid = parse(
            """
                agentflow-beta TB
                  a --> b
                  linkStyle 0 interpolate basis
            """.trimIndent(),
        )
        assertEquals("basis", valid.edges().single().interpolate)
    }

    private fun parse(source: String): AgentflowDb {
        val result = AgentflowJisonParser().parse(source)
        return assertIs<GMResult.Ok<AgentflowDb>>(result, result.toString()).value
    }
}
