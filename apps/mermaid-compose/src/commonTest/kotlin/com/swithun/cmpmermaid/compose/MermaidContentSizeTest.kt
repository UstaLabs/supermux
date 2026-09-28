package com.swithun.cmpmermaid.compose

import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneColor
import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidContentSizeTest {
    @Test
    fun includesNonNegativeViewportPadding() {
        val scene = MermaidScene(
            width = 240f,
            height = 120f,
            background = SceneColor(0xFFFFFFFF),
            elements = emptyList(),
            viewportPadding = 12f,
        )

        assertEquals(MermaidContentSize(264f, 144f), scene.contentSizeWithPadding())
        assertEquals(
            MermaidContentSize(240f, 120f),
            scene.copy(viewportPadding = -8f).contentSizeWithPadding(),
        )
    }

    @Test
    fun keepsReportedDimensionsPositive() {
        val scene = MermaidScene(
            width = 0f,
            height = -1f,
            background = SceneColor(0xFFFFFFFF),
            elements = emptyList(),
        )

        assertEquals(MermaidContentSize(1f, 1f), scene.contentSizeWithPadding())
    }
}
