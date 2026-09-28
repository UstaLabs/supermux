package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WardleyConfigTest {
    @Test
    fun discardsWardleySourceConfigLikeMermaidDirectiveSanitizer() {
        val preprocessed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(
            MermaidPreprocessor.preprocess(
                """
                ---
                config:
                  wardley-beta:
                    width: 1000
                    height: 700
                    padding: 36
                    nodeRadius: 8
                    nodeLabelOffset: 11
                    axisFontSize: 15
                    labelFontSize: 12
                    showGrid: true
                    useMaxWidth: false
                ---
                wardley-beta
                component API [0.5, 0.5]
                """.trimIndent(),
            ),
        ).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            preprocessed.config.applyTo(MermaidRenderOptions()),
        ).value.wardley

        assertEquals(MermaidWardleyOptions(), options)
    }

    @Test
    fun parsesNestedWardleyThemeVariablesAndRejectsInvalidCallerOptions() {
        val themed = MermaidEngine().render(
            source = """
                ---
                config:
                  themeVariables:
                    wardley:
                      backgroundColor: "#102030"
                      axisColor: "#abcdef"
                ---
                wardley-beta
                component API [0.5, 0.5]
            """.trimIndent(),
            context = testContext(),
        )
        val scene = assertIs<GMResult.Ok<MermaidScene>>(themed).value
        assertEquals(SceneColor(0xFF102030), scene.background)
        val axis = scene.elements.filterIsInstance<ScenePath>()
            .single { path -> path.id == "wardley-axis-x" }
        assertEquals(SceneColor(0xFFABCDEF), axis.color)

        val invalid = MermaidEngine().render(
            source = "wardley-beta\ncomponent API [0.5, 0.5]",
            context = testContext(
                MermaidRenderOptions(
                    wardley = MermaidWardleyOptions(nodeRadius = 0f),
                ),
            ),
        )
        assertIs<GMResult.Err<MermaidError>>(invalid)
    }

    private fun testContext(
        options: MermaidRenderOptions = MermaidRenderOptions(),
    ): MermaidRenderContext = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * 7f,
                height = request.fontSize * request.lineHeight,
            )
        },
        options = options,
    )
}
