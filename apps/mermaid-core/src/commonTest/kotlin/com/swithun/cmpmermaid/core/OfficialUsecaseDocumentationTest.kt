package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialUsecaseDocumentationTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lines = request.text.split('\n')
            TextMetrics(
                width = (lines.maxOfOrNull(String::length) ?: 0) * request.fontSize * 0.55f,
                height = lines.size * request.fontSize * request.lineHeight,
                lineCount = lines.size,
            )
        },
    )

    @Test
    fun rendersEveryOfficialUsecaseDocumentationExample() {
        assertEquals(24, officialUsecaseDocumentationCases.size)
        val failures = officialUsecaseDocumentationCases.mapNotNull { fixture ->
            when (val result = engine.render(fixture.source, context)) {
                is GMResult.Err -> "${fixture.id}: ${result.error.message}"
                is GMResult.Ok -> validate(fixture, result.value)
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Use Case documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun validate(
        fixture: MermaidUsecaseDocCase,
        scene: MermaidScene,
    ): String? = when {
        scene.width <= 0f -> "${fixture.id}: non-positive scene width"
        scene.height <= 0f -> "${fixture.id}: non-positive scene height"
        scene.elements.filterIsInstance<SceneShape>().isEmpty() ->
            "${fixture.id}: no rendered Use Case shape"
        else -> null
    }
}
