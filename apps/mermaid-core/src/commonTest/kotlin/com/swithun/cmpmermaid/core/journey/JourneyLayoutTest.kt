package com.swithun.cmpmermaid.core.journey

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JourneyLayoutTest {
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = minOf(request.maxWidth, request.text.length * request.fontSize * 0.5f),
                height = request.fontSize * request.lineHeight,
            )
        },
        options = MermaidRenderOptions(),
    )

    @Test
    fun translatesOfficialJourneyCoordinatesAndDrawingOrder() {
        val scene = render(
            """
            journey
                title My working day
                section Go to work
                  Make tea: 5: Me
                  Go upstairs: 3: Me
                  Do work: 1: Me, Cat
                section Go home
                  Go downstairs: 5: Me
                  Sit down: 5: Me
            """.trimIndent(),
        )

        assertEquals(1300f, scene.width)
        assertEquals(565f, scene.height)

        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        assertEquals(150f, shapes.getValue("journey-section-0").bounds.left)
        assertEquals(700f, shapes.getValue("journey-section-0").bounds.right)
        assertEquals(75f, shapes.getValue("journey-section-0").bounds.top)
        assertEquals(135f, shapes.getValue("journey-task-0").bounds.top)
        assertEquals(325f, shapes.getValue("journey-face-0").bounds.center.y)
        assertEquals(385f, shapes.getValue("journey-face-1").bounds.center.y)
        assertEquals(445f, shapes.getValue("journey-face-2").bounds.center.y)

        val paths = scene.elements.filterIsInstance<ScenePath>().associateBy(ScenePath::id)
        val taskLine = paths.getValue("journey-task-line-0")
        assertEquals(SceneStrokePattern.Dashed, taskLine.strokePattern)
        assertEquals(listOf(4f, 2f), taskLine.dashIntervals)
        val activity = paths.getValue("journey-activity-line")
        assertEquals(SceneArrowHead.Triangle, activity.arrowEnd)
        assertEquals(225f, activity.points.first().y)
    }

    @Test
    fun usesAlphabeticalActorPositionsForTaskMarkers() {
        val scene = render(
            """
            journey
                section Work
                  Pair: 4: Zoe, Alice
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)

        assertEquals(
            context.options.journey.actorColours[1],
            shapes.getValue("journey-task-0-actor-0").fill,
        )
        assertEquals(
            context.options.journey.actorColours[0],
            shapes.getValue("journey-task-0-actor-1").fill,
        )
    }

    @Test
    fun carriesAccessibilityMetadataAndSkipsNonFiniteFaces() {
        val scene = render(
            """
            journey
                accTitle: Checkout journey
                accDescr: Customer path
                section Checkout
                  Invalid score: unknown: Customer
            """.trimIndent(),
        )

        assertEquals("Checkout journey", scene.accessibilityTitle)
        assertEquals("Customer path", scene.accessibilityDescription)
        assertTrue(
            scene.elements.filterIsInstance<SceneShape>()
                .none { shape -> shape.id == "journey-face-0" },
        )
    }

    @Test
    fun wrapsLongActorLabelsAndExpandsLeftMarginLikeOfficialRenderer() {
        val scene = render(
            """
            journey
                section Work
                  Task: 4: This actor label is intentionally long enough to wrap
            """.trimIndent(),
            context.copy(
                options = context.options.copy(
                    journey = context.options.journey.copy(maxLabelWidth = 80f),
                ),
            ),
        )
        val legendLines = scene.elements.filterIsInstance<SceneText>()
            .filter { text -> text.bounds.left == 46f }
        val task = scene.elements.filterIsInstance<SceneShape>()
            .single { shape -> shape.id == "journey-task-0" }

        assertTrue(legendLines.size > 1)
        assertTrue(task.bounds.left > context.options.journey.leftMargin)
    }

    @Test
    fun mirrorsOfficialTextPlacementStrategies() {
        val source = """
            journey
                section Work
                  First line<br/>Second line: 4: Person
        """.trimIndent()

        val foreignObjectScene = render(source)
        val foreignObjectText = foreignObjectScene.elements.filterIsInstance<SceneText>()
            .single { text -> text.text == "First line<br/>Second line" }
        assertTrue(foreignObjectText.softWrap)
        assertTrue(foreignObjectText.clipToBounds)
        assertFalse(
            foreignObjectScene.elements.filterIsInstance<SceneText>()
                .single { text -> text.text == "Person" }
                .clipToBounds,
        )

        val oldText = render(
            source,
            context.withTextPlacement("old"),
        ).elements.filterIsInstance<SceneText>()
            .single { text -> text.text == "First line<br/>Second line" }
        assertFalse(oldText.softWrap)
        assertFalse(oldText.clipToBounds)

        val tspanText = render(
            source,
            context.withTextPlacement("tspan"),
        ).elements.filterIsInstance<SceneText>()
            .filter { text -> text.text == "First line" || text.text == "Second line" }
        assertEquals(listOf("First line", "Second line"), tspanText.map(SceneText::text))
        assertEquals(
            context.options.journey.taskFontSize,
            tspanText[1].bounds.center.y - tspanText[0].bounds.center.y,
        )
        assertTrue(tspanText.none(SceneText::softWrap))
        assertTrue(tspanText.none(SceneText::clipToBounds))
    }

    private fun MermaidRenderContext.withTextPlacement(
        textPlacement: String,
    ): MermaidRenderContext = copy(
        options = options.copy(
            journey = options.journey.copy(textPlacement = textPlacement),
        ),
    )

    private fun render(
        source: String,
        renderContext: MermaidRenderContext = context,
    ): MermaidScene {
        val result = MermaidEngine().render(source, renderContext)
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected Journey render success:\n$source\n$result",
        ).value
    }
}
