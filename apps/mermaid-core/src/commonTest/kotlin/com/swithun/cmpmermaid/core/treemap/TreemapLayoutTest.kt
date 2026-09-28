package com.swithun.cmpmermaid.core.treemap

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTreemapOptions
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TreemapLayoutTest {
    @Test
    fun rendersBranchesLeavesValuesAndTitleInOfficialPaintOrder() {
        val scene = render(
            """
            ---
            title: Product mix
            ---
            treemap-beta
            "Products"
                "Hardware"
                    "Phones": 50
                    "Computers": 30
                "Services": 20
            """.trimIndent(),
        )

        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val texts = scene.elements.filterIsInstance<SceneText>()
        assertEquals(5, shapes.size)
        assertEquals("Product mix", texts.first().text)
        assertTrue(texts.any { text -> text.text == "Products" })
        assertTrue(texts.any { text -> text.text == "Hardware" })
        assertTrue(texts.any { text -> text.text == "100" && text.italic })
        assertTrue(texts.any { text -> text.text == "Phones" })
        assertTrue(texts.any { text -> text.text == "50" })
        assertEquals(8f, scene.viewportPadding)
        assertEquals(MermaidSceneViewportSizing.ResponsiveMaxWidth, scene.viewportSizing)
    }

    @Test
    fun appliesClassStylesAndValueFormattingSpecialCases() {
        val scene = render(
            """
            ---
            config:
              treemap:
                valueFormat: '${'$'},.2f'
                useMaxWidth: false
                showValues: true
                padding: 6
                nodeWidth: 80
                nodeHeight: 30
            ---
            treemap
            "Budget"
                "Operations": 1234.56:::important
                "Other": 0.35
            classDef important fill:#112233,stroke:#445566,stroke-width:5px,color:#abcdef,font-style:italic;
            """.trimIndent(),
        )

        val styledShape = scene.elements
            .filterIsInstance<SceneShape>()
            .first { shape -> shape.fill == SceneColor(0x4D112233) }
        assertEquals(SceneColor(0xFF445566), styledShape.stroke)
        assertEquals(5f, styledShape.strokeWidth)
        val styledTexts = scene.elements
            .filterIsInstance<SceneText>()
            .filter { text -> text.color == SceneColor(0xFFABCDEF) }
        assertTrue(styledTexts.isNotEmpty())
        assertTrue(styledTexts.all(SceneText::italic))
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
            text.text == "${'$'}1.2e+3"
        })
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
    }

    @Test
    fun hidesValuesAndRejectsInvalidDirectConfigurationWithoutThrowing() {
        val noValues = render(
            """
            ---
            config:
              treemap:
                showValues: false
            ---
            treemap-beta
            "Root"
                "Leaf": 25
            """.trimIndent(),
        )
        assertEquals(
            listOf("Root", "Leaf"),
            noValues.elements.filterIsInstance<SceneText>().map { text -> text.text },
        )

        val invalid = MermaidEngine().render(
            "treemap\n\"Root\"\n  \"Leaf\": 1",
            context(MermaidTreemapOptions(nodeWidth = 0f)),
        )
        assertIs<GMResult.Err<MermaidError>>(invalid)
    }

    @Test
    fun appliesFrontmatterTreemapConfigurationAndInvalidFormatFallback() {
        val scene = render(
            """
            ---
            config:
              treemap:
                diagramPadding: 24
                valueFormat: invalid
            ---
            treemap-beta
            "Root"
                "Leaf": 1234.5
            """.trimIndent(),
        )

        assertEquals(24f, scene.viewportPadding)
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
            text.text == "1,234.5"
        })
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(MermaidEngine().render(source, context())).value

    private fun context(
        treemap: MermaidTreemapOptions = MermaidTreemapOptions(),
    ): MermaidRenderContext = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * request.fontSize * 0.58f,
                height = request.fontSize * request.lineHeight,
                lineCount = 1,
            )
        },
        options = MermaidRenderOptions(treemap = treemap),
    )
}
