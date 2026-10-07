package com.swithun.cmpmermaid.core

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialAgentflowDocumentationTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val charactersPerLine = (request.maxWidth / (request.fontSize * 0.55f))
                .toInt()
                .coerceAtLeast(1)
            val lines = request.text.lineSequence().sumOf { line ->
                ceil(line.length.toDouble() / charactersPerLine)
                    .toInt()
                    .coerceAtLeast(1)
            }
            TextMetrics(
                width = minOf(
                    request.maxWidth,
                    request.text.lineSequence()
                        .maxOfOrNull(String::length)
                        .orZero() * request.fontSize * 0.55f,
                ),
                height = lines * request.fontSize * request.lineHeight,
            )
        },
        // Native validates the official syntax through its translated Dagre path.
        // Mermaid's documented ELK request remains an explicit unsupported result.
        options = MermaidRenderOptions(layout = "dagre"),
    )

    @Test
    fun handlesEveryOfficialAgentflowDocumentationExample() {
        assertEquals(12, officialAgentflowDocumentationCases.size)
        val failures = officialAgentflowDocumentationCases.mapNotNull { case ->
            val result = engine.render(case.source, context)
            when (val expected = case.expectedUnsupportedFeature) {
                null -> when (result) {
                    is GMResult.Ok -> validateScene(case.id, result.value)
                    is GMResult.Err -> "${case.id}: ${result.error.message}"
                }
                else -> when (result) {
                    is GMResult.Ok ->
                        "${case.id}: expected UnsupportedFeature($expected), got success"
                    is GMResult.Err -> {
                        val error = result.error
                        if (error is MermaidError.UnsupportedFeature &&
                            error.feature == expected
                        ) {
                            null
                        } else {
                            "${case.id}: expected UnsupportedFeature($expected), got $error"
                        }
                    }
                }
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Agentflow documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    @Test
    fun keepsTheUntranslatedElkDocumentationExampleExplicit() {
        assertEquals(
            mapOf("ELK layout" to 1),
            officialAgentflowDocumentationCases
                .mapNotNull(MermaidAgentflowDocCase::expectedUnsupportedFeature)
                .groupingBy { feature -> feature }
                .eachCount(),
        )
        assertEquals(
            11,
            officialAgentflowDocumentationCases.count { case ->
                case.expectedUnsupportedFeature == null
            },
        )
    }

    private fun validateScene(
        caseId: String,
        scene: MermaidScene,
    ): String? = when {
        !scene.width.isFinite() || scene.width <= 0f ->
            "$caseId produced invalid width ${scene.width}"
        !scene.height.isFinite() || scene.height <= 0f ->
            "$caseId produced invalid height ${scene.height}"
        scene.elements.filterIsInstance<SceneShape>().isEmpty() ->
            "$caseId produced no shapes"
        scene.elements.filterIsInstance<ScenePath>().isEmpty() ->
            "$caseId produced no paths"
        scene.elements.filterIsInstance<SceneText>().isEmpty() ->
            "$caseId produced no text"
        else -> null
    }

    private fun Int?.orZero(): Int = this ?: 0
}
