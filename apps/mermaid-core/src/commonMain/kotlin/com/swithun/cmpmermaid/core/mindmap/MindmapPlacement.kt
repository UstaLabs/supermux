package com.swithun.cmpmermaid.core.mindmap

import com.swithun.cmpmermaid.core.ScenePoint

/**
 * Platform-neutral result shared by Mermaid's translated Mindmap layouts.
 */
internal data class MindmapPlacement(
    val nodeCenters: Map<Int, ScenePoint>,
    val edgePoints: Map<String, List<ScenePoint>>,
)
