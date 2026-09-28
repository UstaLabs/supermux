package com.swithun.cmpmermaid.core.zenuml

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlCoordinates
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlDocument
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlParser
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlStatementKind
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlVerticalCoordinates
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlWidthProvider
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.findOptimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ZenUmlCoordinatesTest {
    @Test
    fun translatesDavidEisenstatConstraintSolver() {
        val cases = listOf(
            arrayOf(
                doubleArrayOf(0.0, 200.0, 0.0, 900.0),
                doubleArrayOf(0.0, 0.0, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 0.0),
            ) to listOf(0.0, 300.0, 600.0, 900.0),
            arrayOf(
                doubleArrayOf(0.0, 0.0, 2.0, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 5.5, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 0.0, 8.0),
                doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0),
            ) to listOf(0.0, 1.0, 2.0, 6.5, 10.0),
            arrayOf(
                doubleArrayOf(0.0, 0.0, 200.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 150.0),
                doubleArrayOf(0.0, 0.0, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 0.0),
            ) to listOf(0.0, 50.0, 200.0, 200.0),
        )

        cases.forEach { (matrix, expected) ->
            assertEquals(expected, findOptimal(matrix))
        }
    }

    @Test
    fun distributesLongNonAdjacentMessageEvenly() {
        val document = parse(
            """
                zenuml
                A
                B
                C
                A->B: short
                B->C: short
                A->C: wide
            """.trimIndent(),
        )
        val coordinates = ZenUmlCoordinates(
            document,
            ZenUmlWidthProvider { text, _ -> if (text == "wide") 800.0 else 0.0 },
        )

        assertEquals(listOf(50.0, 462.5, 875.0), document.participants.map {
            participant -> coordinates.getPosition(participant.name)
        })
        assertEquals(925.0, coordinates.getWidth())
    }

    @Test
    fun includesTargetHalfWidthInCreationConstraint() {
        val document = parse(
            """
                zenuml
                service = new Service()
            """.trimIndent(),
        )
        val coordinates = ZenUmlCoordinates(
            document,
            ZenUmlWidthProvider { text, _ -> if (text == "«create»") 100.0 else 0.0 },
        )

        assertEquals(50.0, coordinates.getPosition("_STARTER_"))
        assertEquals(225.0, coordinates.getPosition("service:Service"))
    }

    @Test
    fun matchesNestedMessageVerticalCoordinates() {
        val document = parse(
            """
                zenuml
                A.method() {
                  B.inner()
                  return done
                }
            """.trimIndent(),
        )
        val vertical = ZenUmlVerticalCoordinates(document)
        val outer = document.statements.single()
        val outerCoordinate = vertical.getStatementCoordinate(outer)
        val body = assertIs<com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlMessage>(outer).body

        assertEquals(72.0, outerCoordinate?.top)
        assertEquals(104.0, outerCoordinate?.height)
        assertEquals(104.0, vertical.getStatementCoordinate(body[0])?.top)
        assertEquals(38.0, vertical.getStatementCoordinate(body[0])?.height)
        assertEquals(158.0, vertical.getStatementCoordinate(body[1])?.top)
        assertEquals(0.0, vertical.getStatementCoordinate(body[1])?.height)
        assertEquals(ZenUmlStatementKind.Return, vertical.getStatementCoordinate(body[1])?.kind)
        assertEquals(192.0, vertical.getTotalHeight())
    }

    @Test
    fun matchesAltAndTryCatchFinallyHeights() {
        val altDocument = parse(
            """
                zenuml
                if(x) {
                  A->B: yes
                } else {
                  A->B: no
                }
            """.trimIndent(),
        )
        val altVertical = ZenUmlVerticalCoordinates(altDocument)
        val alt = altDocument.statements.single()
        assertEquals(72.0, altVertical.getStatementCoordinate(alt)?.top)
        assertEquals(182.0, altVertical.getStatementCoordinate(alt)?.height)

        val tcfDocument = parse(
            """
                zenuml
                try {
                  A.work()
                } catch(error) {
                  B.recover()
                } finally {
                  C.close()
                }
            """.trimIndent(),
        )
        val tcfVertical = ZenUmlVerticalCoordinates(tcfDocument)
        val tcf = tcfDocument.statements.single()
        assertEquals(72.0, tcfVertical.getStatementCoordinate(tcf)?.top)
        assertEquals(305.0, tcfVertical.getStatementCoordinate(tcf)?.height)
    }

    @Test
    fun recordsCreationParticipantTopAndOccurrenceExtent() {
        val document = parse(
            """
                zenuml
                client = new Service() {
                  Repo.load()
                }
            """.trimIndent(),
        )
        val vertical = ZenUmlVerticalCoordinates(document)
        val creation = document.statements.single()

        assertEquals(64.0, vertical.getCreationTop("client:Service"))
        assertEquals(72.0, vertical.getStatementCoordinate(creation)?.top)
        assertEquals(124.0, vertical.getStatementCoordinate(creation)?.height)
        assertEquals(212.0, vertical.getTotalHeight())
    }

    private fun parse(source: String): ZenUmlDocument =
        assertIs<GMResult.Ok<ZenUmlDocument>>(
            ZenUmlParser(
                options = MermaidRenderOptions(),
            ).parse(source),
        ).value
}
