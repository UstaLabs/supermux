package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UsecaseConfigTest {
    @Test
    fun appliesEveryUsecaseConfigurationField() {
        val preprocessed = assertIs<GMResult.Ok<MermaidPreprocessResult>>(
            MermaidPreprocessor.preprocess(
                """
                ---
                config:
                  usecase:
                    useMaxWidth: false
                    theme: neutral
                    look: classic
                    wrappingWidth: 180
                    minNodeWidth: 140
                    actorFontSize: 16
                    actorFontFamily: Arial
                    actorFontWeight: bold
                    usecaseFontSize: 13
                    usecaseFontFamily: Georgia
                    usecaseFontWeight: 500
                    nodeSpacing: 60
                    rankSpacing: 70
                    diagramPadding: 24
                    colorScheme: rotate
                ---
                usecase-beta
                actor Customer
                """.trimIndent(),
            ),
        ).value
        val options = assertIs<GMResult.Ok<MermaidRenderOptions>>(
            preprocessed.config.applyTo(MermaidRenderOptions()),
        ).value.usecase

        assertEquals(false, options.useMaxWidth)
        assertEquals("neutral", options.theme)
        assertEquals("classic", options.look)
        assertEquals(180f, options.wrappingWidth)
        assertEquals(140f, options.minNodeWidth)
        assertEquals(16f, options.actorFontSize)
        assertEquals("Arial", options.actorFontFamily)
        assertEquals("bold", options.actorFontWeight)
        assertEquals(13f, options.usecaseFontSize)
        assertEquals("Georgia", options.usecaseFontFamily)
        assertEquals("500", options.usecaseFontWeight)
        assertEquals(60f, options.nodeSpacing)
        assertEquals(70f, options.rankSpacing)
        assertEquals(24f, options.diagramPadding)
        assertEquals("rotate", options.colorScheme)
    }

    @Test
    fun rejectsInvalidUsecaseFontWeight() {
        val result = MermaidPreprocessor.preprocess(
            """
            ---
            config:
              usecase:
                actorFontWeight: heavy
            ---
            usecase-beta
            actor Customer
            """.trimIndent(),
        )

        assertIs<GMResult.Err<MermaidError>>(result)
    }

    @Test
    fun matchesUpstreamParserTitleReset() {
        val title = "Title cleared by the Use Case parser"
        val result = MermaidEngine().render(
            source = """
                ---
                title: $title
                ---
                usecase-beta
                actor Customer
            """.trimIndent(),
            context = MermaidRenderContext(
                textMetrics = TextMetricProvider { request ->
                    TextMetrics(
                        width = request.text.length * request.fontSize * 0.55f,
                        height = request.fontSize * request.lineHeight,
                    )
                },
            ),
        )
        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value

        assertEquals(null, scene.title)
        assertTrue(scene.elements.filterIsInstance<SceneText>().none { it.text == title })
    }
}
