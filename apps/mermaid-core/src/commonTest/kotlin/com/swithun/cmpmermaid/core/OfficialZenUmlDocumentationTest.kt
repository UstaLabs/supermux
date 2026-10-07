package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialZenUmlDocumentationTest {
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
    fun rendersEveryOfficialZenUmlDocumentationExample() {
        assertEquals(16, officialZenUmlDocumentationCases.size)
        val failures = officialZenUmlDocumentationCases.mapNotNull { fixture ->
            when (val result = engine.render(fixture.source, context)) {
                is GMResult.Err -> "${fixture.id}: ${result.error.message}"
                is GMResult.Ok -> validate(fixture, result.value)
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 ZenUML documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun validate(
        fixture: MermaidZenUmlDocCase,
        scene: MermaidScene,
    ): String? = when {
        scene.width <= 0f -> "${fixture.id}: non-positive scene width"
        scene.height <= 0f -> "${fixture.id}: non-positive scene height"
        scene.elements.filterIsInstance<SceneShape>()
            .none { shape -> shape.id.startsWith("zenuml-participant-") } ->
            "${fixture.id}: no participant"
        scene.elements.filterIsInstance<ScenePath>().none { path ->
            path.id.startsWith("zenuml-message-") ||
                path.id.startsWith("zenuml-creation-") ||
                path.id.startsWith("zenuml-return-")
        } -> "${fixture.id}: no interaction"
        else -> null
    }
}
