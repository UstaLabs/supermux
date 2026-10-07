package com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid

internal data class ErEntity(
    val sourceName: String,
    val id: String,
    val label: String,
    var alias: String = "",
    val attributes: MutableList<ErAttribute> = mutableListOf(),
    val classes: MutableList<String> = mutableListOf("default"),
    val styles: MutableList<String> = mutableListOf(),
    var colorIndex: Int = 0,
)

internal data class ErAttribute(
    val type: String,
    val name: String,
    val keys: List<ErAttributeKey> = emptyList(),
    val comment: String = "",
)

internal enum class ErAttributeKey {
    Primary,
    Foreign,
    Unique,
}

internal data class ErRelationship(
    val entityA: String,
    val roleA: String,
    val entityB: String,
    val specification: ErRelationshipSpec,
)

internal data class ErRelationshipSpec(
    val cardA: ErCardinality,
    val relationType: ErIdentification,
    val cardB: ErCardinality,
)

internal enum class ErCardinality {
    ZeroOrOne,
    ZeroOrMore,
    OneOrMore,
    OnlyOne,
    MdParent,
}

internal enum class ErIdentification {
    NonIdentifying,
    Identifying,
}

internal data class ErStyleClass(
    val id: String,
    val styles: MutableList<String> = mutableListOf(),
    val textStyles: MutableList<String> = mutableListOf(),
)

internal data class ErSubGraph(
    val id: String,
    val nodes: MutableList<String>,
    val title: String,
    val classes: MutableList<String> = mutableListOf(),
    val styles: MutableList<String> = mutableListOf(),
    val direction: String? = null,
)

internal sealed interface ErDocumentEntry

internal data class ErEntityReference(
    val id: String,
) : ErDocumentEntry

internal data class ErSubGraphReference(
    val id: String,
) : ErDocumentEntry

internal data class ErDirectionStatement(
    val value: String,
) : ErDocumentEntry

internal data class ErSubGraphHeader(
    val id: String,
    val text: String,
)
