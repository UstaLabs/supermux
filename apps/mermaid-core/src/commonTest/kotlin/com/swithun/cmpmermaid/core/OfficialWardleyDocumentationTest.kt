package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialWardleyDocumentationTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * request.fontSize * 0.6f,
                height = request.text.lines().size * request.fontSize * request.lineHeight,
                lineCount = request.text.lines().size,
            )
        },
    )

    @Test
    fun rendersEveryOfficialWardleyDocumentationExample() {
        assertEquals(16, officialWardleyDocumentationCases.size)
        val failures = officialWardleyDocumentationCases.mapNotNull { fixture ->
            when (val result = engine.render(fixture.source, context)) {
                is GMResult.Err -> "${fixture.id}: ${result.error.message}"
                is GMResult.Ok -> validate(fixture, result.value)
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Wardley documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun validate(
        fixture: MermaidWardleyDocCase,
        scene: MermaidScene,
    ): String? = when {
        scene.width <= 0f -> "${fixture.id}: non-positive scene width"
        scene.height <= 0f -> "${fixture.id}: non-positive scene height"
        scene.elements.filterIsInstance<ScenePath>()
            .none { path -> path.id == "wardley-axis-x" } ->
            "${fixture.id}: no evolution axis"
        scene.elements.filterIsInstance<ScenePath>()
            .none { path -> path.id == "wardley-axis-y" } ->
            "${fixture.id}: no visibility axis"
        scene.elements.filterIsInstance<SceneShape>()
            .none { shape -> shape.id.startsWith("wardley-node-") } ->
            "${fixture.id}: no component or anchor"
        else -> null
    }
}
