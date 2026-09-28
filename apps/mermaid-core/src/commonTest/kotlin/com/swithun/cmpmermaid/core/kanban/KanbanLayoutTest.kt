package com.swithun.cmpmermaid.core.kanban

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidKanbanOptions
import com.swithun.cmpmermaid.core.MermaidMindmapOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.mermaidLighten
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KanbanLayoutTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val explicitLines = request.text.split('\n')
            val characterWidth = request.fontSize * 0.5f
            val lineCount = explicitLines.sumOf { line ->
                maxOf(1, ceil(line.length * characterWidth / request.maxWidth).toInt())
            }
            TextMetrics(
                width = minOf(
                    request.maxWidth,
                    explicitLines.maxOfOrNull { line -> line.length * characterWidth } ?: 0f,
                ),
                height = lineCount * request.fontSize * request.lineHeight,
                lineCount = lineCount,
            )
        },
        theme = MermaidTheme.MermaidDefault,
        options = MermaidRenderOptions(look = "classic"),
    )

    @Test
    fun laysOutFixedWidthSectionsAndVerticallyStackedItems() {
        val scene = render(
            """
            kanban
              todo[Todo]
                first[First task]
                second[Second task]
              done[Done]
                third[Third task]
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val firstSection = shapes.getValue("kanban-section-0-todo")
        val secondSection = shapes.getValue("kanban-section-1-done")
        val first = shapes.getValue("first")
        val second = shapes.getValue("second")

        assertEquals(200f, firstSection.bounds.width)
        assertEquals(5f, secondSection.bounds.left - firstSection.bounds.right)
        assertEquals(185f, first.bounds.width)
        assertEquals(5f, second.bounds.top - first.bounds.bottom)
        assertEquals(7.5f, first.bounds.left - firstSection.bounds.left)
        assertEquals(10f, firstSection.bounds.left)
        assertEquals(10f, firstSection.bounds.top)
    }

    @Test
    fun reservesBundledFontCompensationInsideUpstreamTitleWidth() {
        val label = "Authorization And Policy Evaluation Label"
        val scene = assertIs<GMResult.Ok<MermaidScene>>(
            engine.render(
                "kanban\n  todo[Todo]\n    task[$label]",
                context.copy(
                    textMetrics = TextMetricProvider { request ->
                        TextMetrics(
                            width = request.maxWidth,
                            height = 24f,
                            lineCount = 1,
                        )
                    },
                ),
            ),
        ).value
        val title = scene.elements.filterIsInstance<SceneText>()
            .single { it.text == label }
        val item = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id == "task" }

        assertEquals(185f, item.bounds.width)
        assertEquals(170f, title.bounds.width)
    }

    @Test
    fun rendersTicketAssigneePriorityAndTicketInteraction() {
        val scene = render(
            """
            ---
            config:
              kanban:
                ticketBaseUrl: "https://issues.example/#TICKET#"
            ---
            kanban
              todo[Todo]
                task[Implement renderer]@{ ticket: MC-42, assigned: Ada, priority: High }
            """.trimIndent(),
        )
        val shape = scene.elements.filterIsInstance<SceneShape>().single { it.id == "task" }
        val texts = scene.elements.filterIsInstance<SceneText>()
        val ticket = texts.single { it.text == "MC-42" }
        val assigned = texts.single { it.text == "Ada" }
        val priority = scene.elements.filterIsInstance<ScenePath>()
            .single { it.id == "task-priority" }
        val interaction = assertNotNull(scene.interactions.singleOrNull())

        assertEquals(56f, shape.bounds.height)
        assertEquals(SceneColor(0xFFFFA500), priority.color)
        assertEquals(shape.bounds.left + 2f, priority.points.first().x)
        assertEquals(ticket.bounds.top, assigned.bounds.top)
        assertTrue(ticket.spans.any { it.underline })
        assertEquals("https://issues.example/MC-42", interaction.link)
        assertEquals("_blank", interaction.linkTarget)
    }

    @Test
    fun appliesSectionWidthButPreservesUpstreamMindmapViewportBug() {
        val scene = render(
            source = """
                kanban
                  todo[Todo]
                    task[Task]
            """.trimIndent(),
            options = context.options.copy(
                kanban = MermaidKanbanOptions(
                    padding = 40f,
                    sectionWidth = 260f,
                ),
                mindmap = MermaidMindmapOptions(
                    padding = 17f,
                    useMaxWidth = false,
                ),
            ),
        )
        val section = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id == "kanban-section-0-todo" }

        assertEquals(260f, section.bounds.width)
        assertEquals(17f, section.bounds.left)
        assertEquals(17f, section.bounds.top)
        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
    }

    @Test
    fun usesUpstreamSectionPaletteOffsetAndNeoStroke() {
        val classic = render(
            "kanban\n  todo[Todo]\n    task[Task]",
        )
        val neo = render(
            source = "kanban\n  todo[Todo]\n    task[Task]",
            options = context.options.copy(look = "neo"),
        )
        val expectedFill = MermaidTheme.MermaidDefault.mindmap.sectionFills[2]
            .mermaidLighten(10.0)
        val classicSection = classic.elements.filterIsInstance<SceneShape>()
            .single { it.id == "kanban-section-0-todo" }
        val neoSection = neo.elements.filterIsInstance<SceneShape>()
            .single { it.id == "kanban-section-0-todo" }

        assertEquals(expectedFill, classicSection.fill)
        assertEquals(expectedFill, classicSection.stroke)
        assertEquals(MermaidTheme.MermaidDefault.nodeStroke, neoSection.stroke)
        assertNotNull(neoSection.shadow)
    }

    @Test
    fun returnsTypedConfigurationAndResourceErrors() {
        val invalidWidth = engine.render(
            "kanban\n  todo",
            context.copy(
                options = context.options.copy(
                    kanban = MermaidKanbanOptions(sectionWidth = 0f),
                ),
            ),
        )
        val tooMany = engine.render(
            "kanban\n  todo\n    one\n    two",
            context.copy(options = context.options.copy(maxEdges = 2)),
        )

        assertIs<MermaidError.Configuration>(
            assertIs<GMResult.Err<MermaidError>>(invalidWidth).error,
        )
        assertIs<MermaidError.ResourceLimit>(
            assertIs<GMResult.Err<MermaidError>>(tooMany).error,
        )
    }

    private fun render(
        source: String,
        options: MermaidRenderOptions = context.options,
    ): MermaidScene = assertIs<GMResult.Ok<MermaidScene>>(
        engine.render(source, context.copy(options = options)),
    ).value
}
