package com.swithun.cmpmermaid.core.timeline

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineLayoutTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * 8f,
                height = 18f,
            )
        },
        theme = MermaidTheme.MermaidDefault,
        options = MermaidRenderOptions(look = "classic"),
    )

    @Test
    fun laysOutLrSectionsTasksEventsAndHorizontalAxis() {
        val scene = render(
            """
            timeline
              title Product history
              section Foundation
                2024 : First event : Second event
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val section = shapes.getValue("timeline-node-0")
        val task = shapes.getValue("timeline-node-1")
        val firstEvent = shapes.getValue("timeline-node-2")
        val secondEvent = shapes.getValue("timeline-node-3")
        val axis = scene.elements.filterIsInstance<ScenePath>()
            .single { path -> path.id == "timeline-axis" }
        val connector = scene.elements.filterIsInstance<ScenePath>()
            .single { path -> path.id == "timeline-task-0-connector" }

        assertEquals(190f, section.bounds.width)
        assertEquals(section.bounds.left, task.bounds.left)
        assertEquals(200f, firstEvent.bounds.top - task.bounds.top, 0.001f)
        assertEquals(10f, secondEvent.bounds.top - firstEvent.bounds.bottom, 0.001f)
        assertEquals(task.bounds.left + 95f, connector.points.first().x, 0.001f)
        assertEquals(SceneStrokePattern.Dashed, connector.strokePattern)
        assertEquals(SceneArrowHead.Triangle, connector.arrowEnd)
        assertEquals(axis.points.first().y, axis.points.last().y)
        assertTrue(axis.points.last().x > axis.points.first().x)
        assertTrue(scene.elements.filterIsInstance<SceneText>().any {
            text -> text.text == "Product history" && text.weight == SceneTextWeight.Bold
        })
        assertEquals(4, scene.elements.filterIsInstance<ScenePath>().count {
            path -> path.id.endsWith("-bottom")
        })
    }

    @Test
    fun laysOutTdTasksLeftAndEventsRightOfVerticalAxis() {
        val scene = render(
            """
            timeline TD
              section Release train
                2025 : Alpha : Beta
                2026 : Stable
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val section = shapes.getValue("timeline-node-0")
        val firstTask = shapes.getValue("timeline-node-1")
        val firstEvent = shapes.getValue("timeline-node-2")
        val secondEvent = shapes.getValue("timeline-node-3")
        val secondTask = shapes.getValue("timeline-node-4")
        val axis = scene.elements.filterIsInstance<ScenePath>()
            .single { path -> path.id == "timeline-axis" }
        val connector = scene.elements.filterIsInstance<ScenePath>()
            .single { path -> path.id == "timeline-task-0-event-0-connector" }
        val axisX = axis.points.first().x

        assertEquals(590f, section.bounds.width)
        assertEquals(section.bounds.left, firstTask.bounds.left)
        assertEquals(32.8f, firstTask.bounds.height, 0.001f)
        assertEquals(20f, axisX - firstTask.bounds.right, 0.001f)
        assertEquals(50f, firstEvent.bounds.left - axisX, 0.001f)
        assertEquals(10f, secondEvent.bounds.top - firstEvent.bounds.bottom, 0.001f)
        assertTrue(secondTask.bounds.top > firstTask.bounds.top)
        assertEquals(axis.points.first().x, axis.points.last().x)
        assertTrue(axis.points.last().y > axis.points.first().y)
        assertEquals(axisX, connector.points.first().x)
        assertEquals(firstEvent.bounds.left, connector.points.last().x)
        assertEquals(0, axis.zIndex)
    }

    @Test
    fun keepsLrVirtualTaskBaselineIndependentOfLaterWrappedLabels() {
        val scene = render(
            """
            timeline
              section Delivery
                Short : First event
                This task label wraps across several lines : Second event
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val shortTask = shapes.getValue("timeline-node-1")
        val wrappedTask = shapes.getValue("timeline-node-3")

        assertTrue(wrappedTask.bounds.height > shortTask.bounds.height)
    }

    @Test
    fun keepsTdVirtualTaskBaselineIndependentOfLaterWrappedLabels() {
        val scene = render(
            """
            timeline TD
              section Delivery
                Short : First event
                This task label wraps across several lines : Second event
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val shortTask = shapes.getValue("timeline-node-1")
        val wrappedTask = shapes.getValue("timeline-node-3")

        assertTrue(wrappedTask.bounds.height > shortTask.bounds.height)
    }

    @Test
    fun paintsLaterTdSectionAboveEarlierEventTextLikeUpstreamNodeWrappers() {
        val scene = render(
            """
            timeline TD
              title Vertical delivery plan
              section Build
                Foundation : Architecture review : API contract
              section Release
                Release candidate : Automated checks : Manual review
            """.trimIndent(),
        )
        val apiContract = scene.elements.filterIsInstance<SceneText>()
            .single { text -> text.text == "API contract" }
        val releaseSection = scene.elements.filterIsInstance<SceneShape>()
            .single { shape -> shape.id == "timeline-node-4" }
        val title = scene.elements.filterIsInstance<SceneText>()
            .single { text -> text.text == "Vertical delivery plan" }

        assertTrue(apiContract.bounds.bottom > releaseSection.bounds.top)
        assertTrue(apiContract.bounds.top < releaseSection.bounds.bottom)
        assertTrue(releaseSection.zIndex > apiContract.zIndex)
        assertTrue(title.zIndex > releaseSection.zIndex)
    }

    @Test
    fun rotatesColorsWithoutSectionsUnlessMulticolorIsDisabled() {
        val colorful = render(
            """
            timeline
              One : A
              Two : B
            """.trimIndent(),
        )
        val monochrome = render(
            """
            ---
            config:
              timeline:
                disableMulticolor: true
            ---
            timeline
              One : A
              Two : B
            """.trimIndent(),
        )
        val colorfulTasks = colorful.elements.filterIsInstance<SceneShape>()
            .filter { shape -> shape.id in setOf("timeline-node-0", "timeline-node-2") }
        val monochromeTasks = monochrome.elements.filterIsInstance<SceneShape>()
            .filter { shape -> shape.id in setOf("timeline-node-0", "timeline-node-2") }

        assertNotEquals(colorfulTasks[0].fill, colorfulTasks[1].fill)
        assertEquals(monochromeTasks[0].fill, monochromeTasks[1].fill)
    }

    @Test
    fun appliesReduxShapePaletteTextAndNeoShadow() {
        val scene = render(
            """
            ---
            config:
              theme: redux-color
              look: neo
            ---
            timeline
              One : Event
            """.trimIndent(),
        )
        val shape = scene.elements.filterIsInstance<SceneShape>()
            .single { element -> element.id == "timeline-node-0" }
        val text = scene.elements.filterIsInstance<SceneText>()
            .single { element -> element.text == "One" }

        assertEquals(SceneColor(0xFFE879F9), shape.fill)
        assertEquals(SceneColor(0xFFE879F9), shape.stroke)
        assertEquals(SceneTextWeight.Bold, text.weight)
        assertNotNull(shape.shadow)
        assertNull(shape.strokeGradient)
        assertTrue(scene.elements.filterIsInstance<ScenePath>().none {
            path -> path.id.endsWith("-bottom")
        })
    }

    @Test
    fun appliesTimelineScopedAppearanceAndViewportConfig() {
        val scene = render(
            """
            ---
            config:
              timeline:
                theme: forest
                look: classic
                leftMargin: 210
                padding: 24
                useMaxWidth: false
            ---
            timeline
              2026 : Launch
            """.trimIndent(),
        )
        val task = scene.elements.filterIsInstance<SceneShape>()
            .single { shape -> shape.id == "timeline-node-0" }

        assertEquals(MermaidTheme.preset(com.swithun.cmpmermaid.core.MermaidThemePreset.Forest)
            .timeline.sectionFills[0], task.fill)
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
        assertTrue(scene.width > 0f)
        assertTrue(scene.height > 0f)
    }

    @Test
    fun returnsStructuredResourceAndConfigurationErrors() {
        val tooMany = engine.render(
            "timeline\n  One : A : B",
            context.copy(options = context.options.copy(maxEdges = 2)),
        )
        assertIs<MermaidError.ResourceLimit>(
            assertIs<GMResult.Err<MermaidError>>(tooMany).error,
        )

        val invalid = engine.render(
            "timeline\n  One",
            context.copy(
                options = context.options.copy(
                    timeline = context.options.timeline.copy(padding = -1f),
                ),
            ),
        )
        assertIs<MermaidError.Configuration>(
            assertIs<GMResult.Err<MermaidError>>(invalid).error,
        )
    }

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(source, context),
            source,
        ).value
}
