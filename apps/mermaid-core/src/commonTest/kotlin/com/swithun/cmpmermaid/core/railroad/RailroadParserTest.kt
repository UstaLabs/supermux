package com.swithun.cmpmermaid.core.railroad

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRailroadOptions
import com.swithun.cmpmermaid.core.railroad.upstream.mermaid.RailroadAstNode
import com.swithun.cmpmermaid.core.railroad.upstream.mermaid.RailroadDb
import com.swithun.cmpmermaid.core.railroad.upstream.mermaid.RailroadParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RailroadParserTest {
    @Test
    fun parsesIrConstructorsAndCollapsesSingleChildContainers() {
        val db = parse(
            """
            railroad-beta
              title "IR Grammar"
              terminalRule = terminal("hello") ;
              referenceRule = nonterminal("expression") ;
              sequenceRule = sequence(terminal("a"), nonterminal("b")) ;
              collapsed = sequence(choice(terminal("only"))) ;
              optionalRule = optional(terminal("value")) ;
              starRule = zeroOrMore(terminal("item")) ;
              plusRule = oneOrMore(nonterminal("item")) ;
              specialRule = special("any character") ;
            """.trimIndent(),
        )

        assertEquals("IR Grammar", db.diagramTitle)
        assertEquals(8, db.getRules().size)
        assertIs<RailroadAstNode.Terminal>(db.getRules()[0].definition)
        assertIs<RailroadAstNode.NonTerminal>(db.getRules()[1].definition)
        assertIs<RailroadAstNode.Sequence>(db.getRules()[2].definition)
        assertIs<RailroadAstNode.Terminal>(db.getRules()[3].definition)
        assertIs<RailroadAstNode.Optional>(db.getRules()[4].definition)
        assertEquals(
            0,
            assertIs<RailroadAstNode.Repetition>(db.getRules()[5].definition).min,
        )
        assertEquals(
            1,
            assertIs<RailroadAstNode.Repetition>(db.getRules()[6].definition).min,
        )
        assertEquals(
            "any character",
            assertIs<RailroadAstNode.Special>(db.getRules()[7].definition).text,
        )
    }

    @Test
    fun parsesEbnfW3cAndIsoForms() {
        val db = parse(
            """
            railroad-ebnf-beta
              /* W3C comment */
              (* ISO comment *)
              expression ::= term, ( ("+" | "-") term )* ;
              optional = [ "sign" ] ;
              repeated = { "digit" } ;
              oneOrMore = "letter"+ ;
              excluded = letter - "x" ;
              special = ? any unicode scalar ? ;
            """.trimIndent(),
        )

        assertEquals(6, db.getRules().size)
        assertIs<RailroadAstNode.Sequence>(db.getRules()[0].definition)
        assertIs<RailroadAstNode.Optional>(db.getRules()[1].definition)
        assertEquals(
            0,
            assertIs<RailroadAstNode.Repetition>(db.getRules()[2].definition).min,
        )
        assertEquals(
            1,
            assertIs<RailroadAstNode.Repetition>(db.getRules()[3].definition).min,
        )
        val exception = assertIs<RailroadAstNode.Sequence>(db.getRules()[4].definition)
        assertEquals("-", assertIs<RailroadAstNode.Terminal>(exception.elements[1]).value)
        assertEquals(
            "any unicode scalar",
            assertIs<RailroadAstNode.Special>(db.getRules()[5].definition).text,
        )
    }

    @Test
    fun parsesAbnfRepetitionNumericValuesAndGroups() {
        val db = parse(
            """
            railroad-abnf-beta
              value = 2*4( ALPHA / DIGIT ) ;
              optional = [ "+" ] ;
              exact = 3%x41 ;
              unbounded = *token ;
              sequence = %x30-39 "." token ;
            """.trimIndent(),
        )

        val bounded = assertIs<RailroadAstNode.Repetition>(db.getRules()[0].definition)
        assertEquals(2, bounded.min)
        assertEquals(4, bounded.max)
        assertIs<RailroadAstNode.Optional>(db.getRules()[1].definition)
        val exact = assertIs<RailroadAstNode.Repetition>(db.getRules()[2].definition)
        assertEquals(3, exact.min)
        assertEquals(3, exact.max)
        assertEquals(
            0,
            assertIs<RailroadAstNode.Repetition>(db.getRules()[3].definition).min,
        )
        val sequence = assertIs<RailroadAstNode.Sequence>(db.getRules()[4].definition)
        assertEquals("%x30-39", assertIs<RailroadAstNode.Terminal>(sequence.elements[0]).value)
    }

    @Test
    fun parsesPegOrderedChoiceSuffixesPredicatesAndAny() {
        val db = parse(
            """
            railroad-peg-beta
              # PEG comment
              expression <- term (("+" / "-") term)* ;
              identifier <- !keyword letter letter* ;
              lookahead <- &"a" ;
              any <- . ;
              maybe <- item? ;
              required <- item+ ;
            """.trimIndent(),
        )

        assertEquals(6, db.getRules().size)
        assertIs<RailroadAstNode.Sequence>(db.getRules()[0].definition)
        val identifier = assertIs<RailroadAstNode.Sequence>(db.getRules()[1].definition)
        assertEquals(
            "!keyword",
            assertIs<RailroadAstNode.Special>(identifier.elements[0]).text,
        )
        assertEquals(
            "&\"a\"",
            assertIs<RailroadAstNode.Special>(db.getRules()[2].definition).text,
        )
        assertEquals(".", assertIs<RailroadAstNode.Special>(db.getRules()[3].definition).text)
        assertIs<RailroadAstNode.Optional>(db.getRules()[4].definition)
        assertEquals(
            1,
            assertIs<RailroadAstNode.Repetition>(db.getRules()[5].definition).min,
        )
    }

    @Test
    fun carriesAccessibilityMetadataAndDuplicateRuleSemantics() {
        val db = parse(
            """
            railroad-ebnf-beta
              title "Escaped\nTitle"
              accTitle: Accessible Railroad
              accDescr {
                First   line

                Second line
              }
              item = "<script>bad()</script>safe" ;
              item = "replacement" ;
            """.trimIndent(),
            diagramTitle = "Frontmatter title",
        )

        assertEquals("Escaped\nTitle", db.diagramTitle)
        assertEquals("Accessible Railroad", db.accessibilityTitle)
        assertEquals("First line\nSecond line", db.accessibilityDescription)
        assertEquals(2, db.getRules().size)
        assertEquals(
            "replacement",
            assertIs<RailroadAstNode.Terminal>(db.getRule("item")?.definition).value,
        )
        assertTrue(
            assertIs<RailroadAstNode.Terminal>(db.getRules().first().definition)
                .value
                .contains("safe"),
        )
        assertTrue(
            !assertIs<RailroadAstNode.Terminal>(db.getRules().first().definition)
                .value
                .contains("<script>"),
        )
    }

    @Test
    fun returnsTypedErrorsForMalformedInputs() {
        val malformed = listOf(
            "railroad-beta\nrule = terminal(\"a\")",
            "railroad-beta\nrule = terminal(\"a) ;",
            "railroad-beta\nrule = unknown(\"a\") ;",
            "railroad-ebnf-beta\nrule = ( \"a\" ;",
            "railroad-ebnf-beta\nrule = ? missing ;",
            "railroad-abnf-beta\nrule = 999999999999999999999\"a\" ;",
            "railroad-peg-beta\nrule = \"a\" ;",
            "railroad-peg-beta\nrule <- (\"a\" / ) ;",
        )

        malformed.forEach { source ->
            assertIs<GMResult.Err<MermaidError>>(parser().parse(source), source)
        }
    }

    @Test
    fun reportsFrontmatterAdjustedLocationsAndEmptyGrammar() {
        val error = assertIs<GMResult.Err<MermaidError>>(
            parser(lineOffset = 5).parse(
                """
                railroad-ebnf-beta
                  rule = "a"
                """.trimIndent(),
            ),
        ).error
        val parse = assertIs<MermaidError.Parse>(error)
        assertEquals(7, parse.line)

        val empty = parse(
            """
            railroad-beta
              title Empty Grammar
            """.trimIndent(),
        )
        assertEquals("Empty Grammar", empty.diagramTitle)
        assertTrue(empty.getRules().isEmpty())
        assertNull(empty.accessibilityTitle)
    }

    private fun parse(
        source: String,
        diagramTitle: String? = null,
    ): RailroadDb = assertIs<GMResult.Ok<RailroadDb>>(
        parser(diagramTitle = diagramTitle).parse(source),
        source,
    ).value

    private fun parser(
        lineOffset: Int = 0,
        diagramTitle: String? = null,
    ): RailroadParser = RailroadParser(
        config = MermaidRailroadOptions(),
        diagramTitle = diagramTitle,
        lineOffset = lineOffset,
    )
}
