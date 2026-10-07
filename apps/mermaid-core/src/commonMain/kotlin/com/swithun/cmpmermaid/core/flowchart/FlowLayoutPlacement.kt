package com.swithun.cmpmermaid.core.flowchart

import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect

/**
 * Platform-neutral output shared by Mermaid's translated layout adapters.
 */
internal data class FlowLayoutPlacement(
    val nodeBounds: Map<String, SceneRect>,
    val subgraphBounds: Map<String, SceneRect>,
    val edges: Map<Int, FlowRoutedEdge>,
)

internal data class FlowRoutedEdge(
    val points: List<ScenePoint>,
    val labelAnchor: ScenePoint,
    val curveOverride: String? = null,
)
