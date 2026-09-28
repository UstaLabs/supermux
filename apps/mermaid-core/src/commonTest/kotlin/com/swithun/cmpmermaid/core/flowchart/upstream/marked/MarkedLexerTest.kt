package com.swithun.cmpmermaid.core.flowchart.upstream.marked

import com.swithun.cmpmermaid.core.GMResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkedLexerTest {
    @Test
    fun matchesMermaidResolvedMarkedTokenFixtures() {
        MarkedFixtures.cases.forEach { fixture ->
            val result = MarkedLexer.lex(fixture.source)
            val tokens = assertIs<GMResult.Ok<List<MarkedToken>>>(
                result,
                "Marked fixture '${fixture.id}' did not lex",
            ).value

            assertEquals(
                expected = fixture.tokens,
                actual = tokens.map { it.toFixtureToken() },
                message = "Marked fixture '${fixture.id}' diverged",
            )
        }
    }

    private fun MarkedToken.toFixtureToken(): MarkedFixtureToken =
        MarkedFixtureToken(
            type = type.sourceName,
            raw = raw,
            text = text,
            tokens = if (
                type == MarkedTokenType.Paragraph ||
                type == MarkedTokenType.Strong ||
                type == MarkedTokenType.Emphasis
            ) {
                tokens.map { it.toFixtureToken() }
            } else {
                emptyList()
            },
        )
}
