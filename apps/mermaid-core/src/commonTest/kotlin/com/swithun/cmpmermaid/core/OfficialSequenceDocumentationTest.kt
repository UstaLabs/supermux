package com.swithun.cmpmermaid.core

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialSequenceDocumentationTest {
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val charactersPerLine = (request.maxWidth / 8f).toInt().coerceAtLeast(1)
            val lineCount = ceil(request.text.length.toDouble() / charactersPerLine)
                .toInt()
                .coerceAtLeast(1)
            TextMetrics(
                width = minOf(request.maxWidth, request.text.length * 8f),
                height = lineCount * request.fontSize * request.lineHeight,
            )
        },
        options = MermaidRenderOptions(look = "neo"),
    )
    private val engine = MermaidEngine()
    private val expectedUnsupportedFeatures = mapOf(
        "037_actor_menus" to "sequence participant link menus",
        "038_advanced_menu_syntax" to "sequence participant link menus",
    )

    @Test
    fun handlesEveryMermaid12SequenceDocumentationExample() {
        assertEquals(38, officialSequenceDocumentationCases.size)

        val failures = officialSequenceDocumentationCases.mapNotNull(::renderFailure)

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Sequence documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun renderFailure(case: MermaidSequenceDocCase): String? {
        val expectedFeature = expectedUnsupportedFeatures[case.id]
        return when (val result = engine.render(case.source, context)) {
            is GMResult.Ok -> expectedFeature?.let {
                "${case.id}: expected explicit unsupported feature '$it'"
            } ?: validateScene(case.id, result.value)
            is GMResult.Err -> {
                val error = result.error
                if (error is MermaidError.UnsupportedFeature && error.feature == expectedFeature) {
                    null
                } else {
                    "${case.id}: ${error.message}"
                }
            }
        }
    }

    private fun validateScene(caseId: String, scene: MermaidScene): String? {
        if (!scene.width.isFinite() || !scene.height.isFinite() ||
            scene.width <= 0f || scene.height <= 0f
        ) {
            return "$caseId: invalid scene size ${scene.width} x ${scene.height}"
        }
        if (scene.elements.isEmpty()) {
            return "$caseId: empty scene"
        }
        scene.elements.forEach { element ->
            val bounds = when (element) {
                is SceneAsset -> element.bounds
                is SceneShape -> element.bounds
                is SceneText -> element.bounds
                is ScenePath -> null
            }
            if (bounds != null &&
                (!bounds.left.isFinite() || !bounds.top.isFinite() ||
                    !bounds.width.isFinite() || !bounds.height.isFinite() ||
                    bounds.width < 0f || bounds.height < 0f)
            ) {
                return "$caseId: invalid ${element::class.simpleName} bounds $bounds"
            }
            if (element is ScenePath &&
                (element.points.size < 2 ||
                    element.points.any { !it.x.isFinite() || !it.y.isFinite() })
            ) {
                return "$caseId: invalid path ${element.id}"
            }
        }
        return null
    }
}
