package com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid

import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePoint

/**
 * Kotlin counterparts of Mermaid.js 12.0.0:
 * packages/parser/src/language/eventmodeling/event-modeling.langium and
 * packages/mermaid/src/diagrams/eventmodeling/types.ts.
 */
internal enum class EventModelingFrameKind {
    TimeFrame,
    ResetFrame,
}

internal data class EventModelingFrame(
    val kind: EventModelingFrameKind,
    val name: String,
    val modelEntityType: String,
    val entityIdentifier: String,
    val sourceFrameNames: List<String>,
    val dataReference: String?,
    val dataType: String?,
    val dataInlineValue: String?,
)

internal data class EventModelingDataEntity(
    val name: String,
    val dataType: String?,
    val dataBlockValue: String,
)

internal data class EventModelingNoteEntity(
    val sourceFrameName: String,
    val dataType: String?,
    val dataBlockValue: String,
)

internal data class EventModelingGwtStatement(
    val modelEntityType: String,
    val entityIdentifier: String,
)

internal data class EventModelingGwt(
    val sourceFrameName: String,
    val givenStatements: List<EventModelingGwtStatement>,
    val whenStatements: List<EventModelingGwtStatement>,
    val thenStatements: List<EventModelingGwtStatement>,
)

internal data class EventModelingAst(
    val frames: List<EventModelingFrame>,
    val modelEntities: List<String>,
    val dataEntities: List<EventModelingDataEntity>,
    val noteEntities: List<EventModelingNoteEntity>,
    val gwtEntities: List<EventModelingGwt>,
    val title: String?,
    val accessibilityTitle: String?,
    val accessibilityDescription: String?,
)

internal data class EventModelingDimension(
    val width: Float,
    val height: Float,
)

internal data class EventModelingVisualProps(
    val fill: SceneColor,
    val stroke: SceneColor,
)

internal data class EventModelingTextProps(
    val displayText: String,
    val nameLength: Int,
    val dataStart: Int?,
    val width: Float,
    val height: Float,
)

internal data class EventModelingSwimlaneProps(
    val index: Int,
    val label: String,
    val namespace: String? = null,
)

internal data class EventModelingSwimlane(
    val index: Int,
    val label: String,
    val namespace: String? = null,
    var r: Float,
    var y: Float,
    var height: Float,
    var maxHeight: Float,
)

internal data class EventModelingBox(
    val r: Float,
    val x: Float,
    val y: Float,
    val dimension: EventModelingDimension,
    val leftSibling: Boolean,
    val swimlane: EventModelingSwimlane,
    val visual: EventModelingVisualProps,
    val text: EventModelingTextProps,
    val frame: EventModelingFrame,
    val index: Int,
)

internal data class EventModelingRelation(
    val visual: EventModelingVisualProps,
    val source: ScenePoint,
    val target: ScenePoint,
    val sourceBox: EventModelingBox,
    val targetBox: EventModelingBox,
)

internal data class EventModelingContext(
    val boxes: List<EventModelingBox> = emptyList(),
    val swimlanes: Map<Int, EventModelingSwimlane> = emptyMap(),
    val relations: List<EventModelingRelation> = emptyList(),
    val previousFrame: EventModelingFrame? = null,
    val previousSwimlaneNumber: Int? = null,
    val maxR: Float = 0f,
    val sortedSwimlanesArray: List<EventModelingSwimlane> = emptyList(),
)
