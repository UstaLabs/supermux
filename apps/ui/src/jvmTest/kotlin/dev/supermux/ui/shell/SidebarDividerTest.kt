package dev.supermux.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two seams, one file: the desktop OVERLAY ([SidebarDivider], resize only) and the compact strip
 * ([CompactSidebarDivider], resize + collapse chip). Both must keep the `sidebar_divider` drag-hit
 * tag; only the compact one may carry `sidebar_collapse` (the desktop shell collapses from its
 * title bar, and a stray chip there would sit on top of the detail pane).
 */
@OptIn(ExperimentalTestApi::class)
class SidebarDividerTest {

    @Test fun the_overlay_divider_has_the_drag_tag_and_no_collapse_chip() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Pointer,
                LocalWindowWidthClass provides WindowWidthClass.Expanded,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    Box(Modifier.fillMaxSize()) { SidebarDivider(onDragDelta = {}) }
                }
            }
        }
        onNodeWithTag("sidebar_divider", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("sidebar_collapse", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun the_compact_divider_has_both_tags_and_reports_a_collapse() = runComposeUiTest {
        var collapses = 0
        setContent {
            CompositionLocalProvider(
                LocalInputMode provides InputMode.Touch,
                LocalWindowWidthClass provides WindowWidthClass.Compact,
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    Box(Modifier.fillMaxSize()) {
                        CompactSidebarDivider(onDragDelta = {}, onCollapse = { collapses++ })
                    }
                }
            }
        }
        onNodeWithTag("sidebar_divider", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("sidebar_collapse", useUnmergedTree = true).assertIsDisplayed()

        onNodeWithTag("sidebar_collapse", useUnmergedTree = true).performClick()
        assertEquals(1, collapses)
    }
}
