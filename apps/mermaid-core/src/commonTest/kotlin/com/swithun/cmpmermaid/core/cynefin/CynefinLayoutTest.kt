package com.swithun.cmpmermaid.core.cynefin

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidCynefinOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.cynefin.upstream.mermaid.CynefinDb
import com.swithun.cmpmermaid.core.cynefin.upstream.mermaid.CynefinParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CynefinLayoutTest {
    @Test
    fun translatesTheUpstreamFixedDomainSceneAndDrawOrder() {
        val scene = render(
            """
                cynefin-beta
                  title Team Practices
                  accTitle: Accessible Cynefin
                  accDescr: Five decision domains
                  complex
                    "Probe"
                  complicated
                    "Analyse"
                  chaotic
                    "Act"
                  clear
                    "Categorise"
                  confusion
                    "Unknown A"
                    "Unknown B"
                    "Unknown C"
                    "Unknown D"
                  complex --> complicated : "Pattern"
            """.trimIndent(),
        )

        assertEquals(904f, scene.width)
        assertEquals(704f, scene.height)
        assertEquals(MermaidSceneViewportSizing.ResponsiveMaxWidth, scene.viewportSizing)
        assertEquals("Team Practices", scene.title)
        assertEquals("Accessible Cynefin", scene.accessibilityTitle)
        assertEquals("Five decision domains", scene.accessibilityDescription)

        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val backgrounds = shapes.filter { shape ->
            shape.id.startsWith("cynefin-background-")
        }
        assertEquals(4, backgrounds.size)
        assertEquals(SceneRect(52f, 52f, 452f, 352f), backgrounds.first().bounds)
        assertEquals(SceneColor(0x66E8F5E9), backgrounds.first().fill)

        val confusion = shapes.single { shape -> shape.id == "cynefin-confusion" }
        assertEquals(SceneShapeKind.Ellipse, confusion.kind)
        assertEquals(SceneRect(332f, 262f, 572f, 442f), confusion.bounds)
        assertEquals(SceneStrokePattern.Dashed, confusion.strokePattern)
        assertEquals(listOf(4f, 2f), confusion.dashIntervals)

        val paths = scene.elements.filterIsInstance<ScenePath>()
        val vertical = paths.single { path -> path.id == "cynefin-boundary-vertical" }
        assertEquals(7, vertical.commands.count { command ->
            command is ScenePathCommand.CubicTo
        })
        assertEquals(SceneStrokePattern.Dashed, vertical.strokePattern)
        assertEquals(listOf(6f, 3f), vertical.dashIntervals)
        assertTrue(vertical.points.all { point -> point.y in 52f..652f })

        val cliff = paths.single { path -> path.id == "cynefin-cliff" }
        assertEquals(2, cliff.commands.count { command ->
            command is ScenePathCommand.CubicTo
        })
        assertEquals(4f, cliff.strokeWidth)

        val transition = paths.single { path -> path.id == "cynefin-transition-0" }
        assertEquals(SceneArrowHead.Triangle, transition.arrowEnd)
        assertEquals(252f, transition.points[0].x)
        assertEquals(202f, transition.points[0].y)
        assertEquals(452f, transition.points[1].x)
        assertEquals(262f, transition.points[1].y)
        assertEquals(652f, transition.points[2].x)
        assertEquals(202f, transition.points[2].y)

        val texts = scene.elements.filterIsInstance<SceneText>().map(SceneText::text)
        assertTrue("Probe \u2192 Sense \u2192 Respond" in texts)
        assertTrue("Disorder" in texts)
        assertTrue("Pattern" in texts)
        assertTrue("Team Practices" in texts)
        assertTrue("Unknown A" in texts)
        assertTrue("Unknown B" in texts)
        assertTrue("Unknown C" in texts)
        assertTrue("Unknown D" !in texts)
        assertTrue("+1 more" in texts)
        assertEquals(3, shapes.count { shape ->
            shape.id.startsWith("cynefin-item-confusion-") &&
                shape.id.endsWith("-badge") &&
                shape.id != "cynefin-item-confusion-overflow-badge"
        })
        assertTrue(
            shapes.single { shape ->
                shape.id == "cynefin-item-confusion-overflow-badge"
            }.strokePattern == SceneStrokePattern.Dashed,
        )
        assertTrue(
            scene.elements.indexOf(transition) >
                scene.elements.indexOf(
                    shapes.single { shape -> shape.id == "cynefin-item-complex-0-badge" },
                ),
        )
    }

    @Test
    fun honorsDescriptionAndViewportConfiguration() {
        val scene = render(
            source = """
                cynefin-beta
                  complex
                    "Probe"
            """.trimIndent(),
            cynefin = MermaidCynefinOptions(
                width = 400f,
                height = 300f,
                padding = 20f,
                showDomainDescriptions = false,
                boundaryAmplitude = 0f,
                seed = 42f,
                useMaxWidth = false,
            ),
        )

        assertEquals(464f, scene.width)
        assertEquals(364f, scene.height)
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
        val texts = scene.elements.filterIsInstance<SceneText>()
        assertTrue(texts.none { text -> text.text == "Emergent Practices" })
        val complexLabel = texts.single { text -> text.text == "Complex" }
        assertEquals(132f, complexLabel.bounds.center.x)
        assertEquals(107f, complexLabel.bounds.center.y)
        val vertical = scene.elements.filterIsInstance<ScenePath>()
            .single { path -> path.id == "cynefin-boundary-vertical" }
        assertTrue(vertical.points.all { point -> point.x == 232f })
    }

    @Test
    fun keepsTitleInsideCanvasViewportWhenPaddingIsSmallerThanTextHeight() {
        val scene = render(
            source = """
                cynefin-beta
                  title Decision workshop
                  complex
            """.trimIndent(),
            cynefin = MermaidCynefinOptions(
                width = 400f,
                height = 300f,
                padding = 0f,
                seed = 42f,
            ),
        )

        val title = scene.elements.filterIsInstance<SceneText>()
            .single { text -> text.text == "Decision workshop" }
        assertEquals(12f, title.bounds.top, 0.001f)
        assertTrue(title.bounds.bottom <= scene.height - 12f)
    }

    @Test
    fun expandsTheReferenceViewportForLongIntrinsicItemLabels() {
        val label = "End To End Production Compatibility Verification Evidence Label 251"
        val scene = render(
            source = """
                cynefin-beta
                  title Decision workshop
                  complex
                  complicated
                  chaotic
                  clear
                    "$label"
            """.trimIndent(),
            cynefin = MermaidCynefinOptions(
                width = 640f,
                height = 480f,
                padding = 18f,
                showDomainDescriptions = false,
                boundaryAmplitude = 0f,
                seed = 101f,
                useMaxWidth = false,
            ),
        )

        val itemText = scene.elements.filterIsInstance<SceneText>()
            .single { text -> text.text == label }
        assertTrue(scene.width > 700f)
        assertTrue(itemText.bounds.right <= scene.width - 12f)
    }

    @Test
    fun rejectsInvalidCallerProvidedConfiguration() {
        val parsed = parse(
            source = "cynefin-beta\ncomplex",
            options = MermaidRenderOptions(
                cynefin = MermaidCynefinOptions(width = Float.NaN),
            ),
        )
        val result = CynefinLayout().layout(
            db = parsed,
            context = context(
                MermaidCynefinOptions(width = Float.NaN),
            ),
        )

        assertIs<GMResult.Err<*>>(result)
    }

    private fun render(
        source: String,
        cynefin: MermaidCynefinOptions = MermaidCynefinOptions(seed = 42f),
    ): MermaidScene {
        val options = MermaidRenderOptions(cynefin = cynefin)
        return assertIs<GMResult.Ok<MermaidScene>>(
            CynefinLayout().layout(
                db = parse(source, options),
                context = context(cynefin),
            ),
        ).value
    }

    private fun parse(
        source: String,
        options: MermaidRenderOptions,
    ): CynefinDb = assertIs<GMResult.Ok<CynefinDb>>(
        CynefinParser(
            options = options,
            diagramTitle = null,
            lineOffset = 0,
        ).parse(source),
    ).value

    private fun context(cynefin: MermaidCynefinOptions): MermaidRenderContext =
        MermaidRenderContext(
            textMetrics = TextMetricProvider { request ->
                TextMetrics(
                    width = request.text.length * 7f,
                    height = request.fontSize * request.lineHeight,
                )
            },
            options = MermaidRenderOptions(cynefin = cynefin),
        )
}
