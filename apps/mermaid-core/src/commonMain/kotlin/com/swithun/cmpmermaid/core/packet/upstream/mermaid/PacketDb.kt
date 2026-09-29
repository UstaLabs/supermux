package com.swithun.cmpmermaid.core.packet.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPacketOptions
import com.swithun.cmpmermaid.core.MermaidPreprocessor

/**
 * Kotlin translation of Mermaid 12.0.0 packet/db.ts and packet/parser.ts -> populate.
 */
internal class PacketDb(
    val config: MermaidPacketOptions,
    diagramTitle: String?,
) {
    private val packet = mutableListOf<PacketWord>()

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun populate(blocks: List<PacketBlock>): GMResult<Unit, MermaidError> {
        var lastBit = -1L
        var word = mutableListOf<ResolvedPacketBlock>()
        var row = 1L
        val bitsPerRow = config.bitsPerRow.toLong()

        blocks.forEach { sourceBlock ->
            var start = sourceBlock.start
            var end = sourceBlock.end
            var bits = sourceBlock.bits
            if (start != null && end != null && end < start) {
                return parseError(
                    "Packet block $start - $end is invalid. End must be greater than start.",
                    sourceBlock,
                )
            }
            start = start ?: lastBit + 1L
            if (start != lastBit + 1L) {
                return parseError(
                    "Packet block $start - ${end ?: start} is not contiguous. " +
                        "It should start from ${lastBit + 1L}.",
                    sourceBlock,
                )
            }
            if (bits == 0L) {
                return parseError(
                    "Packet block $start is invalid. Cannot have a zero bit field.",
                    sourceBlock,
                )
            }
            end = end ?: checkedEnd(start, bits ?: 1L)
                ?: return resourceError(
                    resource = "Packet bit index",
                    actual = Int.MAX_VALUE,
                    maximum = Int.MAX_VALUE,
                    message = "Packet bit index exceeds the supported integer range",
                )
            bits = bits ?: end - start + 1L
            lastBit = end

            var hasRemainingBlock = true
            while (
                word.size.toLong() <= bitsPerRow + 1L &&
                packet.size < MAX_PACKET_SIZE
            ) {
                val (block, nextBlock) = getNextFittingBlock(
                    block = PacketBlock(
                        start = start,
                        end = end,
                        bits = bits,
                        label = sourceBlock.label,
                        line = sourceBlock.line,
                        column = sourceBlock.column,
                    ),
                    row = row,
                    bitsPerRow = bitsPerRow,
                )
                word += block
                if (block.end + 1L == row * bitsPerRow) {
                    pushWord(word)
                    word = mutableListOf()
                    row += 1L
                }
                if (nextBlock == null) {
                    hasRemainingBlock = false
                    break
                }
                start = nextBlock.start
                end = nextBlock.end
                bits = nextBlock.bits
            }
            if (hasRemainingBlock && packet.size >= MAX_PACKET_SIZE) {
                // Mermaid 12.0.0 packet/parser.ts caps generated rows at 10,000.
                // The Kotlin API reports the truncation instead of returning a partial diagram.
                return resourceError(
                    resource = "Packet rows",
                    actual = MAX_PACKET_SIZE + 1,
                    maximum = MAX_PACKET_SIZE,
                )
            }
        }
        pushWord(word)
        return GMResult.Ok(Unit)
    }

    fun getPacket(): List<PacketWord> = packet

    fun setDiagramTitle(value: String) {
        diagramTitle = decode(value)
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = decode(value)
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = decode(value)
    }

    private fun pushWord(word: PacketWord) {
        if (word.isNotEmpty()) {
            packet += word.toList()
        }
    }

    private fun getNextFittingBlock(
        block: PacketBlock,
        row: Long,
        bitsPerRow: Long,
    ): Pair<ResolvedPacketBlock, PacketBlock?> {
        val start = block.start ?: 0L
        val end = block.end ?: start
        val bits = block.bits ?: end - start + 1L
        if (end + 1L <= row * bitsPerRow) {
            return ResolvedPacketBlock(start, end, bits, block.label) to null
        }

        val rowEnd = row * bitsPerRow - 1L
        val rowStart = row * bitsPerRow
        return ResolvedPacketBlock(
            start = start,
            end = rowEnd,
            label = block.label,
            // Preserve Mermaid 12.0.0's parser.ts split-block field value.
            bits = rowEnd - start,
        ) to PacketBlock(
            start = rowStart,
            end = end,
            label = block.label,
            bits = end - rowStart,
            line = block.line,
            column = block.column,
        )
    }

    private fun checkedEnd(
        start: Long,
        bits: Long,
    ): Long? {
        if (bits < 0L || start > Long.MAX_VALUE - bits + 1L) {
            return null
        }
        return start + bits - 1L
    }

    private fun decode(value: String): String = MermaidPreprocessor.decodeEntities(value)

    private fun parseError(
        message: String,
        block: PacketBlock,
    ): GMResult.Err<MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = block.line,
            column = block.column,
            message = message,
        ),
    )

    private fun resourceError(
        resource: String,
        actual: Int,
        maximum: Int,
        message: String = "Mermaid $resource limit exceeded: $actual > $maximum",
    ): GMResult.Err<MermaidError> =
        GMResult.Err(
            MermaidError.ResourceLimit(
                resource = resource,
                actual = actual,
                maximum = maximum,
                message = message,
            ),
        )

    private companion object {
        const val MAX_PACKET_SIZE = 10_000
    }
}
