package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfficialCynefinDocumentationTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = minOf(
                    request.maxWidth,
                    request.text.length * request.fontSize * 0.55f,
                ),
                height = request.fontSize,
                lineCount = 1,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersEveryOfficialCynefinDocumentationExample() {
        assertEquals(3, officialCynefinDocumentationCases.size)
        officialCynefinDocumentationCases.forEach { fixture ->
            val result = engine.render(fixture.source, context)
            val scene = assertIs<GMResult.Ok<MermaidScene>>(
                result,
                "Expected official Cynefin fixture ${fixture.id} to render: $result",
            ).value

            assertTrue(scene.width > 0f, fixture.id)
            assertTrue(scene.height > 0f, fixture.id)
            assertTrue(scene.elements.filterIsInstance<SceneShape>().isNotEmpty(), fixture.id)
            assertTrue(scene.elements.filterIsInstance<ScenePath>().isNotEmpty(), fixture.id)
            assertTrue(scene.elements.filterIsInstance<SceneText>().isNotEmpty(), fixture.id)
        }
    }
}
