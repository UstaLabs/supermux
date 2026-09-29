package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialSwimlaneDocumentationTest {
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
    fun rendersEveryOfficialSwimlaneDocumentationExample() {
        assertEquals(13, officialSwimlaneDocumentationCases.size)
        val failures = officialSwimlaneDocumentationCases.mapNotNull { fixture ->
            when (val result = engine.render(fixture.source, context)) {
                is GMResult.Err -> "${fixture.id}: ${result.error.message}"
                is GMResult.Ok -> validate(fixture, result.value)
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Swimlanes documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun validate(
        fixture: MermaidSwimlaneDocCase,
        scene: MermaidScene,
    ): String? = when {
        scene.width <= 0f -> "${fixture.id}: non-positive scene width"
        scene.height <= 0f -> "${fixture.id}: non-positive scene height"
        scene.elements.filterIsInstance<SceneShape>()
            .none { it.id.startsWith("subgraph_") } ->
            "${fixture.id}: missing swimlane shapes"
        scene.elements.filterIsInstance<SceneText>().isEmpty() ->
            "${fixture.id}: missing swimlane labels"
        else -> null
    }

    private fun Int?.orZero(): Int = this ?: 0
}
