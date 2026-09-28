package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfficialKanbanDocumentationTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = minOf(request.maxWidth, request.text.length * request.fontSize * 0.55f),
                height = request.fontSize * request.lineHeight,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersEveryOfficialKanbanDocumentationExample() {
        assertEquals(3, officialKanbanDocumentationCases.size)
        officialKanbanDocumentationCases.forEach { fixture ->
            val result = engine.render(fixture.source, context)
            val scene = assertIs<GMResult.Ok<MermaidScene>>(
                result,
                "Expected official Kanban fixture ${fixture.id} to render: $result",
            ).value

            assertTrue(scene.width > 0f, fixture.id)
            assertTrue(scene.height > 0f, fixture.id)
            assertTrue(scene.elements.filterIsInstance<SceneShape>().isNotEmpty(), fixture.id)
            assertTrue(scene.elements.filterIsInstance<SceneText>().isNotEmpty(), fixture.id)
        }
    }
}
