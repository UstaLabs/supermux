package com.swithun.cmpmermaid.core.eventmodeling

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid.EventModelingDb
import com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid.EventModelingFrameKind
import com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid.EventModelingParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EventModelingParserTest {
    @Test
    fun parsesFramesDataMetadataNotesAndGwt() {
        val db = parse(
            """
            eventmodeling
            title Order lifecycle
            accTitle: Accessible event model
            accDescr {
              Events and decisions
            }
            entity CartUI
            entity AddItem
            entity ItemAdded
            timeframe 001 ui Store.CartUI
            tf 010 command Store.AddItem ->> 001 `json`{ productId: 7 }
            tf 020 event Store.ItemAdded ->> 010 [[ItemAddedData]]
            resetframe 030 readmodel CartItems
            data ItemAddedData `json`{
              productId: 7
            }
            note 020 `md`{
              Emitted after validation
            }
            gwt 020
              given ui CartUI
              when command AddItem
              then event ItemAdded
            """.trimIndent(),
        )

        val ast = db.getAst()
        assertEquals("Order lifecycle", db.diagramTitle)
        assertEquals("Accessible event model", db.accessibilityTitle)
        assertEquals("Events and decisions", db.accessibilityDescription)
        assertEquals(4, ast.frames.size)
        assertEquals(EventModelingFrameKind.ResetFrame, ast.frames.last().kind)
        assertEquals(listOf("001"), ast.frames[1].sourceFrameNames)
        assertEquals("json", ast.frames[1].dataType)
        assertEquals("{ productId: 7 }", ast.frames[1].dataInlineValue)
        assertEquals("ItemAddedData", ast.frames[2].dataReference)
        assertEquals("json", ast.dataEntities.single().dataType)
        assertEquals(1, ast.noteEntities.size)
        assertEquals(1, ast.gwtEntities.size)
        assertEquals("AddItem", ast.gwtEntities.single().whenStatements.single().entityIdentifier)
    }

    @Test
    fun acceptsAllEntityTypeAliasesAndMultipleSources() {
        val db = parse(
            """
            eventmodeling
            rf 01 ui UI
            rf 02 pcr Processor
            rf 03 processor ProcessorLong
            rf 04 cmd Command
            rf 05 command CommandLong
            rf 06 evt Event
            rf 07 event EventLong
            rf 08 rmo ReadModel
            rf 09 readmodel ReadModelLong
            tf 10 command Combined ->> 01 ->> 02 ->> 03
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "ui",
                "pcr",
                "processor",
                "cmd",
                "command",
                "evt",
                "event",
                "rmo",
                "readmodel",
                "command",
            ),
            db.getAst().frames.map { frame -> frame.modelEntityType },
        )
        assertEquals(listOf("01", "02", "03"), db.getAst().frames.last().sourceFrameNames)
    }

    @Test
    fun acceptsLangiumHiddenWhitespaceAndCommentsBetweenFrameTokens() {
        val db = parse(
            """
            eventmodeling tf 01 ui UI /* same-line declaration */ tf 02
            command Add /* before source */ ->> 01
            tf 03 event Added ->> /* before frame reference */ 02
            """.trimIndent(),
        )

        assertEquals(listOf("01", "02", "03"), db.getAst().frames.map { frame -> frame.name })
        assertEquals(listOf("01"), db.getAst().frames[1].sourceFrameNames)
        assertEquals(listOf("02"), db.getAst().frames[2].sourceFrameNames)
    }

    @Test
    fun acceptsLangiumHiddenTriviaAcrossQualifiedNamesAndDataTokens() {
        val db = parse(
            """
            eventmodeling
            tf 01 ui Store /* before dot */ . // before segment
              UI
            data Payload /* after name */ ` /* before type */ json /* after type */ ` /* before block */ {
              value: 1
            } /* after block */ note /* after keyword */ 01 /* after source */ ` /* before type */ md /* after type */ ` /* before block */ {
              note
            } tf 02 command Add ->> 01 ` /* before inline type */ json /* after inline type */ ` /* before inline */ { value: 1 }
            """.trimIndent(),
        )

        val ast = db.getAst()
        assertEquals(listOf("Store.UI", "Add"), ast.frames.map { frame -> frame.entityIdentifier })
        assertEquals("json", ast.frames.last().dataType)
        assertEquals("json", ast.dataEntities.single().dataType)
        assertEquals("md", ast.noteEntities.single().dataType)
    }

    @Test
    fun returnsStructuredErrorsForReferencesValidationAndMalformedInput() {
        val invalidSources = listOf(
            "eventmodel\n",
            "eventmodeling\ntf 1000 ui UI",
            "eventmodeling\ntf 01 unknown UI",
            "eventmodeling\ntf 01 ui 1UI",
            "eventmodeling\ntf 01 ui UI ->> 99",
            "eventmodeling\ntf 01 ui UI [[Missing]]",
            "eventmodeling\ndata D { value }",
            "eventmodeling\ntf 01 ui UI `xml`{ value }",
            "eventmodeling\n/* unterminated",
            "eventmodeling\ntf 01 ui UI\n/* unterminated",
            "eventmodeling\ndata D {\n  value: 1\n  }",
            """
                eventmodeling
                rf 01 evt Event
                tf 02 cmd Command ->> 01
            """.trimIndent(),
        )

        invalidSources.forEach { source ->
            val result = parser().parse(source)
            assertIs<MermaidError.Parse>(
                assertIs<GMResult.Err<MermaidError>>(result, source).error,
                source,
            )
        }
    }

    @Test
    fun appliesFrontmatterLineOffsetToParseErrors() {
        val result = parser(lineOffset = 5).parse(
            """
            eventmodeling
            tf 01 invalid UI
            """.trimIndent(),
        )

        val error = assertIs<MermaidError.Parse>(
            assertIs<GMResult.Err<MermaidError>>(result).error,
        )
        assertEquals(7, error.line)
    }

    private fun parse(source: String): EventModelingDb =
        assertIs<GMResult.Ok<EventModelingDb>>(parser().parse(source)).value

    private fun parser(lineOffset: Int = 0): EventModelingParser =
        EventModelingParser(
            options = MermaidRenderOptions(),
            diagramTitle = null,
            lineOffset = lineOffset,
        )
}
