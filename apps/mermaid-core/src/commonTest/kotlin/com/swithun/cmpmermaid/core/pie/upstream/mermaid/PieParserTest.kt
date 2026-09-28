package com.swithun.cmpmermaid.core.pie.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PieParserTest {
    @Test
    fun parsesHeaderSectionsAndShowData() {
        val db = parse(
            """
            pie showData
                "GitHub" : 100
                "GitLab" : 50.25
            """.trimIndent(),
        )

        assertTrue(db.getShowData())
        assertEquals(
            linkedMapOf(
                "GitHub" to 100.0,
                "GitLab" to 50.25,
            ),
            db.getSections(),
        )
    }

    @Test
    fun parsesTitleAccessibilityCommentsAndEscapedLabels() {
        val db = parse(
            """
            pie title Product mix %% inline comment
                accTitle: Product distribution
                accDescr {
                    First line

                    Second   line
                }
                "Quoted \"label\"" : 10
                'Single \'label\'' : 20 %% another comment
            """.trimIndent(),
        )

        assertEquals("Product mix", db.diagramTitle)
        assertEquals("Product distribution", db.accessibilityTitle)
        assertEquals("First line\nSecond line", db.accessibilityDescription)
        assertEquals(
            listOf("Quoted \"label\"", "Single 'label'"),
            db.getSections().keys.toList(),
        )
    }

    @Test
    fun preservesFirstDuplicateSectionLikePieDb() {
        val db = parse(
            """
            pie
                "A" : 10
                "A" : 99
            """.trimIndent(),
        )

        assertEquals(mapOf("A" to 10.0), db.getSections())
    }

    @Test
    fun acceptsZeroAndRejectsNegativeValues() {
        val negativeZero = parse(
            """
            pie
                "Zero" : -0
            """.trimIndent(),
        ).getSections().getValue("Zero")
        assertEquals((-0.0).toRawBits(), negativeZero.toRawBits())

        val result = PieParser().parse(
            """
            pie
                "Invalid" : -1.25
            """.trimIndent(),
        )
        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        assertIs<MermaidError.Parse>(error)
        assertEquals(2, error.line)
        assertTrue(error.message.contains("Negative values are not allowed"))
    }

    @Test
    fun rejectsNonGrammarNumbersAndTrailingContent() {
        listOf("01", "1.", "+1", "1e2", "1;").forEach { value ->
            val result = PieParser().parse(
                """
                pie
                    "Invalid" : $value
                """.trimIndent(),
            )

            assertIs<GMResult.Err<MermaidError>>(
                result,
                "Expected '$value' to be rejected",
            )
        }
    }

    @Test
    fun requiresCaseSensitiveKeywordBoundaries() {
        listOf(
            "PIE\n \"A\":1",
            "pieChart\n \"A\":1",
            "pie showdata\n \"A\":1",
        ).forEach { source ->
            assertIs<GMResult.Err<MermaidError>>(
                PieParser().parse(source),
                "Expected invalid header:\n$source",
            )
        }
    }

    private fun parse(source: String): PieDb {
        val result = PieParser().parse(source)
        return assertIs<GMResult.Ok<PieDb>>(
            result,
            "Expected Pie parse success:\n$source\n$result",
        ).value
    }
}
