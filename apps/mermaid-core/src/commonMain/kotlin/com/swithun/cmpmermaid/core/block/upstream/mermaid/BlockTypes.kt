package com.swithun.cmpmermaid.core.block.upstream.mermaid

import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.SceneTextSpan
import com.swithun.cmpmermaid.core.flowchart.FlowNodeStyle
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/block/blockTypes.ts.
 */
internal enum class BlockType {
    Na,
    ColumnSetting,
    Edge,
    Round,
    BlockArrow,
    Space,
    Square,
    Diamond,
    Hexagon,
    Odd,
    LeanRight,
    LeanLeft,
    Trapezoid,
    InverseTrapezoid,
    RectangleLeftInverseArrow,
    Circle,
    Ellipse,
    Stadium,
    Subroutine,
    Cylinder,
    Group,
    DoubleCircle,
    ClassDefinition,
    ApplyClass,
    ApplyStyles,
    Composite,
}

internal data class BlockText(
    val text: String,
    val spans: List<SceneTextSpan> = emptyList(),
)

internal data class BlockSize(
    var width: Float,
    var height: Float,
    var x: Float = 0f,
    var y: Float = 0f,
)

internal data class Block(
    var id: String,
    var label: String? = null,
    var type: BlockType = BlockType.Na,
    var children: MutableList<Block> = mutableListOf(),
    var start: String? = null,
    var end: String? = null,
    var arrowTypeEnd: String = "",
    var arrowTypeStart: String = "arrow_open",
    var thickness: String = "normal",
    var pattern: String = "solid",
    var width: Int? = null,
    var columns: Int? = null,
    var classes: MutableList<String> = mutableListOf(),
    var directions: List<String> = emptyList(),
    var css: String? = null,
    var styleClass: String? = null,
    var styles: MutableList<String> = mutableListOf(),
    var stylesStr: String? = null,
    var widthInColumns: Int? = null,
    var colorIndex: Int? = null,
    var size: BlockSize? = null,
    var renderedLabel: BlockText? = null,
    var style: FlowNodeStyle? = null,
    var shapeLayout: MermaidShapeLayout? = null,
    var renderedSize: BlockSize? = null,
)

internal data class BlockClassDefinition(
    val id: String,
    val textStyles: MutableList<String> = mutableListOf(),
    val styles: MutableList<String> = mutableListOf(),
)

internal data class BlockNodeValue(
    val id: String,
    val label: String? = null,
    val typeString: String? = null,
    val directions: List<String> = emptyList(),
    val type: BlockType = BlockType.Na,
)

internal data class BlockLinkValue(
    val edgeTypeString: String,
    val label: String,
)

internal data class BlockShapeValue(
    val typeString: String,
    val label: String,
    val directions: List<String> = emptyList(),
)

internal data class BlockBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal data class BlockEdgeGeometry(
    val points: List<ScenePoint>,
    val labelAnchor: ScenePoint,
)

internal data class BlockJisonProduction(
    val symbol: Int,
    val length: Int,
)

internal sealed interface BlockJisonCell {
    data class Goto(val state: Int) : BlockJisonCell

    data class Shift(val state: Int) : BlockJisonCell

    data class Reduce(val production: Int) : BlockJisonCell

    object Accept : BlockJisonCell
}

internal data class BlockJisonToken(
    val symbol: Int,
    val name: String,
    val text: String,
    val line: Int,
    val column: Int,
)

internal data class BlockMeasuredShape(
    val size: SceneSize,
    val textSize: SceneSize,
)
