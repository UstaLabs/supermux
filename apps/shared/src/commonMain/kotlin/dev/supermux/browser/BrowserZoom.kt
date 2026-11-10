package dev.supermux.browser

/** The zoom levels a Browser tab steps through, as browsers do. */
val ZOOM_STEPS: List<Float> = listOf(0.25f, 0.33f, 0.5f, 0.67f, 0.75f, 0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f)

/** The next level from [current], up when [direction] is positive, else down; the same level at either end. */
fun nextZoom(current: Float, direction: Int): Float =
    if (direction > 0) ZOOM_STEPS.firstOrNull { it > current + 0.001f } ?: ZOOM_STEPS.last()
    else ZOOM_STEPS.lastOrNull { it < current - 0.001f } ?: ZOOM_STEPS.first()
