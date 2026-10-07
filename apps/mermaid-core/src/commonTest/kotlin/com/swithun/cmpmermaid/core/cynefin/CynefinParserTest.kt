package com.swithun.cmpmermaid.core.cynefin

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.cynefin.upstream.mermaid.CynefinDb
import com.swithun.cmpmermaid.core.cynefin.upstream.mermaid.CynefinDomainName
import com.swithun.cmpmermaid.core.cynefin.upstream.mermaid.CynefinParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CynefinParserTest {
    @Test
    fun parsesUpstreamFullDiagramWithMetadataDomainsAndTransitions() {
        val db = parse(
            """
            cynefin-beta
              title Team Practices
              accTitle: Cynefin for team practices
              accDescr: A map of work by complexity
              complex
                "Retrospectives"
                "Pair programming"
              complicated
                "Code review"
              clear
                "Deployment checklist"
              chaotic
                "Incident response"
              confusion
                "New initiative"
              complex --> complicated : "Pattern emerges"
              complicated --> clear : "Best practice found"
              chaotic --> complex : "Stabilized"
              confusion --> chaotic : "Crisis detected"
            """.trimIndent(),
        )

        assertEquals("Team Practices", db.diagramTitle)
        assertEquals("Cynefin for team practices", db.accessibilityTitle)
        assertEquals("A map of work by complexity", db.accessibilityDescription)
        assertEquals(CynefinDomainName.entries, db.getDomains().keys.toList())
        assertEquals(
            listOf("Retrospectives", "Pair programming"),
            db.getDomains()[CynefinDomainName.Complex]?.items?.map { item -> item.label },
        )
        assertEquals(4, db.getTransitions().size)
        assertEquals("Pattern emerges", db.getTransitions().first().label)
    }

    @Test
    fun acceptsColonHeaderCommentsEscapesAndMultilineAccessibility() {
        val source = MermaidPreprocessor.encodeEntities(
            """
            %% leading
            cynefin-beta:
              accDescr {
                First line
                Second  line
              }
              complex %% domain comment
                "AT&amp;T\nProbe"
                'It\'s adaptive'
            """.trimIndent(),
        )
        val db = parse(source)

        assertEquals("First line\nSecond line", db.accessibilityDescription)
        assertEquals(
            listOf("AT&amp;T\nProbe", "It's adaptive"),
            db.getDomains()[CynefinDomainName.Complex]?.items?.map { item -> item.label },
        )
    }

    @Test
    fun replacesDuplicateDomainWithoutChangingInsertionOrder() {
        val db = parse(
            """
            cynefin-beta
              clear
                "First"
              complex
                "Adaptive"
              clear
                "Replacement"
            """.trimIndent(),
        )

        assertEquals(
            listOf(CynefinDomainName.Clear, CynefinDomainName.Complex),
            db.getDomains().keys.toList(),
        )
        assertEquals(
            listOf("Replacement"),
            db.getDomains()[CynefinDomainName.Clear]?.items?.map { item -> item.label },
        )
    }

    @Test
    fun filtersSelfLoopsAndPreservesUnlabelledTransitions() {
        val db = parse(
            """
            cynefin-beta
              complex --> complex : "Reflect"
              complex --> complicated
              clear --> clear
            """.trimIndent(),
        )

        assertEquals(1, db.getTransitions().size)
        assertEquals(CynefinDomainName.Complex, db.getTransitions().single().from)
        assertEquals(CynefinDomainName.Complicated, db.getTransitions().single().to)
        assertNull(db.getTransitions().single().label)
    }

    @Test
    fun returnsStructuredErrorsForInvalidGrammar() {
        val invalidSources = listOf(
            "cynefin\ncomplex",
            "cynefin-beta\nunknown",
            "cynefin-beta\ncomplex\nUnquoted",
            "cynefin-beta\ncomplex --> unknown",
            "cynefin-beta\ncomplex --> clear : unquoted",
            "cynefin-beta\ncomplex --> clear trailing",
            "cynefin-beta\n\"orphan item\"",
            "cynefin-beta\ncomplex\n\"unterminated",
        )

        invalidSources.forEach { source ->
            val error = assertIs<GMResult.Err<MermaidError>>(
                parser().parse(source),
                source,
            ).error
            assertIs<MermaidError.Parse>(error, source)
        }
    }

    @Test
    fun enforcesTransitionLimitAndAppliesFrontmatterLineOffset() {
        val limited = parser(maxEdges = 1).parse(
            """
            cynefin-beta
              complex --> complicated
              complicated --> clear
            """.trimIndent(),
        )
        val limit = assertIs<MermaidError.ResourceLimit>(
            assertIs<GMResult.Err<MermaidError>>(limited).error,
        )
        assertEquals("Cynefin transitions", limit.resource)
        assertEquals(2, limit.actual)
        assertEquals(1, limit.maximum)

        val malformed = parser(lineOffset = 7).parse(
            """
            cynefin-beta
              complex --> unknown
            """.trimIndent(),
        )
        val parse = assertIs<MermaidError.Parse>(
            assertIs<GMResult.Err<MermaidError>>(malformed).error,
        )
        assertEquals(9, parse.line)
    }

    private fun parse(source: String): CynefinDb =
        assertIs<GMResult.Ok<CynefinDb>>(parser().parse(source)).value

    private fun parser(
        maxEdges: Int = 500,
        lineOffset: Int = 0,
    ): CynefinParser = CynefinParser(
        options = MermaidRenderOptions(maxEdges = maxEdges),
        diagramTitle = null,
        lineOffset = lineOffset,
    )
}
