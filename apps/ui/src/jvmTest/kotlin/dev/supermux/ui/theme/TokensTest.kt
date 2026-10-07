package dev.supermux.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** Values copied from the desktop Tokens.kt this file replaces — the superset wins. */
class TokensTest {
    @Test
    fun spacingIsTheFourPointGridPlusTheQrSize() {
        assertEquals(4.dp, Space.xs)
        assertEquals(8.dp, Space.sm)
        assertEquals(12.dp, Space.md)
        assertEquals(16.dp, Space.lg)
        assertEquals(24.dp, Space.xl)
        assertEquals(32.dp, Space.xxl)
        assertEquals(200.dp, Space.qr)
    }

    @Test
    fun radiiStrokesAndSizes() {
        assertEquals(8.dp, Radii.sm)
        assertEquals(12.dp, Radii.md)
        assertEquals(16.dp, Radii.lg)
        assertEquals(999.dp, Radii.pill)
        assertEquals(1.dp, Stroke.hairline)
        assertEquals(2.dp, Stroke.thin)
        assertEquals(2.dp, Stroke.md)
        assertEquals(14.dp, IconSize.sm)
        assertEquals(18.dp, IconSize.md)
        assertEquals(24.dp, IconSize.lg)
        assertEquals(8.dp, Size.statusDot)
        assertEquals(384.dp, Size.omniboxWidth)
        assertEquals(360.dp, Size.omniboxListMax)
        assertEquals(18.dp, Sizes.iconSm)
        assertEquals(1.5.dp, Sizes.hairline)
        assertEquals(32.dp, Sizes.iconButton)
        assertEquals(40.dp, Sizes.videoPlayGlyph)
        assertEquals(280.dp, Media.inlineImageMaxHeight)
        assertEquals(320.dp, Media.inlineVideoMaxHeight)
        assertEquals(0.7f, Media.inlineVideoWidthFraction)
    }
}
