package com.swithun.cmpmermaid.core.ishikawa

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.ishikawa.upstream.mermaid.IshikawaDb
import com.swithun.cmpmermaid.core.ishikawa.upstream.mermaid.IshikawaParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IshikawaParserTest {
    @Test
    fun parsesUpstreamBasicHierarchy() {
        val root = parse(
            """
            ishikawa-beta
                Blurry Photo
                    Process
                        Out of focus
                    User
                        Shaky hands
            """.trimIndent(),
        ).getRoot()

        assertEquals("Blurry Photo", root?.text)
        assertEquals(listOf("Process", "User"), root?.children?.map { node -> node.text })
        assertEquals("Out of focus", root?.children?.get(0)?.children?.single()?.text)
        assertEquals("Shaky hands", root?.children?.get(1)?.children?.single()?.text)
    }

    @Test
    fun parsesUpstreamUnindentedRootWithNestedCauses() {
        val root = parse(
            """
            ishikawa-beta
            Problem
            Cause A
              Subcause A1
            Cause B
            """.trimIndent(),
        ).getRoot()

        assertEquals("Problem", root?.text)
        assertEquals(listOf("Cause A", "Cause B"), root?.children?.map { node -> node.text })
        assertEquals("Subcause A1", root?.children?.first()?.children?.single()?.text)
    }

    @Test
    fun parsesUpstreamEffectIndentedMoreThanCauses() {
        val root = parse(
            """
            ishikawa-beta
                Problem
            Cause A
              Subcause A1
            Cause B
            """.trimIndent(),
        ).getRoot()

        assertEquals("Problem", root?.text)
        assertEquals(listOf("Cause A", "Cause B"), root?.children?.map { node -> node.text })
        assertEquals("Subcause A1", root?.children?.first()?.children?.single()?.text)
    }

    @Test
    fun acceptsBothHeadersBlankLinesAndFullLineComments() {
        listOf("ishikawa", "ishikawa-beta").forEach { header ->
            val root = parse(
                """
                %% before

                $header
                %% after header
                Effect

                    Cause
                """.trimIndent(),
            ).getRoot()

            assertEquals("Effect", root?.text)
            assertEquals("Cause", root?.children?.single()?.text)
        }
    }

    @Test
    fun treatsHeaderRemainderCommentAsWhitespace() {
        val root = parse(
            """
            ishikawa %% header comment
            Effect
              Cause
            """.trimIndent(),
        ).getRoot()

        assertEquals("Effect", root?.text)
        assertEquals("Cause", root?.children?.single()?.text)
    }

    @Test
    fun returnsStructuredErrorsForMissingEffectAndBadHeader() {
        val missingEffect = assertIs<GMResult.Err<MermaidError>>(
            parser(lineOffset = 4).parse("ishikawa-beta\n"),
        ).error
        assertEquals(5, assertIs<MermaidError.Parse>(missingEffect).line)

        val badHeader = assertIs<GMResult.Err<MermaidError>>(
            parser().parse("ishikawaish\nEffect"),
        ).error
        assertEquals(1, assertIs<MermaidError.Parse>(badHeader).line)
    }

    @Test
    fun enforcesEdgeLimitWithoutThrowing() {
        val result = parser(maxEdges = 1).parse(
            """
            ishikawa
            Effect
              Cause A
              Cause B
            """.trimIndent(),
        )

        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        val limit = assertIs<MermaidError.ResourceLimit>(error)
        assertEquals("Ishikawa edges", limit.resource)
        assertEquals(2, limit.actual)
        assertEquals(1, limit.maximum)
    }

    private fun parse(source: String): IshikawaDb =
        assertIs<GMResult.Ok<IshikawaDb>>(parser().parse(source)).value

    private fun parser(
        lineOffset: Int = 0,
        maxEdges: Int = 500,
    ): IshikawaParser = IshikawaParser(
        options = MermaidRenderOptions(maxEdges = maxEdges),
        diagramTitle = null,
        lineOffset = lineOffset,
    )
}
