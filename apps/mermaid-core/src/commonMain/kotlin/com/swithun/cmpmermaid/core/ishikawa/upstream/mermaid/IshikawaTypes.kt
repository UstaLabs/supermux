package com.swithun.cmpmermaid.core.ishikawa.upstream.mermaid

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/ishikawa/ishikawaTypes.ts -> IshikawaNode.
 */
internal data class IshikawaNode(
    val text: String,
    val children: MutableList<IshikawaNode> = mutableListOf(),
)
