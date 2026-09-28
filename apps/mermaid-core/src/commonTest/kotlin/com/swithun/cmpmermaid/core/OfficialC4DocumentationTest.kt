package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialC4DocumentationTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lines = request.text.lines()
            TextMetrics(
                width = minOf(
                    request.maxWidth,
                    lines.maxOfOrNull(String::length).orZero() *
                        request.fontSize * 0.55f,
                ),
                height = lines.size * request.fontSize * request.lineHeight,
                lineCount = lines.size,
            )
        },
    )

    @Test
    fun rendersEveryOfficialC4DocumentationExample() {
        assertEquals(6, officialC4DocumentationCases.size)
        val failures = officialC4DocumentationCases.mapNotNull { fixture ->
            when (val result = engine.render(fixture.source, context)) {
                is GMResult.Err -> "${fixture.id}: ${result.error.message}"
                is GMResult.Ok -> validate(fixture, result.value)
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 C4 documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun validate(
        fixture: MermaidC4DocCase,
        scene: MermaidScene,
    ): String? = when {
        scene.width <= 0f -> "${fixture.id}: non-positive scene width"
        scene.height <= 0f -> "${fixture.id}: non-positive scene height"
        scene.elements.filterIsInstance<SceneShape>()
            .none { shape -> shape.id.startsWith("c4-shape-") } ->
            "${fixture.id}: missing C4 elements"
        scene.elements.filterIsInstance<ScenePath>()
            .none { path -> path.id.startsWith("c4-relation-") } ->
            "${fixture.id}: missing C4 relationships"
        else -> null
    }

    private fun Int?.orZero(): Int = this ?: 0
}
