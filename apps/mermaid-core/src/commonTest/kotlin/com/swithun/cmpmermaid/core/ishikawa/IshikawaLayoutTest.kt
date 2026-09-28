package com.swithun.cmpmermaid.core.ishikawa

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidIshikawaOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IshikawaLayoutTest {
    @Test
    fun rendersOfficialFishboneStructureAndQuadraticHead() {
        val scene = render(
            """
            ishikawa-beta
                Blurry Photo
                Process
                    Out of focus
                    Shutter speed too slow
                User
                    Shaky hands
                Equipment
                    LENS
                        Damaged lens
                Environment
                    Too dark
            """.trimIndent(),
        )

        val paths = scene.elements.filterIsInstance<ScenePath>()
        val head = paths.single { path -> path.id == "ishikawa-head" }
        val spine = paths.single { path -> path.id == "ishikawa-spine" }
        val branches = paths.filter { path -> path.id.startsWith("ishikawa-branch-") }
        val subBranches = paths.filter { path -> path.id.startsWith("ishikawa-sub-branch-") }

        assertTrue(head.commands.any { command -> command is ScenePathCommand.QuadraticTo })
        assertEquals(4, branches.size)
        assertEquals(6, subBranches.size)
        assertTrue((branches + subBranches).all { path ->
            path.arrowStart == SceneArrowHead.Triangle
        })
        assertEquals(spine.points.first().y, spine.points.last().y)
        assertTrue(spine.points.first().x < spine.points.last().x)
        assertEquals(4, scene.elements.filterIsInstance<SceneShape>().size)
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
            text.text == "Blurry\nPhoto"
        })
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
    }

    @Test
    fun appliesIshikawaConfigurationAndNormalizesContentPadding() {
        val scene = render(
            """
            ---
            config:
              ishikawa:
                diagramPadding: 37
                useMaxWidth: true
            ---
            ishikawa
            Effect
              Cause
            """.trimIndent(),
        )

        assertEquals(MermaidSceneViewportSizing.ResponsiveMaxWidth, scene.viewportSizing)
        assertTrue(scene.width > 74f)
        assertTrue(scene.height > 74f)
        assertEquals(
            37f,
            scene.elements.minOf { element ->
                when (element) {
                    is ScenePath -> element.points.minOf { point -> point.x }
                    is SceneShape -> element.bounds.left
                    is SceneText -> element.bounds.left
                    else -> Float.POSITIVE_INFINITY
                }
            },
            absoluteTolerance = 0.001f,
        )
    }

    @Test
    fun rendersRootOnlyAndPreservesSanitizedEntityWithActualBreakText() {
        val scene = render(
            """
            ishikawa
            AT&amp;T<br/>Failure
            """.trimIndent(),
        )

        val spine = scene.elements.filterIsInstance<ScenePath>()
            .single { path -> path.id == "ishikawa-spine" }
        assertEquals(spine.points.first(), spine.points.last())
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
            text.text == "AT&amp;T\nFailure"
        })
        assertTrue(scene.elements.none { element ->
            element is ScenePath && element.id.startsWith("ishikawa-branch-")
        })
    }

    @Test
    fun rejectsInvalidDirectConfigurationWithoutThrowing() {
        val result = MermaidEngine().render(
            "ishikawa\nEffect",
            context(
                MermaidIshikawaOptions(
                    diagramPadding = -1f,
                    useMaxWidth = false,
                ),
            ),
        )

        assertIs<GMResult.Err<MermaidError>>(result)
    }

    @Test
    fun usesPortableComposeTextMeasurementConstraints() {
        val measuredWidths = mutableListOf<Float>()
        val result = MermaidEngine().render(
            "ishikawa\nEffect\n  Cause",
            context().copy(
                textMetrics = TextMetricProvider { request ->
                    measuredWidths += request.maxWidth
                    TextMetrics(
                        width = request.text.length * request.fontSize * 0.6f,
                        height = request.fontSize,
                        lineCount = 1,
                    )
                },
            ),
        )

        assertIs<GMResult.Ok<MermaidScene>>(result)
        assertTrue(measuredWidths.isNotEmpty())
        assertTrue(measuredWidths.all { width -> width == 100_000f })
    }

    @Test
    fun appliesConfiguredFontSizeToCauseLabelsAndUpstreamHeadSpacing() {
        val fontSizes = mutableListOf<Float>()
        val result = MermaidEngine().render(
            "ishikawa\nEffect\n  Cause",
            context().copy(
                textMetrics = TextMetricProvider { request ->
                    fontSizes += request.fontSize
                    TextMetrics(
                        width = request.text.length * request.fontSize * 0.6f,
                        height = request.fontSize,
                        lineCount = 1,
                    )
                },
                options = MermaidRenderOptions(fontSize = 22f),
            ),
        )

        val scene = assertIs<GMResult.Ok<MermaidScene>>(result).value
        assertEquals(listOf(14f, 22f), fontSizes)
        val texts = scene.elements.filterIsInstance<SceneText>()
        val head = texts.single { text -> text.weight == SceneTextWeight.Bold }
        assertEquals(14f, head.fontSize)
        assertEquals(22f * 1.05f / 14f, head.lineHeight, absoluteTolerance = 0.001f)
        assertEquals(22f, texts.single { text -> text.text == "Cause" }.fontSize)
    }

    @Test
    fun rendersDeepAlternatingHierarchyDeterministically() {
        val source = """
            ishikawa
            Effect
              Level 1
                Level 2
                  Level 3
                    Level 4
                      Level 5
        """.trimIndent()

        val first = render(source)
        val second = render(source)

        assertEquals(first, second)
        assertEquals(
            4,
            first.elements.filterIsInstance<ScenePath>().count { path ->
                path.id.startsWith("ishikawa-sub-branch-")
            },
        )
        assertTrue(first.elements.filterIsInstance<ScenePath>().flatMap { path ->
            path.points
        }.all { point -> point.x.isFinite() && point.y.isFinite() })
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            MermaidEngine().render(source, context()),
        ).value

    private fun context(
        ishikawa: MermaidIshikawaOptions = MermaidIshikawaOptions(),
    ): MermaidRenderContext = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lines = request.text.split('\n')
            TextMetrics(
                width = lines.maxOfOrNull { line -> line.length }?.times(request.fontSize * 0.6f)
                    ?: 0f,
                height = request.fontSize,
                lineCount = 1,
            )
        },
        theme = MermaidTheme.MermaidDefault,
        options = MermaidRenderOptions(ishikawa = ishikawa),
    )
}
