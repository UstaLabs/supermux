package com.swithun.cmpmermaid.compose

import androidx.compose.ui.geometry.Offset
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneNodeInteraction
import com.swithun.cmpmermaid.core.SceneRect
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagramViewportTest {
    @Test
    fun zoomAroundCenterKeepsContentCentered() {
        val viewport = DiagramViewport().applyGesture(
            viewportWidth = 300f,
            viewportHeight = 200f,
            fittedContentWidth = 300f,
            fittedContentHeight = 120f,
            centroidX = 150f,
            centroidY = 100f,
            gesturePanX = 0f,
            gesturePanY = 0f,
            zoomChange = 2f,
        )

        assertEquals(2f, viewport.zoom)
        assertEquals(0f, viewport.panX)
        assertEquals(0f, viewport.panY)
    }

    @Test
    fun zoomAroundRightSideKeepsTouchedContentUnderCentroid() {
        val viewport = DiagramViewport().applyGesture(
            viewportWidth = 300f,
            viewportHeight = 200f,
            fittedContentWidth = 300f,
            fittedContentHeight = 120f,
            centroidX = 250f,
            centroidY = 100f,
            gesturePanX = 0f,
            gesturePanY = 0f,
            zoomChange = 2f,
        )

        assertEquals(-100f, viewport.panX)
    }

    @Test
    fun panCannotMoveContentBeyondViewport() {
        val viewport = DiagramViewport(zoom = 2f).applyGesture(
            viewportWidth = 300f,
            viewportHeight = 200f,
            fittedContentWidth = 300f,
            fittedContentHeight = 120f,
            centroidX = 150f,
            centroidY = 100f,
            gesturePanX = 1_000f,
            gesturePanY = -1_000f,
            zoomChange = 1f,
        )

        assertEquals(150f, viewport.panX)
        assertEquals(-20f, viewport.panY)
    }

    @Test
    fun zoomingBackToFitResetsPan() {
        val viewport = DiagramViewport(
            zoom = 2f,
            panX = 80f,
            panY = -20f,
        ).applyGesture(
            viewportWidth = 300f,
            viewportHeight = 200f,
            fittedContentWidth = 300f,
            fittedContentHeight = 120f,
            centroidX = 150f,
            centroidY = 100f,
            gesturePanX = 0f,
            gesturePanY = 0f,
            zoomChange = 0.5f,
        )

        assertEquals(DiagramViewport(), viewport)
    }

    @Test
    fun resolvesInteractionThroughFittedViewportTransform() {
        val interaction = SceneNodeInteraction(
            nodeId = "A",
            bounds = SceneRect(20f, 20f, 40f, 40f),
            link = "https://example.com",
        )
        val scene = MermaidScene(
            width = 100f,
            height = 100f,
            background = SceneColor(0xFFFFFFFF),
            elements = emptyList(),
            interactions = listOf(interaction),
        )

        val resolved = scene.interactionAt(
            screenPosition = Offset(76f, 36f),
            viewportWidth = 200f,
            viewportHeight = 120f,
            viewport = DiagramViewport(),
        )

        assertEquals(interaction, resolved)
    }

    @Test
    fun resolvesInteractionThroughPaddedViewportTransform() {
        val interaction = SceneNodeInteraction(
            nodeId = "A",
            bounds = SceneRect(20f, 20f, 40f, 40f),
            link = "https://example.com",
        )
        val scene = MermaidScene(
            width = 100f,
            height = 100f,
            background = SceneColor(0xFFFFFFFF),
            elements = emptyList(),
            interactions = listOf(interaction),
            viewportPadding = 10f,
        )

        val resolved = scene.interactionAt(
            screenPosition = Offset(76f, 36f),
            viewportWidth = 200f,
            viewportHeight = 120f,
            viewport = DiagramViewport(),
        )

        assertEquals(interaction, resolved)
    }
}
