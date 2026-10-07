package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialRailroadDocumentationTest {
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
    fun rendersEveryOfficialRailroadDocumentationExample() {
        assertEquals(14, officialRailroadDocumentationCases.size)
        val failures = officialRailroadDocumentationCases.mapNotNull { fixture ->
            when (val result = engine.render(fixture.source, context)) {
                is GMResult.Err -> "${fixture.id}: ${result.error.message}"
                is GMResult.Ok -> validate(fixture, result.value)
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Railroad documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun validate(
        fixture: MermaidRailroadDocCase,
        scene: MermaidScene,
    ): String? = when {
        scene.width <= 0f -> "${fixture.id}: non-positive scene width"
        scene.height <= 0f -> "${fixture.id}: non-positive scene height"
        scene.elements.filterIsInstance<SceneShape>()
            .none { shape -> shape.id.startsWith("railroad-") } ->
            "${fixture.id}: missing Railroad symbols"
        scene.elements.filterIsInstance<ScenePath>()
            .none { path -> path.id.startsWith("railroad-line-") } ->
            "${fixture.id}: missing Railroad paths"
        else -> null
    }

    private fun Int?.orZero(): Int = this ?: 0
}
