package com.swithun.cmpmermaid.core.packet

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPacketOptions
import com.swithun.cmpmermaid.core.packet.upstream.mermaid.PacketDb
import com.swithun.cmpmermaid.core.packet.upstream.mermaid.PacketParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PacketParserTest {
    @Test
    fun parsesBothHeadersAndAnEmptyPacket() {
        assertEquals(emptyList(), parse("packet").getPacket())
        assertEquals(emptyList(), parse("packet-beta").getPacket())
    }

    @Test
    fun parsesExplicitSingleAndCountedBlocks() {
        val db = parse(
            """
            packet
              0-10: "range"
              11: 'single'
              +8: "byte"
            """.trimIndent(),
        )

        assertEquals(1, db.getPacket().size)
        assertEquals(
            listOf(
                listOf(0L, 10L, 11L),
                listOf(11L, 11L, 1L),
                listOf(12L, 19L, 8L),
            ),
            db.getPacket().single().map { block ->
                listOf(block.start, block.end, block.bits)
            },
        )
        assertEquals(listOf("range", "single", "byte"), db.getPacket().single().map {
            block -> block.label
        })
    }

    @Test
    fun preservesUpstreamSplitBlockValuesAcrossRows() {
        val db = parse(
            """
            packet
              0-10: "test"
              11-90: "multiple"
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                listOf(0L, 10L, 11L),
                listOf(11L, 31L, 20L),
                listOf(32L, 63L, 31L),
                listOf(64L, 90L, 26L),
            ),
            db.getPacket().flatten().map { block ->
                listOf(block.start, block.end, block.bits)
            },
        )
    }

    @Test
    fun parsesMetadataCommentsAndEscapedLabels() {
        val db = parse(
            """
            packet
              title Packet   diagram %% comment
              accTitle: Packet title
              accDescr {
                First   line
                Second line
              }
              0: "line\nquote\"slash\\"
            """.trimIndent(),
            diagramTitle = "Frontmatter title",
        )

        assertEquals("Packet diagram", db.diagramTitle)
        assertEquals("Packet title", db.accessibilityTitle)
        assertEquals("First line\nSecond line", db.accessibilityDescription)
        assertEquals("line\nquote\"slash\\", db.getPacket().single().single().label)
    }

    @Test
    fun returnsStructuredErrorsForInvalidPacketBlocks() {
        val sources = listOf(
            "packet\n  0-16: \"test\"\n  18-20: \"gap\"",
            "packet\n  25-20: \"reverse\"",
            "packet\n  +0: \"zero\"",
            "packet\n  0-: \"missing\"",
            "packet\n  0: unquoted",
            "packet\n  0: \"unterminated",
        )

        sources.forEach { source ->
            val error = assertIs<GMResult.Err<MermaidError>>(parser().parse(source)).error
            assertIs<MermaidError.Parse>(error)
        }
    }

    @Test
    fun reportsFrontmatterAdjustedSourceLine() {
        val error = assertIs<GMResult.Err<MermaidError>>(
            parser(lineOffset = 5).parse("packet\n  +0: \"zero\""),
        ).error
        val parse = assertIs<MermaidError.Parse>(error)

        assertEquals(7, parse.line)
        assertEquals(3, parse.column)
    }

    @Test
    fun reportsTheUpstreamPacketRowLimitWithoutReturningPartialData() {
        val result = parser(
            config = MermaidPacketOptions(bitsPerRow = 1),
        ).parse("packet\n  +10001: \"oversized\"")
        val resource = assertIs<MermaidError.ResourceLimit>(
            assertIs<GMResult.Err<MermaidError>>(result).error,
        )

        assertEquals("Packet rows", resource.resource)
        assertEquals(10_001, resource.actual)
        assertEquals(10_000, resource.maximum)
    }

    private fun parse(
        source: String,
        diagramTitle: String? = null,
    ): PacketDb = assertIs<GMResult.Ok<PacketDb>>(
        parser(diagramTitle = diagramTitle).parse(source),
    ).value

    private fun parser(
        config: MermaidPacketOptions = MermaidPacketOptions(),
        diagramTitle: String? = null,
        lineOffset: Int = 0,
    ): PacketParser = PacketParser(
        config = config,
        diagramTitle = diagramTitle,
        lineOffset = lineOffset,
    )
}
