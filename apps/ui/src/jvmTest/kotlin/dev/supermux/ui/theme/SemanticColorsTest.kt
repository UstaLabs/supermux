package dev.supermux.ui.theme

import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class SemanticColorsTest {
    @Test
    fun darkAndLightDiffer() {
        assertNotEquals(supermuxSemanticsDark().success, supermuxSemanticsLight().success)
    }

    @Test
    fun theLocalDefaultsToTheDarkSet() = runComposeUiTest {
        setContent {
            assertEquals(supermuxSemanticsDark(), LocalSemantics.current)
            Text("semantics")
        }
        onNodeWithText("semantics").assertIsDisplayed()
    }

    /**
     * Pins the resolved sRGB of every semantic role. These are the OKLCH triples in
     * `SemanticColors.kt` run through `oklchToArgb` — a change to either the triples or the
     * conversion repaints status across both apps, so it must be deliberate.
     */
    @Test
    fun darkSemanticsResolveToPinnedSrgb() {
        val c = supermuxSemanticsDark()
        assertEquals(Color(0xFF53BE70), c.success)
        assertEquals(Color(0xFFEBB25F), c.warning)
        assertEquals(Color(0xFFFF6F6B), c.danger)
        assertEquals(Color(0xFF5AA8D4), c.info)
        assertEquals(Color(0xFF4BBAA7), c.brand)
        assertEquals(Color(0xFF071009), c.onStatus)
    }

    @Test
    fun lightSemanticsResolveToPinnedSrgb() {
        val c = supermuxSemanticsLight()
        assertEquals(Color(0xFF007F35), c.success)
        assertEquals(Color(0xFFA16100), c.warning)
        assertEquals(Color(0xFFC52C2A), c.danger)
        assertEquals(Color(0xFF006AA0), c.info)
        assertEquals(Color(0xFF007368), c.brand)
        assertEquals(Color(0xFFF4FAF5), c.onStatus)
    }
}
