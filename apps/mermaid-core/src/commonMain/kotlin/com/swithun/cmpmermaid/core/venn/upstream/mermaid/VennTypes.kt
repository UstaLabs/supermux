package com.swithun.cmpmermaid.core.venn.upstream.mermaid

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/venn/vennTypes.ts.
 */
internal data class VennData(
    val sets: List<String>,
    val size: Double,
    val label: String? = null,
)

internal data class VennTextData(
    val sets: List<String>,
    val id: String,
    val label: String? = null,
)

internal data class VennStyleData(
    val targets: List<String>,
    val styles: Map<String, String>,
)
