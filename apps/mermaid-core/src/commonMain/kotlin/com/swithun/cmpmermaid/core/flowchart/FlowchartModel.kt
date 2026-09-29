package com.swithun.cmpmermaid.core.flowchart

import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextSpan
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType

internal enum class FlowDirection {
    TopToBottom,
    BottomToTop,
    LeftToRight,
    RightToLeft,
}

internal data class FlowNodeStyle(
    val fill: SceneColor? = null,
    val stroke: SceneColor? = null,
    val text: SceneColor? = null,
    val labelBackground: SceneColor? = null,
    val strokeWidth: Float? = null,
    val strokePattern: SceneStrokePattern? = null,
    val dashIntervals: List<Float> = emptyList(),
    val fontSize: Float? = null,
    val fontSizeScale: Float? = null,
    val fontFamily: String? = null,
    val fontWeight: SceneTextWeight? = null,
    val italic: Boolean? = null,
    val underline: Boolean = false,
    val lineThrough: Boolean = false,
    val lineHeightMultiplier: Float? = null,
    val lineHeightPixels: Float? = null,
    val textAlignment: SceneTextAlignment? = null,
    val animated: Boolean = false,
    val animationDurationMillis: Int? = null,
)

internal data class FlowNode(
    val id: String,
    val label: String,
    val labelSpans: List<SceneTextSpan>,
    val labelType: FlowLabelType,
    val shape: SceneShapeKind,
    val padding: Float,
    val minWidth: Float?,
    val look: String,
    val inlineStyle: FlowNodeStyle? = null,
    val metadata: Map<String, String> = emptyMap(),
    val link: String? = null,
    val linkTarget: String? = null,
    val tooltip: String? = null,
    val callbackName: String? = null,
    val callbackArgs: String? = null,
    val icon: String? = null,
    val position: String? = null,
    val image: String? = null,
    val assetWidth: Float? = null,
    val assetHeight: Float? = null,
    val constraint: String? = null,
    val colorIndex: Int? = null,
)

internal data class FlowEdge(
    val id: String,
    val from: String,
    val to: String,
    val label: String? = null,
    val labelSpans: List<SceneTextSpan> = emptyList(),
    val pattern: SceneStrokePattern = SceneStrokePattern.Solid,
    val arrowStart: SceneArrowHead = SceneArrowHead.None,
    val arrowEnd: SceneArrowHead = SceneArrowHead.Triangle,
    val thickness: Float? = null,
    val minimumLength: Int = 1,
    val invisible: Boolean = false,
    val inlineStyle: FlowNodeStyle? = null,
    val animated: Boolean = false,
    val animationDurationMillis: Int? = null,
    val curve: String? = null,
    val look: String,
)

internal data class FlowSubgraph(
    val id: String,
    val label: String,
    val labelSpans: List<SceneTextSpan>,
    val nodeIds: Set<String>,
    val direction: FlowDirection? = null,
    val parentId: String? = null,
    val padding: Float,
    val look: String,
    val cornerRadius: Float = 0f,
    val inlineStyle: FlowNodeStyle? = null,
    val metadata: Map<String, String> = emptyMap(),
    val colorIndex: Int? = null,
)

internal data class FlowchartDocument(
    val direction: FlowDirection,
    val nodes: Map<String, FlowNode>,
    val edges: List<FlowEdge>,
    val subgraphs: List<FlowSubgraph>,
    val title: String? = null,
    val accessibilityTitle: String? = null,
    val accessibilityDescription: String? = null,
)
