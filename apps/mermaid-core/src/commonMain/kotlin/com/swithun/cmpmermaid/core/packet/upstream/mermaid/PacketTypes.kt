package com.swithun.cmpmermaid.core.packet.upstream.mermaid

internal data class PacketBlock(
    val start: Long?,
    val end: Long?,
    val bits: Long?,
    val label: String,
    val line: Int,
    val column: Int,
)

internal data class ResolvedPacketBlock(
    val start: Long,
    val end: Long,
    val bits: Long,
    val label: String,
)

internal typealias PacketWord = List<ResolvedPacketBlock>
