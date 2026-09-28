package com.swithun.cmpmermaid.core.kanban

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class KanbanParserTest {
    @Test
    fun parsesSectionsAndFlattensEveryDeeperIndentationLevel() {
        val document = parse(
            """
            kanban
              todo[Todo]
                design[Design]
                  api[API]
                build[Build]
              done[Done]
                ship[Ship]
            """.trimIndent(),
        )

        assertEquals(listOf("todo", "done"), document.sections.map { it.node.id })
        assertEquals(
            listOf("design", "api", "build"),
            document.sections[0].items.map(KanbanNode::id),
        )
        assertEquals(listOf("ship"), document.sections[1].items.map(KanbanNode::id))
    }

    @Test
    fun parsesAnonymousAndQuotedNodeForms() {
        val document = parse(
            """
            kanban
              ["String containing []"]
                rounded("String containing ()")
                circle((Circle))
                cloud(-Cloud task-)
                hex{{Hexagon}}
            """.trimIndent(),
        )

        assertEquals("String containing []", document.sections.single().node.id)
        assertEquals(
            listOf("String containing ()", "Circle", "Cloud task-", "Hexagon"),
            document.sections.single().items.map(KanbanNode::label),
        )
    }

    @Test
    fun parsesInlineAndMultilineMetadata() {
        val document = parse(
            """
            kanban
              todo[Todo]
                first[Fix parser]@{ ticket: MC-42, assigned: 'Ada', priority: 'High' }
                second@{
                  label: "Review rendering"
                  ticket: MC-43
                  assigned: Lin
                  priority: Very Low
                }
            """.trimIndent(),
        )

        val first = document.sections.single().items[0]
        val second = document.sections.single().items[1]
        assertEquals("MC-42", first.ticket)
        assertEquals("Ada", first.assigned)
        assertEquals("High", first.priority)
        assertEquals("Review rendering", second.label)
        assertEquals("MC-43", second.ticket)
        assertEquals("Lin", second.assigned)
        assertEquals("Very Low", second.priority)
    }

    @Test
    fun acceptsDecorationsCommentsBlankRowsAndLeadingWhitespace() {
        val document = parse(
            """

              %% board comment
              kanban
                todo[Todo]
                ::icon(star)
                :::m-4 p-8

                  task[Task] %% item comment
            """.trimIndent(),
        )

        assertEquals("todo", document.sections.single().node.id)
        assertEquals("task", document.sections.single().items.single().id)
        // Mermaid getData intentionally drops section decorators.
        assertNull(document.sections.single().node.icon)
        assertNull(document.sections.single().node.cssClasses)
    }

    @Test
    fun returnsTypedErrorsForInvalidHierarchyMetadataAndDecorations() {
        val sources = listOf(
            """
            kanban
                  root
                shallowerItem
              invalid
            """.trimIndent(),
            "kanban\n  root@{ shape: KanbanItem }",
            "kanban\n  root@{ ticket: [",
            "kanban\n  ::icon(star)",
            "kanban\n",
        )

        sources.forEach { source ->
            assertIs<GMResult.Err<MermaidError>>(parser().parse(source), source)
        }
    }

    @Test
    fun addsFrontmatterOffsetToParseErrors() {
        val error = assertIs<GMResult.Err<MermaidError>>(
            KanbanParser(lineOffset = 7).parse("kanban\n  root@{"),
        ).error
        val parse = assertIs<MermaidError.Parse>(error)

        assertEquals(9, parse.line)
    }

    private fun parse(source: String): KanbanDocument =
        assertIs<GMResult.Ok<KanbanDocument>>(parser().parse(source)).value

    private fun parser(): KanbanParser = KanbanParser(lineOffset = 0)
}
