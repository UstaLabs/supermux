package com.swithun.cmpmermaid.compose

import com.swithun.cmpmermaid.core.SceneShapeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidPrimitiveRenderingTest {
    @Test
    fun dispatchesCircularShapesToOvalWithoutChangingExistingPrimitiveKinds() {
        assertEquals(
            PrimitiveCanvasShape.Oval,
            SceneShapeKind.Circle.primitiveCanvasShape(),
        )
        assertEquals(
            PrimitiveCanvasShape.Oval,
            SceneShapeKind.Ellipse.primitiveCanvasShape(),
        )
        assertEquals(
            PrimitiveCanvasShape.RoundedRectangle,
            SceneShapeKind.RoundedRectangle.primitiveCanvasShape(),
        )
        assertEquals(
            PrimitiveCanvasShape.Rectangle,
            SceneShapeKind.Rectangle.primitiveCanvasShape(),
        )
    }

    @Test
    fun preservesExplicitLinesWhenSoftWrappingIsDisabled() {
        assertEquals(1, mermaidTextMaxLines("main", softWrap = false))
        assertEquals(3, mermaidTextMaxLines("release\ncandidate\nready", softWrap = false))
        assertEquals(8, mermaidTextMaxLines("release\ncandidate", softWrap = true))
    }

    @Test
    fun preservesDefaultTextScaleWhileAllowingSvgTextOverride() {
        assertEquals(1.06f, mermaidTextHorizontalScale(null))
        assertEquals(1f, mermaidTextHorizontalScale(1f))
    }
}
