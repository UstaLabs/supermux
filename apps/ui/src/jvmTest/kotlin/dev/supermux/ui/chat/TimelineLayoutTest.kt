package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.WindowWidthClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The transcript's width rule (cluster D2's union of the two hosts): a phone-sized window runs the
 * text full-bleed, anything wider caps it at the reading width so prose never spans a monitor.
 */
@OptIn(ExperimentalTestApi::class)
class TimelineLayoutTest {

    @Test fun expanded_window_caps_the_reading_width() = runComposeUiTest {
        var density: Density? = null
        setPlatformContent(widthClass = WindowWidthClass.Expanded) {
            density = LocalDensity.current
            Box(Modifier.width(1600.dp)) {
                Box(Modifier.timelineReadingWidth().fillMaxWidth().height(10.dp).testTag("column"))
            }
        }
        val d = density!!
        val widthPx = onNodeWithTag("column").fetchSemanticsNode().layoutInfo.width
        assertEquals(with(d) { TIMELINE_MAX_WIDTH.roundToPx() }, widthPx)
    }

    @Test fun compact_window_runs_full_bleed() = runComposeUiTest {
        var density: Density? = null
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            density = LocalDensity.current
            Box(Modifier.width(1600.dp)) {
                Box(Modifier.timelineReadingWidth().height(10.dp).testTag("column"))
            }
        }
        val d = density!!
        val widthPx = onNodeWithTag("column").fetchSemanticsNode().layoutInfo.width
        assertTrue(
            widthPx > with(d) { TIMELINE_MAX_WIDTH.roundToPx() },
            "a phone reads full-bleed; got ${widthPx}px",
        )
    }
}
