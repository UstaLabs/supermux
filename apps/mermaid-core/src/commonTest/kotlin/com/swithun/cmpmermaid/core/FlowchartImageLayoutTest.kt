package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FlowchartImageLayoutTest {
    private val engine = MermaidEngine()
    private val source = """
        flowchart LR
          A@{ img: "https://example.com/image.png", h: 60, constraint: "on" }
    """.trimIndent()

    @Test
    fun usesNaturalAspectRatioForConstrainedImageNode() {
        val result = engine.render(
            source = source,
            context = MermaidRenderContext(
                textMetrics = TextMetricProvider { TextMetrics(8f, 18f) },
                options = MermaidRenderOptions(layout = "dagre"),
                assetMetrics = mapOf(
                    "https://example.com/image.png" to SceneSize(200f, 100f),
                ),
            ),
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val image = scene.elements.filterIsInstance<SceneAsset>().single()
        assertEquals(120f, image.bounds.width)
        assertEquals(60f, image.bounds.height)
    }

    @Test
    fun usesFillColorAsIconFrameStrokeLikeMermaidIconSquare() {
        val result = engine.render(
            source = """
                flowchart LR
                  A@{ icon: "fa:user", form: "square", label: "User", h: 60 }
            """.trimIndent(),
            context = MermaidRenderContext(
                textMetrics = TextMetricProvider { TextMetrics(32f, 18f) },
                options = MermaidRenderOptions(layout = "dagre"),
            ),
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val shape = scene.elements.filterIsInstance<SceneShape>().single { it.id == "A" }
        assertEquals(shape.fill, shape.stroke)
        assertNull(shape.shadow)
    }

    @Test
    fun addsMermaidLabelBackgroundForAssetNodes() {
        val result = engine.render(
            source = """
                flowchart LR
                  A@{ img: "https://example.com/image.png", label: "Image", h: 60 }
            """.trimIndent(),
            context = MermaidRenderContext(
                textMetrics = TextMetricProvider { TextMetrics(40f, 18f) },
                options = MermaidRenderOptions(layout = "dagre"),
            ),
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
        val background = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id == "A_asset_label_background" }
        assertEquals(SceneColor(0x80CCCCCC), background.fill)
        assertEquals(SceneColor(0x00000000), background.stroke)
        assertEquals(0f, background.strokeWidth)
    }
}
