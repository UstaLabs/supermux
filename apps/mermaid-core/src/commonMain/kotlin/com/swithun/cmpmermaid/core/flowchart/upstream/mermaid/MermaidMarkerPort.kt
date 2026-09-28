package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.SceneArrowHead

/**
 * Kotlin translation of Mermaid 12.0.0's marker offset tables in
 * rendering-util/rendering-elements/markers.js and utils/lineWithOffset.ts.
 */
internal object MermaidMarkerPort {
    fun pathOffset(arrow: SceneArrowHead): Float = when (arrow) {
        SceneArrowHead.None -> 0f
        SceneArrowHead.Triangle -> 4f
        SceneArrowHead.Circle,
        SceneArrowHead.Cross,
        SceneArrowHead.Open,
        SceneArrowHead.Async,
        SceneArrowHead.SequenceCross,
        SceneArrowHead.HalfTriangleTop,
        SceneArrowHead.HalfTriangleBottom,
        SceneArrowHead.HalfOpenTop,
        SceneArrowHead.HalfOpenBottom,
        -> 0f
        SceneArrowHead.ClassAggregation,
        SceneArrowHead.ClassExtension,
        SceneArrowHead.ClassComposition,
        -> 17.25f
        SceneArrowHead.ClassDependency -> 6f
        SceneArrowHead.ClassLollipop -> 13.5f
        SceneArrowHead.ErOnlyOne,
        SceneArrowHead.ErZeroOrOne,
        SceneArrowHead.ErOneOrMore,
        SceneArrowHead.ErZeroOrMore,
        SceneArrowHead.RequirementArrow,
        SceneArrowHead.RequirementContains,
        -> 0f
    }

    fun neoGapOffset(arrow: SceneArrowHead): Float = when (arrow) {
        SceneArrowHead.None -> 0f
        SceneArrowHead.Triangle -> 4f
        SceneArrowHead.Circle,
        SceneArrowHead.Cross,
        -> 12.5f
        SceneArrowHead.Open,
        SceneArrowHead.Async,
        SceneArrowHead.SequenceCross,
        SceneArrowHead.HalfTriangleTop,
        SceneArrowHead.HalfTriangleBottom,
        SceneArrowHead.HalfOpenTop,
        SceneArrowHead.HalfOpenBottom,
        SceneArrowHead.ClassAggregation,
        SceneArrowHead.ClassExtension,
        SceneArrowHead.ClassComposition,
        SceneArrowHead.ClassDependency,
        SceneArrowHead.ClassLollipop,
        SceneArrowHead.ErOnlyOne,
        SceneArrowHead.ErZeroOrOne,
        SceneArrowHead.ErOneOrMore,
        SceneArrowHead.ErZeroOrMore,
        SceneArrowHead.RequirementArrow,
        SceneArrowHead.RequirementContains,
        -> 0f
    }

    fun terminalSegmentOffset(arrow: SceneArrowHead): Float =
        maxOf(pathOffset(arrow), neoGapOffset(arrow))
}
