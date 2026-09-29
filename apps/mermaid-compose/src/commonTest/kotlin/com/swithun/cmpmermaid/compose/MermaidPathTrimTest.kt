package com.swithun.cmpmermaid.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidPathTrimTest {
    @Test
    fun explicitDashIntervalsOverrideGeneratedNeoPattern() {
        assertEquals(
            false,
            shouldApplyNeoMarkerMargins(
                look = "neo",
                animated = false,
                dashIntervals = listOf(10f, 7f),
            ),
        )
        assertEquals(
            true,
            shouldApplyNeoMarkerMargins(
                look = "neo",
                animated = false,
                dashIntervals = emptyList(),
            ),
        )
        assertEquals(
            false,
            shouldApplyNeoMarkerMargins(
                look = "neo",
                animated = true,
                dashIntervals = emptyList(),
            ),
        )
    }

    @Test
    fun preservesContoursCreatedByLineHopGaps() {
        val segments = mermaidDashSegments(
            contourLengths = listOf(16f, 24f),
            sourceIntervals = listOf(0f, 4f, 32f, 4f),
        )

        assertEquals(
            listOf(
                MermaidContourSegment(
                    contourIndex = 0,
                    start = 4f,
                    end = 16f,
                ),
                MermaidContourSegment(
                    contourIndex = 1,
                    start = 0f,
                    end = 20f,
                ),
            ),
            segments,
        )
    }

    @Test
    fun carriesStartMarginAcrossShortFirstContour() {
        val segments = mermaidDashSegments(
            contourLengths = listOf(2f, 18f),
            sourceIntervals = listOf(0f, 4f, 12f, 4f),
        )

        assertEquals(
            listOf(
                MermaidContourSegment(
                    contourIndex = 1,
                    start = 2f,
                    end = 14f,
                ),
            ),
            segments,
        )
    }
}
