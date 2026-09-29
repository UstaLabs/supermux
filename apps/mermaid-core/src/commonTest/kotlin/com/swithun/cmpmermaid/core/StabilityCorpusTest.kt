package com.swithun.cmpmermaid.core

import com.swithun.cmpmermaid.core.generated.stabilityCorpusCases
import kotlin.math.ceil
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StabilityCorpusTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val charactersPerLine = (request.maxWidth / (request.fontSize * 0.55f))
                .toInt()
                .coerceAtLeast(1)
            val lineCount = request.text.lineSequence().sumOf { line ->
                ceil(line.length.toDouble() / charactersPerLine)
                    .toInt()
                    .coerceAtLeast(1)
            }
            TextMetrics(
                width = min(
                    request.maxWidth,
                    request.text.lineSequence()
                        .maxOfOrNull(String::length)
                        .orZero() * request.fontSize * 0.55f,
                ),
                height = lineCount * request.fontSize * request.lineHeight,
            )
        },
    )

    @Test
    fun rendersIndependentComplexReleaseCandidateCorpus() {
        assertEquals(167, stabilityCorpusCases.size)
        stabilityCorpusCases.forEach { case ->
            val scene = render(case)

            assertTrue(
                scene.width.isFinite() && scene.width > 0f,
                "${case.id} produced invalid width ${scene.width}",
            )
            assertTrue(
                scene.height.isFinite() && scene.height > 0f,
                "${case.id} produced invalid height ${scene.height}",
            )
            assertTrue(
                scene.elements.isNotEmpty(),
                "${case.id} produced an empty scene",
            )
        }
    }

    @Test
    fun rendersIndependentCorpusDeterministically() {
        stabilityCorpusCases.forEach { case ->
            assertEquals(
                expected = render(case),
                actual = render(case),
                message = "${case.id} produced a non-deterministic SceneGraph",
            )
        }
    }

    private fun render(
        case: com.swithun.cmpmermaid.core.generated.StabilityCorpusCase,
    ): MermaidScene {
        val result = engine.render(
            source = case.source,
            context = context.copy(
                options = MermaidRenderOptions(layout = case.layout),
            ),
        )
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "${case.id} failed with ${(result as? GMResult.Err)?.error}:\n${case.source}",
        ).value
    }

    private fun Int?.orZero(): Int = this ?: 0
}
