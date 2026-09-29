package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialTreeViewDocumentationTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * request.fontSize * 0.55f,
                height = request.fontSize,
                lineCount = 1,
            )
        },
    )

    @Test
    fun rendersEveryOfficialTreeViewDocumentationExample() {
        assertEquals(13, officialTreeViewDocumentationCases.size)
        val failures = officialTreeViewDocumentationCases.mapNotNull { fixture ->
            when (val result = engine.render(fixture.source, context)) {
                is GMResult.Err -> "${fixture.id}: ${result.error.message}"
                is GMResult.Ok -> validate(fixture, result.value)
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 TreeView documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun validate(
        fixture: MermaidTreeViewDocCase,
        scene: MermaidScene,
    ): String? = when {
        scene.width <= 0f -> "${fixture.id}: non-positive scene width"
        scene.height <= 0f -> "${fixture.id}: non-positive scene height"
        scene.elements.filterIsInstance<SceneText>().none { text -> text.text == "/" } ->
            "${fixture.id}: missing synthetic root"
        scene.elements.filterIsInstance<ScenePath>()
            .none { path -> path.id.startsWith("treeview-line-") } ->
            "${fixture.id}: missing TreeView connectors"
        else -> null
    }
}
