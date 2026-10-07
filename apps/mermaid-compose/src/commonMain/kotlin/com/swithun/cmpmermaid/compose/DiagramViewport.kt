package com.swithun.cmpmermaid.compose

import kotlin.math.max

internal data class DiagramViewport(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

internal fun DiagramViewport.applyGesture(
    viewportWidth: Float,
    viewportHeight: Float,
    fittedContentWidth: Float,
    fittedContentHeight: Float,
    centroidX: Float,
    centroidY: Float,
    gesturePanX: Float,
    gesturePanY: Float,
    zoomChange: Float,
    maxZoom: Float = 5f,
): DiagramViewport {
    if (
        viewportWidth <= 0f ||
        viewportHeight <= 0f ||
        fittedContentWidth <= 0f ||
        fittedContentHeight <= 0f
    ) {
        return DiagramViewport()
    }

    val nextZoom = (zoom * zoomChange).coerceIn(1f, maxZoom)
    if (nextZoom == 1f) {
        return DiagramViewport()
    }

    val zoomRatio = nextZoom / zoom
    val centroidOffsetX = centroidX - viewportWidth / 2f
    val centroidOffsetY = centroidY - viewportHeight / 2f
    val proposedPanX = panX * zoomRatio + centroidOffsetX * (1f - zoomRatio) + gesturePanX
    val proposedPanY = panY * zoomRatio + centroidOffsetY * (1f - zoomRatio) + gesturePanY
    val maxPanX = max(0f, (fittedContentWidth * nextZoom - viewportWidth) / 2f)
    val maxPanY = max(0f, (fittedContentHeight * nextZoom - viewportHeight) / 2f)

    return DiagramViewport(
        zoom = nextZoom,
        panX = proposedPanX.coerceIn(-maxPanX, maxPanX),
        panY = proposedPanY.coerceIn(-maxPanY, maxPanY),
    )
}
