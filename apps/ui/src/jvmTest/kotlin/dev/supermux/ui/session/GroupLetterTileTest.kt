package dev.supermux.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GroupLetterTileTest {

    /**
     * The glyph's ink sits in the middle of the tile, measured on the rendered pixels.
     *
     * This guards the desktop renderer only. The bug it was written for was iOS-specific: with the
     * default line height, SF's metrics put the letter ~3pt low (22px above vs 12px below at 3x),
     * while this JVM render was already centred. The iOS check was a simulator screenshot.
     */
    @Test
    fun theLetterInkIsCentredInTheTile() = runComposeUiTest {
        setContent {
            Box(Modifier.testTag("tile")) { GroupLetterTile("S", Color(0xFF3A5F8A), 18.dp) }
        }
        val px = onNodeWithTag("tile").captureToImage().toPixelMap()
        var top = Int.MAX_VALUE; var bottom = -1; var left = Int.MAX_VALUE; var right = -1
        for (y in 0 until px.height) for (x in 0 until px.width) {
            val c = px[x, y]
            if (c.red > 0.85f && c.green > 0.85f && c.blue > 0.85f) {
                top = minOf(top, y); bottom = maxOf(bottom, y); left = minOf(left, x); right = maxOf(right, x)
            }
        }
        assertTrue(bottom >= 0, "no glyph pixels rendered")
        val vSkew = abs(top - (px.height - 1 - bottom))
        val hSkew = abs(left - (px.width - 1 - right))
        assertTrue(vSkew <= 1, "glyph off-centre vertically: top margin $top, bottom margin ${px.height - 1 - bottom}")
        assertTrue(hSkew <= 1, "glyph off-centre horizontally: left margin $left, right margin ${px.width - 1 - right}")
    }
}
