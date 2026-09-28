package com.swithun.cmpmermaid.core.statediagram.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.officialStateDocumentationCases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StateJisonParserTest {
    @Test
    fun parsesStatesRelationsAndDescriptions() {
        val db = parse(
            """
            stateDiagram-v2
                [*] --> Idle
                Idle --> Running : start
                Running : Processing
                Running --> [*]
            """.trimIndent(),
        )

        assertEquals(
            setOf("root_start", "Idle", "Running", "root_end"),
            db.getStates().keys,
        )
        assertEquals(listOf("Processing"), db.getStates().getValue("Running").descriptions)
        assertEquals("start", db.getRelations()[1].relationTitle)
    }

    @Test
    fun translatesNestedStartEndAndConcurrencyRegions() {
        val db = parse(
            """
            stateDiagram-v2
                state Active {
                    [*] --> One
                    One --> [*]
                    --
                    [*] --> Two
                    Two --> [*]
                }
            """.trimIndent(),
        )

        val active = db.getStates().getValue("Active")
        val regions = active.document.orEmpty().filterIsInstance<StateNodeStatement>()
        assertEquals(2, regions.size)
        assertTrue(regions.all { it.type == StateNodeType.Divider })
        val firstRelation = regions.first().document.orEmpty()
            .filterIsInstance<StateRelationStatement>()
            .first()
        assertEquals("divider-id-1_start", firstRelation.state1.id)
    }

    @Test
    fun parsesEveryOfficialStateDocumentationExample() {
        assertEquals(22, officialStateDocumentationCases.size)
        val failures = officialStateDocumentationCases.mapNotNull { case ->
            val preprocessed = when (val result = MermaidPreprocessor.preprocess(case.source)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return@mapNotNull "${case.id}: ${result.error.message}"
            }
            when (
                val result = StateJisonParser()
                    .parse(preprocessed.code.cleaned + "\n")
            ) {
                is GMResult.Ok -> null
                is GMResult.Err -> "${case.id}: ${result.error.message}"
            }
        }
        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 State parser cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun parse(source: String): StateDb {
        val result = StateJisonParser().parse(source + "\n")
        return assertIs<GMResult.Ok<StateDb>>(result).value
    }
}
