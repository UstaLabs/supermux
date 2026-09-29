package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfficialEventModelingDocumentationTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lines = request.text.split('\n')
            TextMetrics(
                width = minOf(
                    request.maxWidth,
                    lines.maxOfOrNull { line ->
                        line.length * request.fontSize * 0.55f
                    } ?: 0f,
                ),
                height = lines.size * request.fontSize * request.lineHeight,
                lineCount = lines.size,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersEveryOfficialEventModelingDocumentationExample() {
        assertEquals(12, officialEventModelingDocumentationCases.size)
        officialEventModelingDocumentationCases.forEach { fixture ->
            val result = engine.render(fixture.source, context)
            val scene = assertIs<GMResult.Ok<MermaidScene>>(
                result,
                "Expected official Event Modeling fixture ${fixture.id} to render: $result",
            ).value

            assertTrue(scene.width > 0f, fixture.id)
            assertTrue(scene.height > 0f, fixture.id)
            assertTrue(scene.elements.filterIsInstance<SceneShape>().isNotEmpty(), fixture.id)
            assertTrue(scene.elements.filterIsInstance<SceneText>().isNotEmpty(), fixture.id)
        }
    }
}
