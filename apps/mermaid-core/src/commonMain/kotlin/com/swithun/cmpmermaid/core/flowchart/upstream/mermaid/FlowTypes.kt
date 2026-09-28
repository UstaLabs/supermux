package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

/**
 * Kotlin counterparts of packages/mermaid/src/diagrams/flowchart/types.ts.
 */
internal enum class FlowLabelType {
    Text,
    String,
    Markdown,
}

internal data class MermaidFlowText(
    val text: String,
    val type: FlowLabelType,
)

internal data class MermaidFlowVertex(
    val id: String,
    val domId: String,
    var text: String? = null,
    var labelType: FlowLabelType = FlowLabelType.Text,
    var type: String? = null,
    val styles: MutableList<String> = mutableListOf(),
    val classes: MutableList<String> = mutableListOf(),
    var dir: String? = null,
    val props: MutableMap<String, String> = linkedMapOf(),
    var link: String? = null,
    var linkTarget: String? = null,
    var tooltip: String? = null,
    var callbackName: String? = null,
    var callbackArgs: String? = null,
    var icon: String? = null,
    var form: String? = null,
    var position: String? = null,
    var image: String? = null,
    var assetWidth: Float? = null,
    var assetHeight: Float? = null,
    var constraint: String? = null,
)

internal data class MermaidFlowEdge(
    val start: String,
    val end: String,
    var type: String? = null,
    var text: String = "",
    var labelType: FlowLabelType = FlowLabelType.Text,
    var stroke: String? = null,
    var length: Int? = null,
    val classes: MutableList<String> = mutableListOf(),
    var id: String? = null,
    var isUserDefinedId: Boolean = false,
    var interpolate: String? = null,
    var style: MutableList<String>? = null,
    var animation: String? = null,
    var animate: Boolean? = null,
)

internal data class MermaidFlowClass(
    val id: String,
    val styles: MutableList<String> = mutableListOf(),
    val textStyles: MutableList<String> = mutableListOf(),
)

internal data class MermaidFlowSubgraph(
    val id: String,
    var nodes: MutableList<String>,
    val title: String,
    val classes: MutableList<String> = mutableListOf(),
    val direction: String? = null,
    val labelType: FlowLabelType = FlowLabelType.Text,
    val metadata: MutableMap<String, String> = linkedMapOf(),
)

internal data class MermaidFlowLink(
    var type: String,
    var stroke: String,
    var length: Int? = null,
    var text: MermaidFlowText? = null,
    var id: String? = null,
)

internal data class MermaidFlowLayoutNode(
    val id: String,
    val label: String?,
    val labelType: FlowLabelType,
    val parentId: String?,
    val padding: Float,
    val minWidth: Float?,
    val look: String,
    val cssStyles: List<String>,
    val cssCompiledStyles: List<String>,
    val cssClasses: String,
    val shape: String,
    val dir: String?,
    val isGroup: Boolean,
    val metadata: Map<String, String> = emptyMap(),
    val colorIndex: Int? = null,
    val link: String? = null,
    val linkTarget: String? = null,
    val tooltip: String? = null,
    val callbackName: String? = null,
    val callbackArgs: String? = null,
    val icon: String? = null,
    val form: String? = null,
    val position: String? = null,
    val image: String? = null,
    val assetWidth: Float? = null,
    val assetHeight: Float? = null,
    val constraint: String? = null,
)

internal data class MermaidFlowLayoutEdge(
    val id: String,
    val isUserDefinedId: Boolean,
    val start: String,
    val end: String,
    val type: String,
    val label: String,
    val labelType: FlowLabelType,
    val thickness: String?,
    val minimumLength: Int?,
    val classes: String,
    val arrowTypeStart: String,
    val arrowTypeEnd: String,
    val cssCompiledStyles: List<String>,
    val styles: List<String>,
    val pattern: String?,
    val animate: Boolean?,
    val animation: String?,
    val curve: String?,
    val look: String,
)

internal data class MermaidFlowLayoutData(
    val nodes: List<MermaidFlowLayoutNode>,
    val edges: List<MermaidFlowLayoutEdge>,
)

internal sealed interface MermaidFlowDocumentItem {
    data class Nodes(val ids: List<String>) : MermaidFlowDocumentItem

    data class Subgraph(val id: String) : MermaidFlowDocumentItem

    data class Direction(val value: String) : MermaidFlowDocumentItem

    object Empty : MermaidFlowDocumentItem
}
