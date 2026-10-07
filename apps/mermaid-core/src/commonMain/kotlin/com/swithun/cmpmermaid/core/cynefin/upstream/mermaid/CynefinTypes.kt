package com.swithun.cmpmermaid.core.cynefin.upstream.mermaid

internal enum class CynefinDomainName(
    val sourceName: String,
    val displayName: String,
) {
    Complex("complex", "Complex"),
    Complicated("complicated", "Complicated"),
    Clear("clear", "Clear"),
    Chaotic("chaotic", "Chaotic"),
    Confusion("confusion", "Confusion"),
    ;

    companion object {
        fun fromSourceName(source: String): CynefinDomainName? =
            values().firstOrNull { domain -> domain.sourceName == source }
    }
}

internal data class CynefinItem(
    val label: String,
)

internal data class CynefinDomain(
    val name: CynefinDomainName,
    val items: List<CynefinItem>,
)

internal data class CynefinTransition(
    val from: CynefinDomainName,
    val to: CynefinDomainName,
    val label: String? = null,
)

internal data class CynefinAstDomainBlock(
    val domain: CynefinDomainName,
    val items: List<String>,
)

internal data class CynefinAstTransition(
    val from: CynefinDomainName,
    val to: CynefinDomainName,
    val label: String?,
)
