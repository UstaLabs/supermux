package com.swithun.cmpmermaid.core.statediagram.upstream.mermaid

internal sealed interface StateStatement

internal data class StateNodeStatement(
    var id: String,
    var type: StateNodeType = StateNodeType.Default,
    var descriptions: MutableList<String> = mutableListOf(),
    var document: MutableList<StateStatement>? = null,
    var note: StateNote? = null,
    var start: Boolean? = null,
    val classes: MutableList<String> = mutableListOf(),
    val styles: MutableList<String> = mutableListOf(),
    val textStyles: MutableList<String> = mutableListOf(),
) : StateStatement

internal enum class StateNodeType {
    Default,
    Fork,
    Join,
    Choice,
    Divider,
    Start,
    End,
}

internal data class StateRelationStatement(
    val state1: StateNodeStatement,
    val state2: StateNodeStatement,
    val description: String? = null,
) : StateStatement

internal data class StateClassDefStatement(
    val id: String,
    val classes: String,
) : StateStatement

internal data class StateStyleStatement(
    val id: String,
    val styleClass: String,
) : StateStatement

internal data class StateApplyClassStatement(
    val id: String,
    val styleClass: String,
) : StateStatement

internal data class StateDirectionStatement(
    var value: String,
) : StateStatement

internal data class StateClickStatement(
    val state: StateNodeStatement,
    val url: String,
    val tooltip: String,
) : StateStatement

internal data class StateIgnoredStatement(
    val source: String,
) : StateStatement

internal data class StateNote(
    val position: String,
    val text: String,
)

internal data class StateDiagramEdge(
    val id1: String,
    val id2: String,
    val relationTitle: String? = null,
)

internal data class StateStyleClass(
    val id: String,
    val styles: MutableList<String> = mutableListOf(),
    val textStyles: MutableList<String> = mutableListOf(),
)

internal data class StateLink(
    val url: String,
    val tooltip: String,
)
