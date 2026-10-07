package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialArchitectureDocumentationTest {
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
    fun rendersEveryOfficialArchitectureDocumentationExample() {
        assertEquals(6, officialArchitectureDocumentationCases.size)
        val failures = officialArchitectureDocumentationCases.mapNotNull { fixture ->
            when (val result = engine.render(fixture.source, context)) {
                is GMResult.Err -> "${fixture.id}: ${result.error.message}"
                is GMResult.Ok -> validate(fixture, result.value)
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Architecture documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun validate(
        fixture: MermaidArchitectureDocCase,
        scene: MermaidScene,
    ): String? = when {
        scene.width <= 0f -> "${fixture.id}: non-positive scene width"
        scene.height <= 0f -> "${fixture.id}: non-positive scene height"
        scene.elements.filterIsInstance<SceneShape>().isEmpty() &&
            scene.elements.filterIsInstance<SceneAsset>().isEmpty() ->
            "${fixture.id}: missing architecture services or groups"
        scene.elements.filterIsInstance<ScenePath>()
            .none { it.id.startsWith("architecture-edge-") } ->
            "${fixture.id}: missing architecture edges"
        else -> null
    }

    private fun Int?.orZero(): Int = this ?: 0
}
