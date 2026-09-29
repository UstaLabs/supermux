package com.swithun.cmpmermaid.core.requirement.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequirementJisonParserTest {
    @Test
    fun parsesAllRequirementFieldsAndElementFields() {
        val db = parse(
            """
            requirementDiagram
              functionalRequirement checkout {
                id: "PAY-1"
                text: "Card payments must be authorised."
                risk: high
                verifyMethod: test
              }
              element gateway {
                type: "payment service"
                docRef: docs/gateway
              }
            """.trimIndent(),
        )

        val requirement = db.getRequirements().getValue("checkout")
        assertEquals(RequirementType.Functional, requirement.type)
        assertEquals("PAY-1", requirement.requirementId)
        assertEquals("Card payments must be authorised.", requirement.text)
        assertEquals(RequirementRisk.High, requirement.risk)
        assertEquals(RequirementVerifyMethod.Test, requirement.verifyMethod)
        val element = db.getElements().getValue("gateway")
        assertEquals("payment service", element.type)
        assertEquals("docs/gateway", element.docRef)
    }

    @Test
    fun parsesAllRequirementRiskAndVerifyEnums() {
        val db = parse(
            """
            requirementDiagram
              requirement base {
                risk: low
                verifyMethod: analysis
              }
              interfaceRequirement interface {
                risk: medium
                verifyMethod: demonstration
              }
              performanceRequirement performance {
                risk: high
                verifyMethod: inspection
              }
              physicalRequirement physical {
                verifyMethod: test
              }
              designConstraint design {
              }
            """.trimIndent(),
        )

        assertEquals(RequirementRisk.Low, db.getRequirements().getValue("base").risk)
        assertEquals(
            RequirementVerifyMethod.Demonstration,
            db.getRequirements().getValue("interface").verifyMethod,
        )
        assertEquals(RequirementType.Performance, db.getRequirements().getValue("performance").type)
        assertEquals(RequirementType.Physical, db.getRequirements().getValue("physical").type)
        assertEquals(
            RequirementType.DesignConstraint,
            db.getRequirements().getValue("design").type,
        )
    }

    @Test
    fun parsesBothRelationshipDirectionsAndAllTypes() {
        val db = parse(
            """
            requirementDiagram
              a - contains -> b
              b <- copies - c
              c - derives -> d
              d - satisfies -> e
              e - verifies -> f
              f - refines -> g
              g - traces -> h
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                RequirementRelation(RequirementRelationshipType.Contains, "a", "b"),
                RequirementRelation(RequirementRelationshipType.Copies, "c", "b"),
                RequirementRelation(RequirementRelationshipType.Derives, "c", "d"),
                RequirementRelation(RequirementRelationshipType.Satisfies, "d", "e"),
                RequirementRelation(RequirementRelationshipType.Verifies, "e", "f"),
                RequirementRelation(RequirementRelationshipType.Refines, "f", "g"),
                RequirementRelation(RequirementRelationshipType.Traces, "g", "h"),
            ),
            db.getRelationships(),
        )
    }

    @Test
    fun parsesDirectionsAndAccessibilityMetadata() {
        listOf("TB", "BT", "LR", "RL").forEach { direction ->
            val db = parse(
                """
                requirementDiagram
                  direction $direction
                  accTitle: Checkout requirements
                  accDescr {
                    Requirements and verification links.
                  }
                """.trimIndent(),
            )

            assertEquals(direction, db.getDirection())
            assertEquals("Checkout requirements", db.accessibilityTitle)
            assertEquals(
                "Requirements and verification links.",
                db.accessibilityDescription,
            )
        }
    }

    @Test
    fun appliesDirectStylesAndClassesInDeclarationOrder() {
        val db = parse(
            """
            requirementDiagram
              requirement req:::important {
              }
              element component {
              }
              classDef important fill:#f96,stroke:#333,stroke-width:4px
              class component important
              style req,component color:blue
            """.trimIndent(),
        )

        assertEquals(
            listOf("fill:#f96", "stroke:#333", "stroke-width:4px", "color:blue"),
            db.getRequirements().getValue("req").cssStyles,
        )
        assertEquals(
            listOf("fill:#f96", "stroke:#333", "stroke-width:4px", "color:blue"),
            db.getElements().getValue("component").cssStyles,
        )
        assertEquals(
            listOf("default", "important"),
            db.getRequirements().getValue("req").classes,
        )
    }

    @Test
    fun duplicateDefinitionsKeepTheFirstNodeLikeUpstreamMap() {
        val db = parse(
            """
            requirementDiagram
              requirement req {
                id: first
              }
              performanceRequirement req {
                id: second
              }
            """.trimIndent(),
        )

        assertEquals(1, db.getRequirements().size)
        assertEquals("first", db.getRequirements().getValue("req").requirementId)
        assertEquals(RequirementType.Requirement, db.getRequirements().getValue("req").type)
    }

    @Test
    fun reportsStructuredParseErrorsWithFrontmatterOffset() {
        val result = RequirementJisonParser(lineOffset = 4).parse(
            """
            requirementDiagram
              requirement broken {
                risk: impossible
              }
            """.trimIndent() + "\n",
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        assertIs<MermaidError.Parse>(error)
        assertTrue(error.line >= 6)
    }

    @Test
    fun rejectsTheUpstreamPercentStyleTokenMismatch() {
        val result = RequirementJisonParser().parse(
            """
            requirementDiagram
              requirement req {
              }
              style req width:50%
            """.trimIndent() + "\n",
        )

        assertIs<GMResult.Err<MermaidError>>(result)
    }

    private fun parse(source: String): RequirementDb {
        val result = RequirementJisonParser().parse(source + "\n")
        return assertIs<GMResult.Ok<RequirementDb>>(result).value
    }
}
