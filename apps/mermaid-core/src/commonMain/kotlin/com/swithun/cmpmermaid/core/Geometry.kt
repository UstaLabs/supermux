package com.swithun.cmpmermaid.core

import kotlin.math.max
import kotlin.math.min

data class ScenePoint(
    val x: Float,
    val y: Float,
)

data class SceneSize(
    val width: Float,
    val height: Float,
)

data class SceneRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val center: ScenePoint get() = ScenePoint((left + right) / 2f, (top + bottom) / 2f)

    fun inflate(horizontal: Float, vertical: Float): SceneRect = SceneRect(
        left = left - horizontal,
        top = top - vertical,
        right = right + horizontal,
        bottom = bottom + vertical,
    )

    fun union(other: SceneRect): SceneRect = SceneRect(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
    )

    fun translate(dx: Float, dy: Float): SceneRect = SceneRect(
        left = left + dx,
        top = top + dy,
        right = right + dx,
        bottom = bottom + dy,
    )
}
