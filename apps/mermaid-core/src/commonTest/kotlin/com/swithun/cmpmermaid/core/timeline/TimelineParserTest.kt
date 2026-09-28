package com.swithun.cmpmermaid.core.timeline

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TimelineParserTest {
    @Test
    fun parsesDefaultLrAndExplicitDirections() {
        val default = parse("timeline\n  2024 : Shipped")
        val lr = parse("TiMeLiNe LR\n  2024 : Shipped")
        val td = parse("timeline TD\n  2024 : Shipped")

        assertEquals(TimelineDirection.LR, default.direction)
        assertEquals(TimelineDirection.LR, lr.direction)
        assertEquals(TimelineDirection.TD, td.direction)
    }

    @Test
    fun parsesSectionsPeriodsAndSameOrFollowingLineEvents() {
        val document = parse(
            """
            timeline
              section First age
                Early period : event one : https://example.com/a:b
                Later period : event two
                             : event three : event four
              section Second age
                Named period
            """.trimIndent(),
        )

        assertEquals(listOf("First age", "Second age"), document.sections)
        assertEquals(listOf("Early period ", "Later period ", "Named period"), document.tasks.map {
            it.task
        })
        assertEquals(
            listOf("event one ", "https://example.com/a:b"),
            document.tasks[0].events,
        )
        assertEquals(
            listOf("event two", "event three ", "event four"),
            document.tasks[1].events,
        )
        assertEquals("First age", document.tasks[1].section)
        assertEquals("Second age", document.tasks[2].section)
    }

    @Test
    fun preservesSemicolonsAndHashesWhereTheUpstreamLexerDoes() {
        val document = parse(
            """
            timeline
              title ;my;title;#visible
              section ;a;bc#123;
              ;ta;sk; : ;ev;ent1; : #ev#ent2#
              ignored period # comment
            """.trimIndent(),
        )

        assertEquals(";my;title;#visible", document.title)
        assertEquals(listOf(";a;bc#123;"), document.sections)
        assertEquals(";ta;sk; ", document.tasks[0].task)
        assertEquals(listOf(";ev;ent1; ", "#ev#ent2#"), document.tasks[0].events)
        assertEquals("ignored period ", document.tasks[1].task)
    }

    @Test
    fun parsesMetadataAndPreservesEntitiesLikeUpstreamTimelineLexer() {
        val document = parse(
            """
            timeline
              accTitle: Release &amp; migration
              accDescr {
                First line
                Second &lt; third
              }
              2026 : R&amp;D
            """.trimIndent(),
            diagramTitle = "Frontmatter title",
        )

        assertEquals("Frontmatter title", document.title)
        assertEquals("Release &amp; migration", document.accessibilityTitle)
        assertEquals("First line\n    Second &lt; third", document.accessibilityDescription)
        assertEquals(listOf("R&amp;D"), document.tasks.single().events)
    }

    @Test
    fun dslTitleOverridesFrontmatterTitle() {
        val document = parse(
            "timeline\n  title Source title\n  Period",
            diagramTitle = "Frontmatter title",
        )

        assertEquals("Source title", document.title)
    }

    @Test
    fun returnsStructuredErrorWhenEventPrecedesPeriod() {
        val result = parser(lineOffset = 7).parse(
            """
            timeline
              : orphan event
            """.trimIndent(),
        )
        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        val parse = assertIs<MermaidError.Parse>(error)

        assertEquals(9, parse.line)
    }

    @Test
    fun returnsStructuredErrorsForInvalidHeaderAndAccessibilityBlocks() {
        val invalidSources = listOf(
            "timeline BT\n  2024",
            "timeline\n  accTitle:",
            "timeline\n  accDescr:",
            "timeline\n  accDescr {\n    never closes",
        )

        invalidSources.forEach { source ->
            val error = assertIs<GMResult.Err<MermaidError>>(parser().parse(source)).error
            assertIs<MermaidError.Parse>(error)
        }
    }

    private fun parse(
        source: String,
        diagramTitle: String? = null,
    ): TimelineDocument = assertIs<GMResult.Ok<TimelineDocument>>(
        TimelineParser(
            diagramTitle = diagramTitle,
            lineOffset = 0,
        ).parse(source),
    ).value

    private fun parser(lineOffset: Int = 0): TimelineParser = TimelineParser(
        diagramTitle = null,
        lineOffset = lineOffset,
    )
}
