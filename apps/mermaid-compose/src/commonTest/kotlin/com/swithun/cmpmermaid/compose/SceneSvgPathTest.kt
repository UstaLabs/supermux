package com.swithun.cmpmermaid.compose

import androidx.compose.ui.geometry.Rect
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.SceneAffineTransform
import com.swithun.cmpmermaid.core.SceneShapePath
import com.swithun.cmpmermaid.core.SceneShapeViewportFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SceneSvgPathTest {
    @Test
    fun mapsAndCentersViewBoxWithoutLoadingNativeGraphics() {
        val matrix = Rect(0f, 0f, 20f, 10f).toViewportMatrix(
            bounds = Rect(20f, 40f, 40f, 80f),
            viewportFit = SceneShapeViewportFit.MeetCenter,
        )

        assertEquals(
            androidx.compose.ui.geometry.Offset(20f, 55f),
            matrix.map(androidx.compose.ui.geometry.Offset.Zero),
        )
        assertEquals(
            androidx.compose.ui.geometry.Offset(40f, 65f),
            matrix.map(androidx.compose.ui.geometry.Offset(20f, 10f)),
        )
    }

    @Test
    fun mapsSvgAffineTransformWithoutLoadingNativeGraphics() {
        val matrix = SceneAffineTransform(
            scaleX = 2f,
            scaleY = 3f,
            translateX = 4f,
            translateY = 5f,
        ).toComposeMatrix()

        assertEquals(
            androidx.compose.ui.geometry.Offset(6f, 8f),
            matrix.map(androidx.compose.ui.geometry.Offset(1f, 1f)),
        )
    }

    @Test
    fun convertsInvalidSvgPathToStructuredFailure() {
        val result = SceneShapePath(
            pathData = "not-svg-path",
        ).toComposePath(
            bounds = Rect(0f, 0f, 24f, 24f),
            viewBox = Rect(0f, 0f, 24f, 24f),
            viewportFit = SceneShapeViewportFit.Stretch,
        )

        assertIs<GMResult.Err<SceneSvgPathError>>(result)
    }
}
