package com.swithun.cmpmermaid.core

import com.swithun.cmpmermaid.core.generated.InvalidSourceCorpusCase
import com.swithun.cmpmermaid.core.generated.StabilityCorpusCase
import com.swithun.cmpmermaid.core.generated.invalidSourceCorpusCases
import com.swithun.cmpmermaid.core.generated.productionCorpusCases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InvalidSourceCorpusTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * request.fontSize * 0.55f,
                height = request.fontSize * request.lineHeight,
            )
        },
    )

    @Test
    fun returnsStableContentErrorsForEveryDiagramFamily() {
        assertEquals(EXPECTED_DIAGRAM_IDS, invalidSourceCorpusCases.map { it.diagramId }.toSet())

        invalidSourceCorpusCases.forEach { case ->
            val first = renderError(case)
            val second = renderError(case)

            assertEquals(
                MermaidRenderErrorType.CONTENT_ERROR,
                first.renderErrorType,
                "${case.diagramId} classified malformed source as ${first.renderErrorType}",
            )
            assertTrue(
                first.message.isNotBlank(),
                "${case.diagramId} returned an empty error message",
            )
            assertEquals(
                first,
                second,
                "${case.diagramId} returned a non-deterministic error",
            )
        }
    }

    @Test
    fun malformedSourceDoesNotPoisonFollowingValidRender() {
        val representatives = productionCorpusCases
            .groupBy(StabilityCorpusCase::diagramId)
            .mapValues { (_, cases) -> cases.first() }

        assertEquals(EXPECTED_DIAGRAM_IDS, representatives.keys)
        invalidSourceCorpusCases.forEach { invalidCase ->
            renderError(invalidCase)

            val validCase = representatives.getValue(invalidCase.diagramId)
            val validResult = engine.render(
                source = validCase.source,
                context = context.copy(
                    options = MermaidRenderOptions(layout = validCase.layout),
                ),
            )
            assertIs<GMResult.Ok<MermaidScene>>(
                validResult,
                "${invalidCase.diagramId} failed after malformed input: " +
                    "${(validResult as? GMResult.Err)?.error}",
            )
        }
    }

    private fun renderError(case: InvalidSourceCorpusCase): MermaidError {
        val result = engine.render(case.source, context)
        return assertIs<GMResult.Err<MermaidError>>(
            result,
            "${case.diagramId} unexpectedly rendered malformed source:\n${case.source}",
        ).error
    }

    private companion object {
        val EXPECTED_DIAGRAM_IDS: Set<String> = setOf(
            "flowchart",
            "swimlanes",
            "architecture",
            "c4",
            "railroad",
            "treeview",
            "xychart",
            "quadrant",
            "timeline",
            "kanban",
            "sequence",
            "class",
            "state",
            "er",
            "gantt",
            "pie",
            "journey",
            "requirement",
            "gitgraph",
            "mindmap",
            "packet",
            "radar",
            "sankey",
            "treemap",
            "venn",
            "ishikawa",
            "cynefin",
            "block",
            "eventmodeling",
            "agentflow",
            "usecase",
            "wardley",
            "zenuml",
        )
    }
}
