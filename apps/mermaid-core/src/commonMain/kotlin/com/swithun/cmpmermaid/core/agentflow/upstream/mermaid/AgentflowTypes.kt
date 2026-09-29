package com.swithun.cmpmermaid.core.agentflow.upstream.mermaid

import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType

/**
 * Kotlin counterparts of Mermaid.js 12.0.0
 * packages/mermaid/src/diagrams/agentflow/types.ts and diagnostics.ts.
 */
internal data class AgentflowText(
    val text: String,
    val type: FlowLabelType,
)

internal enum class AgentflowEdgeSemantic {
    Sequence,
    Reference,
    Failure,
}

internal data class AgentflowVertex(
    val id: String,
    val domId: String,
    var text: String? = null,
    var labelType: FlowLabelType = FlowLabelType.Text,
    var type: String? = null,
    val styles: MutableList<String> = mutableListOf(),
    val classes: MutableList<String> = mutableListOf(),
    var direction: String? = null,
    val props: MutableMap<String, String> = linkedMapOf(),
    val metadata: MutableMap<String, Any?> = linkedMapOf(),
    var isConnector: Boolean = false,
    var link: String? = null,
    var linkTarget: String? = null,
    var tooltip: String? = null,
    var callbackName: String? = null,
    var callbackArgs: String? = null,
)

internal data class AgentflowEdge(
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
    var semantic: AgentflowEdgeSemantic? = null,
    val metadata: MutableMap<String, Any?> = linkedMapOf(),
)

internal data class AgentflowClass(
    val id: String,
    val styles: MutableList<String> = mutableListOf(),
    val textStyles: MutableList<String> = mutableListOf(),
)

internal data class AgentflowSubgraph(
    val id: String,
    var nodes: MutableList<String>,
    var title: String,
    val classes: MutableList<String> = mutableListOf(),
    var direction: String? = null,
    var labelType: FlowLabelType = FlowLabelType.Text,
    val metadata: MutableMap<String, Any?> = linkedMapOf(),
)

internal data class AgentflowLink(
    var type: String,
    var stroke: String,
    var length: Int? = null,
    var text: AgentflowText? = null,
    var id: String? = null,
    var semantic: AgentflowEdgeSemantic? = null,
)

internal sealed interface AgentflowDocumentItem {
    data class Nodes(val ids: List<String>) : AgentflowDocumentItem

    data class Subgraph(val id: String) : AgentflowDocumentItem

    data class Direction(val value: String) : AgentflowDocumentItem

    object Empty : AgentflowDocumentItem
}

internal data class AgentflowSourcePosition(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val startIndex: Int,
    val endIndex: Int,
)

internal enum class AgentflowStatementType {
    Vertex,
    Edge,
    Subgraph,
    Connector,
    Attachment,
}

internal data class AgentflowElementMapping(
    val id: String,
    var type: AgentflowStatementType,
    var position: AgentflowSourcePosition,
)

internal enum class AgentflowDiagnosticId {
    ShapeUnsupported,
    ShapeRemoved,
    EdgeOperatorUnsupported,
    ReferenceEdgeLabelRejected,
    ConnectorRefUnresolved,
    ConnectorRefNotAConnector,
    MetadataKeyMisapplied,
    DuplicateIdNode,
    ReservedSyntheticId,
    ContainmentViolation,
    EdgeSemanticContradiction,
    FlowNoInput,
}

internal enum class AgentflowDiagnosticSeverity {
    Warning,
    Error,
}

internal data class AgentflowDiagnostic(
    val id: AgentflowDiagnosticId,
    val severity: AgentflowDiagnosticSeverity,
    val message: String,
    val nodeId: String? = null,
    val edgeId: String? = null,
    val position: AgentflowSourcePosition? = null,
)

internal enum class AgentflowVertexKind {
    Tool,
    Action,
    Input,
    ReferenceDocument,
    Decision,
    Connector,
    Task,
}

internal data class AgentflowSemanticVertex(
    val id: String,
    val label: String?,
    val shape: String?,
    val kind: AgentflowVertexKind,
    val metadata: Map<String, Any?>,
)

internal data class AgentflowSemanticEdge(
    val start: String,
    val end: String,
    val id: String?,
    val label: String?,
    val type: String?,
    val stroke: String?,
    val semantic: AgentflowEdgeSemantic?,
    val length: Int?,
    val metadata: Map<String, Any?>,
)

internal data class AgentflowSemanticSubgraph(
    val id: String,
    val type: String = "flow",
    val title: String?,
    val nodes: List<String>,
    val metadata: Map<String, Any?>,
    val direction: String?,
)

internal data class AgentflowSemanticConnector(
    val id: String,
    val title: String?,
    val metadata: Map<String, Any?>,
)

internal data class AgentflowSemanticModel(
    val direction: String?,
    val vertices: List<AgentflowSemanticVertex>,
    val edges: List<AgentflowSemanticEdge>,
    val subgraphs: List<AgentflowSemanticSubgraph>,
    val connectors: List<AgentflowSemanticConnector>,
    val diagnostics: List<AgentflowDiagnostic>,
)

internal data class AgentflowMappingStats(
    val vertices: Int,
    val edges: Int,
    val subgraphs: Int,
    val connectors: Int,
    val attachments: Int,
    val totalElements: Int,
)

internal data class AgentflowJisonLocation(
    val firstLine: Int,
    val firstColumn: Int,
    val lastLine: Int,
    val lastColumn: Int,
    val startIndex: Int,
    val endIndex: Int,
)
