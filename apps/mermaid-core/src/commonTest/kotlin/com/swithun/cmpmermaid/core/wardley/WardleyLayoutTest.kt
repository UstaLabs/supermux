package com.swithun.cmpmermaid.core.wardley

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidWardleyOptions
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleyDocument
import com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleyParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WardleyLayoutTest {
    @Test
    fun translatesFixedCoordinatesAxesLinksTrendsAndMetadata() {
        val scene = render(
            """
                wardley-beta
                title Tea Shop Value Chain
                accTitle: Accessible Wardley map
                accDescr: Tea shop dependencies
                anchor Business [0.95, 0.63]
                component Tea [0.63, 0.81]
                component Kettle [0.43, 0.35]
                Business +<> Tea; demand
                Tea -.-> Kettle
                evolve Kettle 0.62
                note "Strategic note" [0.30, 0.49]
            """.trimIndent(),
        )

        assertEquals(900f, scene.width)
        assertEquals(600f, scene.height)
        assertEquals(MermaidSceneViewportSizing.ResponsiveMaxWidth, scene.viewportSizing)
        assertEquals("Tea Shop Value Chain", scene.title)
        assertEquals("Accessible Wardley map", scene.accessibilityTitle)
        assertEquals("Tea shop dependencies", scene.accessibilityDescription)

        val texts = scene.elements.filterIsInstance<SceneText>()
        assertTrue(texts.any { text -> text.text == "Evolution" })
        assertTrue(texts.any { text -> text.text == "Visibility" })
        assertTrue(texts.any { text -> text.text == "Tea" })
        assertTrue(texts.any { text -> text.text == "Strategic note" })
        assertTrue(
            listOf("Genesis", "Custom Built", "Product", "Commodity").all { stage ->
                texts.any { text -> text.text == stage }
            },
        )

        val links = scene.elements.filterIsInstance<ScenePath>()
        val bidirectional = links.single { path -> path.id == "wardley-link-0" }
        assertEquals(SceneArrowHead.Triangle, bidirectional.arrowStart)
        assertEquals(SceneArrowHead.Triangle, bidirectional.arrowEnd)
        val dashed = links.single { path -> path.id == "wardley-link-1" }
        assertEquals(SceneStrokePattern.Dashed, dashed.strokePattern)
        assertEquals(listOf(6f, 6f), dashed.dashIntervals)
        val trend = links.single { path -> path.id == "wardley-trend-0" }
        assertEquals(SceneArrowHead.Triangle, trend.arrowEnd)
        assertEquals(SceneStrokePattern.Dashed, trend.strokePattern)
    }

    @Test
    fun rendersPipelinesStrategiesAnnotationsForcesAndGrid() {
        val scene = render(
            """
                wardley-beta
                evolution Genesis@0.2 -> Custom@0.4 -> Product@0.75 -> Commodity@1.0
                component Database [0.40, 0.60]
                pipeline Database {
                  component File System [0.25]
                  component SQL DB [0.50]
                  component Cloud DB [0.85]
                }
                component Build [0.7, 0.2] (build)
                component Buy [0.6, 0.3] (buy)
                component Outsource [0.5, 0.4] (outsource)
                component Market [0.4, 0.5] (market) (inertia)
                annotations [0.1, 0.9]
                annotation 1,[0.6, 0.65] "Critical"
                accelerator "Adoption" [0.2, 0.8]
                deaccelerator "Legacy" [0.3, 0.7]
            """.trimIndent(),
            options = MermaidWardleyOptions(showGrid = true),
        )

        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val pipeline = shapes.single { shape -> shape.id == "wardley-pipeline-box-0" }
        assertEquals(SceneShapeKind.RoundedRectangle, pipeline.kind)
        assertTrue(pipeline.bounds.width > 300f)
        assertTrue(shapes.any { shape -> shape.id.startsWith("wardley-build-overlay-") })
        assertTrue(shapes.any { shape -> shape.id.startsWith("wardley-buy-overlay-") })
        assertTrue(shapes.any { shape -> shape.id.startsWith("wardley-outsource-overlay-") })
        assertTrue(shapes.any { shape -> shape.id.startsWith("wardley-market-overlay-") })
        assertTrue(shapes.any { shape -> shape.id == "wardley-annotations-box" })

        val paths = scene.elements.filterIsInstance<ScenePath>()
        assertEquals(6, paths.count { path -> path.id.startsWith("wardley-grid-") })
        assertEquals(2, paths.count { path -> path.id.startsWith("wardley-pipeline-link-") })
        assertEquals(3, paths.count { path -> path.id.startsWith("wardley-market-line-") })
        assertTrue(paths.any { path -> path.id.startsWith("wardley-accelerator-") })
        assertTrue(paths.any { path -> path.id.startsWith("wardley-deaccelerator-") })
    }

    @Test
    fun honorsDiagramSizeAndCallerViewportConfiguration() {
        val scene = render(
            """
                wardley-beta
                size [800, 500]
                component API [0.5, 0.5]
            """.trimIndent(),
            options = MermaidWardleyOptions(
                width = 1200f,
                height = 700f,
                padding = 50f,
                useMaxWidth = false,
            ),
        )

        assertEquals(800f, scene.width)
        assertEquals(500f, scene.height)
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
        val node = scene.elements.filterIsInstance<SceneShape>()
            .single { shape -> shape.id == "wardley-node-0" }
        assertEquals(SceneRect(394f, 244f, 406f, 256f), node.bounds)
    }

    @Test
    fun rejectsInvalidCallerConfigurationAndThemeVariables() {
        val document = parse(
            "wardley-beta\ncomponent API [0.5, 0.5]",
            MermaidRenderOptions(),
        )
        val invalidSize = WardleyLayout().layout(
            document,
            context(
                MermaidRenderOptions(
                    wardley = MermaidWardleyOptions(width = Float.NaN),
                ),
            ),
        )
        assertIs<GMResult.Err<*>>(invalidSize)

        val invalidTheme = WardleyLayout().layout(
            document,
            context(
                MermaidRenderOptions(
                    themeVariables = mapOf("wardley.axisColor" to "not-a-color"),
                ),
            ),
        )
        assertIs<GMResult.Err<*>>(invalidTheme)
    }

    private fun render(
        source: String,
        options: MermaidWardleyOptions = MermaidWardleyOptions(),
    ): MermaidScene {
        val renderOptions = MermaidRenderOptions(wardley = options)
        return assertIs<GMResult.Ok<MermaidScene>>(
            WardleyLayout().layout(
                document = parse(source, renderOptions),
                context = context(renderOptions),
            ),
        ).value
    }

    private fun parse(
        source: String,
        options: MermaidRenderOptions,
    ): WardleyDocument = assertIs<GMResult.Ok<WardleyDocument>>(
        WardleyParser(
            options = options,
            diagramTitle = null,
            lineOffset = 0,
        ).parse(source),
    ).value

    private fun context(options: MermaidRenderOptions): MermaidRenderContext =
        MermaidRenderContext(
            textMetrics = TextMetricProvider { request ->
                TextMetrics(
                    width = request.text.length * 7f,
                    height = request.fontSize * request.lineHeight,
                )
            },
            options = options,
        )
}
