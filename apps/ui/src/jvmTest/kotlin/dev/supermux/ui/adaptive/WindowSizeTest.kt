package dev.supermux.ui.adaptive

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the Material3 width breakpoints (Compact < 600 ≤ Medium < 840 ≤ Expanded) and the
 * composition-local contract: `Expanded` when nobody provides one (desktop-safe default).
 */
@OptIn(ExperimentalTestApi::class)
class WindowSizeTest {
    @Test
    fun breakpointsMatchMaterial3() {
        assertEquals(WindowWidthClass.Compact, widthClassFor(0))
        assertEquals(WindowWidthClass.Compact, widthClassFor(411))   // phone
        assertEquals(WindowWidthClass.Compact, widthClassFor(599))
        assertEquals(WindowWidthClass.Medium, widthClassFor(600))    // Android's workspace threshold
        assertEquals(WindowWidthClass.Medium, widthClassFor(795))    // unfolded Z Fold
        assertEquals(WindowWidthClass.Medium, widthClassFor(839))
        assertEquals(WindowWidthClass.Expanded, widthClassFor(840))
        assertEquals(WindowWidthClass.Expanded, widthClassFor(1440)) // desktop window
    }

    @Test
    fun defaultsToExpandedWhenUnprovided() = runComposeUiTest {
        setContent {
            assertEquals(WindowWidthClass.Expanded, LocalWindowWidthClass.current)
            Text("default-expanded")
        }
        onNodeWithText("default-expanded").assertIsDisplayed()
    }

    @Test
    fun provideWindowWidthClassPropagates() = runComposeUiTest {
        setContent {
            ProvideWindowWidthClass(widthDp = 411) {
                assertEquals(WindowWidthClass.Compact, LocalWindowWidthClass.current)
                Text("provided-compact")
            }
            ProvideWindowWidthClass(widthDp = 700) {
                assertEquals(WindowWidthClass.Medium, LocalWindowWidthClass.current)
                Text("provided-medium")
            }
            ProvideWindowWidthClass(widthDp = 1280) {
                assertEquals(WindowWidthClass.Expanded, LocalWindowWidthClass.current)
                Text("provided-expanded")
            }
        }
        onNodeWithText("provided-compact").assertIsDisplayed()
        onNodeWithText("provided-medium").assertIsDisplayed()
        onNodeWithText("provided-expanded").assertIsDisplayed()
    }
}
