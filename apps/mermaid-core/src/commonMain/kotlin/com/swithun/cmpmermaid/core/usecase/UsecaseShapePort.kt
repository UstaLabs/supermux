package com.swithun.cmpmermaid.core.usecase

import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneShapeGeometry
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneShapePaint
import com.swithun.cmpmermaid.core.SceneShapePath
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.SceneTextSpan
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseActorType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mermaid.js 12.0.0:
 * rendering-util/rendering-elements/shapes/usecaseActor*.ts,
 * usecaseEllipse.ts, usecaseBusiness.ts, and usecaseJsonTable.ts.
 */
internal object UsecaseShapePort {
    fun actor(
        label: UsecaseMeasuredText,
        stereotype: UsecaseMeasuredText?,
        type: UsecaseActorType,
        business: Boolean,
        icon: String?,
    ): UsecaseNodeVisual {
        val stereotypeHeight = stereotype?.size?.height ?: 0f
        val contentHeight = ACTOR_FIGURE_HEIGHT +
            ACTOR_LABEL_GAP +
            label.size.height +
            if (stereotype == null) 0f else stereotypeHeight + STEREOTYPE_LABEL_GAP
        val width = maxOf(
            ACTOR_FIGURE_WIDTH,
            label.size.width,
            stereotype?.size?.width ?: 0f,
        ) + ACTOR_PADDING * 2f
        val height = contentHeight + ACTOR_PADDING * 2f
        val contentTop = -contentHeight / 2f
        val glyphCenterY = contentTop + ACTOR_FIGURE_HEIGHT / 2f
        val paths = mutableListOf<SceneShapePath>()
        when (type) {
            UsecaseActorType.Normal -> {
                paths += closed(ellipse(0f, glyphCenterY - 24f, 12f, 12f))
                paths += open(
                    ScenePoint(0f, glyphCenterY - 12f),
                    ScenePoint(0f, glyphCenterY + 8f),
                )
                paths += open(
                    ScenePoint(-17f, glyphCenterY - 5f),
                    ScenePoint(17f, glyphCenterY - 5f),
                )
                paths += open(
                    ScenePoint(0f, glyphCenterY + 8f),
                    ScenePoint(-15f, glyphCenterY + 28f),
                )
                paths += open(
                    ScenePoint(0f, glyphCenterY + 8f),
                    ScenePoint(15f, glyphCenterY + 28f),
                )
            }
            UsecaseActorType.Hollow -> {
                paths += closed(
                    ellipse(0f, glyphCenterY - 23f, 9f, 9f),
                    fill = SceneShapePaint.None,
                )
                paths += closed(
                    listOf(
                        ScenePoint(-22f, glyphCenterY - 10f),
                        ScenePoint(22f, glyphCenterY - 10f),
                        ScenePoint(22f, glyphCenterY),
                        ScenePoint(6f, glyphCenterY),
                        ScenePoint(22f, glyphCenterY + 17f),
                        ScenePoint(13f, glyphCenterY + 28f),
                        ScenePoint(0f, glyphCenterY + 13f),
                        ScenePoint(-13f, glyphCenterY + 28f),
                        ScenePoint(-22f, glyphCenterY + 17f),
                        ScenePoint(-6f, glyphCenterY),
                        ScenePoint(-22f, glyphCenterY),
                    ),
                    fill = SceneShapePaint.None,
                )
            }
            UsecaseActorType.Awesome -> {
                paths += closed(ellipse(0f, glyphCenterY - 21f, 13f, 13f))
                paths += closed(
                    roundedTorso(glyphCenterY),
                )
            }
            UsecaseActorType.Icon -> {
                paths += closed(
                    rectangle(
                        width = ICON_FRAME_SIZE,
                        height = ICON_FRAME_SIZE,
                        centerY = glyphCenterY + ICON_CENTER_Y,
                    ),
                )
            }
        }
        if (business) {
            paths += businessActorMarker(type, glyphCenterY)
        }

        val texts = mutableListOf<UsecaseTextVisual>()
        var nextCenter = contentTop + ACTOR_FIGURE_HEIGHT + ACTOR_LABEL_GAP
        if (stereotype != null) {
            nextCenter += stereotype.size.height / 2f
            texts += UsecaseTextVisual(stereotype, ScenePoint(0f, nextCenter))
            nextCenter += stereotype.size.height / 2f + STEREOTYPE_LABEL_GAP
        }
        nextCenter += label.size.height / 2f
        texts += UsecaseTextVisual(label, ScenePoint(0f, nextCenter))

        return UsecaseNodeVisual(
            kind = if (type == UsecaseActorType.Icon) SceneShapeKind.IconSquare else SceneShapeKind.Person,
            size = SceneSize(width, height),
            geometry = SceneShapeGeometry(paths, rectangle(width, height)),
            texts = texts,
            asset = icon?.let {
                UsecaseAssetVisual(
                    source = it,
                    center = ScenePoint(0f, glyphCenterY + ICON_CENTER_Y),
                    size = SceneSize(ICON_SIZE, ICON_SIZE),
                )
            },
        )
    }

    fun useCase(
        label: UsecaseMeasuredText,
        stereotype: UsecaseMeasuredText?,
        rectangular: Boolean,
        business: Boolean,
        minimumLabelWidth: Float,
        look: String,
    ): UsecaseNodeVisual {
        val labelWidth = max(
            minimumLabelWidth,
            max(label.size.width, stereotype?.size?.width ?: 0f),
        )
        val stereotypeHeight = stereotype?.size?.height ?: 0f
        val labelHeight = label.size.height +
            if (stereotype == null) 0f else stereotypeHeight + STEREOTYPE_LABEL_GAP
        // Mermaid.js 12.0.0:
        // usecaseEllipse.ts -> radiusX/radiusY and squareRect.ts -> squareRect.
        val width = if (rectangular) {
            labelWidth + if (look == "neo") 32f else 40f
        } else {
            labelWidth + 40f
        }
        val height = if (rectangular) {
            labelHeight + if (look == "neo") 24f else 20f
        } else {
            labelHeight + 40f
        }
        val outline = if (rectangular) {
            rectangle(width, height)
        } else {
            ellipse(0f, 0f, width / 2f, height / 2f)
        }
        val paths = mutableListOf(closed(outline))
        if (business) {
            val radiusX = width / 2f
            val radiusY = height / 2f
            val markerInset = 2f
            val markerStartX = labelWidth / 2f + markerInset
            val markerEndX = radiusX - markerInset
            val startRatio = (markerStartX / radiusX).coerceIn(-1f, 1f)
            val endRatio = (markerEndX / radiusX).coerceIn(-1f, 1f)
            paths += open(
                ScenePoint(markerStartX, radiusY * sqrt(max(0f, 1f - startRatio * startRatio))),
                ScenePoint(markerEndX, -radiusY * sqrt(max(0f, 1f - endRatio * endRatio))),
            )
        }
        val texts = mutableListOf<UsecaseTextVisual>()
        if (stereotype != null) {
            texts += UsecaseTextVisual(
                stereotype,
                ScenePoint(0f, -labelHeight / 2f + stereotypeHeight / 2f),
            )
        }
        texts += UsecaseTextVisual(
            label,
            ScenePoint(0f, labelHeight / 2f - label.size.height / 2f),
        )
        return UsecaseNodeVisual(
            kind = if (rectangular) SceneShapeKind.Rectangle else SceneShapeKind.Ellipse,
            size = SceneSize(width, height),
            geometry = SceneShapeGeometry(paths, outline),
            texts = texts,
        )
    }

    fun note(label: UsecaseMeasuredText): UsecaseNodeVisual {
        // Mermaid.js 12.0.0: note.ts -> bbox + node.padding * 2.
        val width = label.size.width + 20f
        val height = label.size.height + 20f
        val outline = rectangle(width, height)
        return UsecaseNodeVisual(
            kind = SceneShapeKind.Rectangle,
            size = SceneSize(width, height),
            geometry = SceneShapeGeometry(listOf(closed(outline)), outline),
            texts = listOf(UsecaseTextVisual(label, ScenePoint(0f, 0f))),
        )
    }

    fun jsonTable(
        title: UsecaseMeasuredText,
        rows: List<UsecaseMeasuredJsonRow>,
        borderWidth: Float,
    ): UsecaseNodeVisual {
        val keyWidth = (rows.maxOfOrNull { row -> row.key.size.width } ?: 0f) +
            CELL_PADDING_X * 2f
        val measuredValueWidth = (rows.maxOfOrNull { row -> row.value.size.width } ?: 0f) +
            CELL_PADDING_X * 2f
        val titleWidth = title.size.width + CELL_PADDING_X * 2f
        val innerWidth = max(titleWidth, keyWidth + measuredValueWidth)
        val valueWidth = measuredValueWidth + max(0f, innerWidth - keyWidth - measuredValueWidth)
        val titleHeight = title.size.height + CELL_PADDING_Y * 2f
        val rowHeights = rows.map { row ->
            max(row.key.size.height, row.value.size.height) + CELL_PADDING_Y * 2f
        }
        val innerHeight = titleHeight + rowHeights.sum()
        val totalWidth = innerWidth + borderWidth * 2f
        val totalHeight = innerHeight + borderWidth * 2f
        val left = -innerWidth / 2f
        val top = -innerHeight / 2f
        val paths = mutableListOf<SceneShapePath>()
        paths += closed(rectangle(totalWidth, totalHeight))
        paths += closed(rectangleAt(left, top, innerWidth, titleHeight))
        val texts = mutableListOf(
            UsecaseTextVisual(title, ScenePoint(0f, top + titleHeight / 2f)),
        )
        var rowTop = top + titleHeight
        rows.forEachIndexed { index, row ->
            val rowHeight = rowHeights[index]
            paths += closed(rectangleAt(left, rowTop, keyWidth, rowHeight))
            paths += closed(rectangleAt(left + keyWidth, rowTop, valueWidth, rowHeight))
            texts += UsecaseTextVisual(
                row.key,
                ScenePoint(left + keyWidth / 2f, rowTop + rowHeight / 2f),
            )
            texts += UsecaseTextVisual(
                row.value,
                ScenePoint(left + keyWidth + valueWidth / 2f, rowTop + rowHeight / 2f),
            )
            rowTop += rowHeight
        }
        return UsecaseNodeVisual(
            kind = SceneShapeKind.Rectangle,
            size = SceneSize(totalWidth, totalHeight),
            geometry = SceneShapeGeometry(paths, rectangle(totalWidth, totalHeight)),
            texts = texts,
        )
    }

    private fun businessActorMarker(
        type: UsecaseActorType,
        glyphCenterY: Float,
    ): SceneShapePath {
        val centerY: Float
        val radius: Float
        when (type) {
            UsecaseActorType.Normal -> {
                centerY = glyphCenterY - 24f
                radius = 12f
            }
            UsecaseActorType.Hollow -> {
                centerY = glyphCenterY - 23f
                radius = 9f
            }
            UsecaseActorType.Awesome -> {
                centerY = glyphCenterY - 21f
                radius = 13f
            }
            UsecaseActorType.Icon -> return open(
                ScenePoint(12f, glyphCenterY - 8f),
                ScenePoint(26f, glyphCenterY - 26f),
            )
        }
        val centerOffset = radius * BUSINESS_MARKER_OFFSET_RATIO
        val halfChord = radius * sqrt(1f - BUSINESS_MARKER_OFFSET_RATIO * BUSINESS_MARKER_OFFSET_RATIO)
        val directionX = cos(BUSINESS_MARKER_ANGLE)
        val directionY = -sin(BUSINESS_MARKER_ANGLE)
        val centerX = centerOffset * -directionY
        val markerCenterY = centerY + centerOffset * directionX
        val deltaX = halfChord * directionX
        val deltaY = halfChord * directionY
        return open(
            ScenePoint(centerX - deltaX, markerCenterY - deltaY),
            ScenePoint(centerX + deltaX, markerCenterY + deltaY),
        )
    }

    private fun roundedTorso(centerY: Float): List<ScenePoint> = listOf(
        ScenePoint(-24f, centerY + 25f),
        ScenePoint(-23f, centerY + 13f),
        ScenePoint(-18f, centerY + 4f),
        ScenePoint(-10f, centerY - 1f),
        ScenePoint(0f, centerY - 3f),
        ScenePoint(10f, centerY - 1f),
        ScenePoint(18f, centerY + 4f),
        ScenePoint(23f, centerY + 13f),
        ScenePoint(24f, centerY + 25f),
        ScenePoint(18f, centerY + 30f),
        ScenePoint(-18f, centerY + 30f),
    )

    private fun rectangle(
        width: Float,
        height: Float,
        centerY: Float = 0f,
    ): List<ScenePoint> = listOf(
        ScenePoint(-width / 2f, centerY - height / 2f),
        ScenePoint(width / 2f, centerY - height / 2f),
        ScenePoint(width / 2f, centerY + height / 2f),
        ScenePoint(-width / 2f, centerY + height / 2f),
    )

    private fun rectangleAt(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ): List<ScenePoint> = listOf(
        ScenePoint(left, top),
        ScenePoint(left + width, top),
        ScenePoint(left + width, top + height),
        ScenePoint(left, top + height),
    )

    private fun ellipse(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
    ): List<ScenePoint> = (0 until ELLIPSE_SEGMENTS).map { index ->
        val angle = 2f * PI.toFloat() * index / ELLIPSE_SEGMENTS
        ScenePoint(
            centerX + radiusX * cos(angle),
            centerY + radiusY * sin(angle),
        )
    }

    private fun closed(
        points: List<ScenePoint>,
        fill: SceneShapePaint = SceneShapePaint.Fill,
    ): SceneShapePath = SceneShapePath(
        points = points,
        closed = true,
        fill = fill,
        stroke = SceneShapePaint.Stroke,
        strokeWidth = 2f,
    )

    private fun open(
        vararg points: ScenePoint,
    ): SceneShapePath = SceneShapePath(
        points = points.toList(),
        closed = false,
        fill = SceneShapePaint.None,
        stroke = SceneShapePaint.Stroke,
        strokeWidth = 2f,
    )

    private const val ACTOR_FIGURE_WIDTH = 56f
    private const val ACTOR_FIGURE_HEIGHT = 72f
    private const val ACTOR_LABEL_GAP = 8f
    private const val STEREOTYPE_LABEL_GAP = 2f
    private const val ACTOR_PADDING = 8f
    private const val ICON_FRAME_SIZE = 52f
    private const val ICON_SIZE = 42f
    private const val ICON_CENTER_Y = -2f
    private const val CELL_PADDING_X = 8f
    private const val CELL_PADDING_Y = 4f
    private const val ELLIPSE_SEGMENTS = 64
    private const val BUSINESS_MARKER_OFFSET_RATIO = 0.6f
    private val BUSINESS_MARKER_ANGLE = PI.toFloat() / 3f
}

internal data class UsecaseMeasuredText(
    val text: String,
    val spans: List<SceneTextSpan>,
    val size: SceneSize,
)

internal data class UsecaseTextVisual(
    val measured: UsecaseMeasuredText,
    val center: ScenePoint,
)

internal data class UsecaseAssetVisual(
    val source: String,
    val center: ScenePoint,
    val size: SceneSize,
)

internal data class UsecaseNodeVisual(
    val kind: SceneShapeKind,
    val size: SceneSize,
    val geometry: SceneShapeGeometry,
    val texts: List<UsecaseTextVisual>,
    val asset: UsecaseAssetVisual? = null,
)

internal data class UsecaseMeasuredJsonRow(
    val key: UsecaseMeasuredText,
    val value: UsecaseMeasuredText,
)
