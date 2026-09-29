package com.swithun.cmpmermaid.core

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialClassDocumentationTest {
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val charactersPerLine = (request.maxWidth / 8f).toInt().coerceAtLeast(1)
            val lineCount = request.text
                .split('\n')
                .sumOf { line ->
                    ceil(line.length.toDouble() / charactersPerLine)
                        .toInt()
                        .coerceAtLeast(1)
                }
            TextMetrics(
                width = minOf(
                    request.maxWidth,
                    request.text.lineSequence().maxOfOrNull(String::length).orZero() * 8f,
                ),
                height = lineCount * request.fontSize * request.lineHeight,
            )
        },
        options = MermaidRenderOptions(look = "neo"),
    )
    private val engine = MermaidEngine()

    @Test
    fun rendersEveryMermaid12ClassDocumentationExample() {
        assertEquals(38, officialClassDocumentationCases.size)

        val failures = officialClassDocumentationCases.mapNotNull { case ->
            when (val result = engine.render(case.source, context)) {
                is GMResult.Ok -> validateScene(case.id, result.value)
                is GMResult.Err -> "${case.id}: ${result.error.message}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Class documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun validateScene(
        caseId: String,
        scene: MermaidScene,
    ): String? {
        if (!scene.width.isFinite() || !scene.height.isFinite() ||
            scene.width <= 0f || scene.height <= 0f
        ) {
            return "$caseId: invalid scene size ${scene.width} x ${scene.height}"
        }
        if (scene.elements.isEmpty()) {
            return "$caseId: empty scene"
        }
        scene.elements.forEach { element ->
            val bounds = when (element) {
                is SceneAsset -> element.bounds
                is SceneShape -> element.bounds
                is SceneText -> element.bounds
                is ScenePath -> null
            }
            if (bounds != null &&
                (!bounds.left.isFinite() || !bounds.top.isFinite() ||
                    !bounds.width.isFinite() || !bounds.height.isFinite() ||
                    bounds.width < 0f || bounds.height < 0f)
            ) {
                return "$caseId: invalid ${element::class.simpleName} bounds $bounds"
            }
            if (element is ScenePath &&
                (element.points.size < 2 ||
                    element.points.any { point -> !point.x.isFinite() || !point.y.isFinite() })
            ) {
                return "$caseId: invalid path ${element.id}"
            }
        }
        return null
    }

    private fun Int?.orZero(): Int = this ?: 0
}
