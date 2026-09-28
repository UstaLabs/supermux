package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfficialIshikawaDocumentationTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lines = request.text.split('\n')
            TextMetrics(
                width = lines.maxOfOrNull { line -> line.length }?.times(request.fontSize * 0.55f)
                    ?: 0f,
                height = request.fontSize,
                lineCount = 1,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersEveryOfficialIshikawaDocumentationExample() {
        assertEquals(1, officialIshikawaDocumentationCases.size)
        officialIshikawaDocumentationCases.forEach { fixture ->
            val result = engine.render(fixture.source, context)
            val scene = assertIs<GMResult.Ok<MermaidScene>>(
                result,
                "Expected official Ishikawa fixture ${fixture.id} to render: $result",
            ).value

            assertTrue(scene.width > 0f, fixture.id)
            assertTrue(scene.height > 0f, fixture.id)
            assertTrue(scene.elements.filterIsInstance<ScenePath>().isNotEmpty(), fixture.id)
            assertTrue(scene.elements.filterIsInstance<SceneText>().isNotEmpty(), fixture.id)
        }
    }
}
