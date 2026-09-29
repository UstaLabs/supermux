package com.swithun.cmpmermaid.core.journey.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JourneyJisonParserTest {
    @Test
    fun parsesTitleSectionsTasksActorsAndScores() {
        val db = parse(
            """
            journey
                title My working day
                section Go to work
                  Make tea: 5: Me
                  Go upstairs: 3: Me
                  Do work: 1: Me, Cat
                section Go home
                  Sit down: 5: Me
            """.trimIndent(),
        )

        assertEquals("My working day", db.diagramTitle)
        assertEquals(listOf("Go to work", "Go home"), db.getSections())
        assertEquals(listOf("Cat", "Me"), db.getActors())
        assertEquals(4, db.getTasks().size)
        assertEquals("Go to work", db.getTasks()[0].section)
        assertEquals("Make tea", db.getTasks()[0].task)
        assertEquals(5.0, db.getTasks()[0].score)
        assertEquals(listOf("Me", "Cat"), db.getTasks()[2].people)
    }

    @Test
    fun parsesAccessibilityAndComments() {
        val db = parse(
            """
            journey
                %% comment
                accTitle: Accessible journey
                accDescr {
                  A multiline description.
                }
                section Stage
                  Task without actors: 2
            """.trimIndent(),
        )

        assertEquals("Accessible journey", db.accessibilityTitle)
        assertEquals("A multiline description.", db.accessibilityDescription)
        assertEquals(emptyList(), db.getTasks().single().people)
    }

    @Test
    fun matchesJourneyDbNumberAndActorSemantics() {
        val db = parse(
            """
            journey
                section Stage
                  Empty score: ${"\t"}
                  Invalid score: nope: Beta, Alpha: Ignored
            """.trimIndent(),
        )

        assertEquals(0.0, db.getTasks()[0].score)
        assertTrue(db.getTasks()[1].score.isNaN())
        assertEquals(listOf("Beta", "Alpha"), db.getTasks()[1].people)
        assertEquals(listOf("Alpha", "Beta"), db.getActors())
    }

    @Test
    fun reportsInvalidStatementsWithFrontmatterOffset() {
        val result = JourneyJisonParser(lineOffset = 3).parse(
            """
            journey
            :
            """.trimIndent() + "\n",
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        val parse = assertIs<MermaidError.Parse>(error)
        assertEquals(5, parse.line)
    }

    private fun parse(source: String): JourneyDb {
        val result = JourneyJisonParser().parse("$source\n")
        return assertIs<GMResult.Ok<JourneyDb>>(
            result,
            "Expected Journey parse success:\n$source\n$result",
        ).value
    }
}
