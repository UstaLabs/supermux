package com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.officialErDocumentationCases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ErJisonParserTest {
    @Test
    fun parsesEntitiesAttributesAndRelationshipSemantics() {
        val db = parse(
            """
            erDiagram
                CUSTOMER ||--o{ ORDER : places
                CUSTOMER {
                    string id PK
                    string email UK "Primary contact"
                    string? nickname
                }
            """.trimIndent(),
        )

        val customer = db.getEntities().getValue("CUSTOMER")
        assertEquals(listOf("id", "email", "nickname"), customer.attributes.map(ErAttribute::name))
        assertEquals(listOf(ErAttributeKey.Primary), customer.attributes[0].keys)
        assertEquals("Primary contact", customer.attributes[1].comment)
        assertEquals("string?", customer.attributes[2].type)

        val relationship = db.getRelationships().single()
        assertEquals(customer.id, relationship.entityA)
        assertEquals(db.getEntities().getValue("ORDER").id, relationship.entityB)
        assertEquals("places", relationship.roleA)
        assertEquals(ErCardinality.ZeroOrMore, relationship.specification.cardA)
        assertEquals(ErCardinality.OnlyOne, relationship.specification.cardB)
        assertEquals(ErIdentification.Identifying, relationship.specification.relationType)
    }

    @Test
    fun parsesAliasesClassesStylesAndNestedSubgraphs() {
        val db = parse(
            """
            erDiagram
                classDef warm fill:#ffedd5,stroke:#ea580c
                subgraph domain [Customer Domain]
                    direction LR
                    customer["Customer Account"]:::warm {
                        int id PK
                    }
                    subgraph orders
                        ORDER
                    end
                end
                style domain stroke:#2563eb
            """.trimIndent(),
        )

        val customer = db.getEntities().getValue("customer")
        assertEquals("Customer Account", customer.alias)
        assertTrue("warm" in customer.classes)
        assertEquals(2, db.getSubGraphs().size)
        assertEquals("orders", db.getSubGraphs()[0].id)
        assertEquals("domain", db.getSubGraphs()[1].id)
        assertEquals("LR", db.getSubGraphs()[1].direction)
        assertEquals(listOf("customer", "orders"), db.getSubGraphs()[1].nodes)
        assertEquals(listOf("stroke:#2563eb"), db.getSubGraphs()[1].styles)
    }

    @Test
    fun parsesEveryOfficialErDocumentationExample() {
        assertEquals(24, officialErDocumentationCases.size)
        val failures = officialErDocumentationCases.mapNotNull { case ->
            val preprocessed = when (val result = MermaidPreprocessor.preprocess(case.source)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return@mapNotNull "${case.id}: ${result.error.message}"
            }
            when (
                val result = ErJisonParser()
                    .parse(preprocessed.code.cleaned + "\n")
            ) {
                is GMResult.Ok -> null
                is GMResult.Err -> "${case.id}: ${result.error.message}"
            }
        }
        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 ER parser cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun parse(source: String): ErDb {
        val result = ErJisonParser().parse(source + "\n")
        return assertIs<GMResult.Ok<ErDb>>(result).value
    }
}
