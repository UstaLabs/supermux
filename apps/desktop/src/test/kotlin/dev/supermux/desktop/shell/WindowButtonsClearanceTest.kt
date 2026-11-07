package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.panes.PaneHost
import dev.supermux.ui.panes.PaneStripEnd
import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nothing of ours sits under the window's own buttons, where a click meant for a tab closes or
 * maximises the window (#29's Windows smoke). The pane frame's gutter leaves the rightmost strip a
 * few dp short of the window's edge, so a strip keeps clear of whatever part of the buttons it runs
 * under, not only when it touches the edge.
 */
@OptIn(ExperimentalTestApi::class)
class WindowButtonsClearanceTest {
    private val windows = chromeInsets(ChromeOs.Windows, customChrome = true, windowsCaptionButtons = 138.dp)
    private val linux = chromeInsets(ChromeOs.Linux, customChrome = true)

    // ── one strip in an 800 dp window ──

    @Test
    fun aTopRightStripShortOfTheEdgeEndsAtWindowsCaptionButtons() =
        assertEquals(800f - 138f, contentRight(windows, x = 400, width = 394))

    @Test
    fun aTopRightStripShortOfTheEdgeEndsAtLinuxButtons() =
        assertEquals(800f - 104f, contentRight(linux, x = 400, width = 394))

    @Test
    fun aStripReachingPartWayUnderTheButtonsClearsThatPart() =
        assertEquals(800f - 138f, contentRight(windows, x = 0, width = 750))

    @Test
    fun aStripClearOfTheButtonsKeepsItsWidth() =
        assertEquals(400f + 250f, contentRight(windows, x = 400, width = 250))

    @Test
    fun aStripBelowTheBandKeepsItsWidth() =
        assertEquals(400f + 394f, contentRight(windows, x = 400, width = 394, y = 300))

    // ── a workspace's panes ──

    @Test
    fun theRightmostStripsTabsPlusAndSplitButtonsClearWindowsCaptionButtons() = runComposeUiTest {
        twoPanes(windows)
        assertClearOf(1000f - 138f)
    }

    @Test
    fun theRightmostStripsTabsPlusAndSplitButtonsClearLinuxButtons() = runComposeUiTest {
        twoPanes(linux)
        assertClearOf(1000f - 104f)
    }

    @Test
    fun aFullStripBesideTheTrafficLightsKeepsItsPlusInView() = runComposeUiTest {
        val mac = chromeInsets(ChromeOs.MacOs, customChrome = true, macTrafficLights = 70.dp, sidebarControls = false)
        setContent {
            CompositionLocalProvider(LocalWindowChromeInsets provides mac) {
                Box(Modifier.size(1000.dp, 600.dp)) {
                    PaneHost(
                        layout = LayoutNode.Group("g1", listOf("a", "b", "c", "d", "e"), "a"),
                        modifier = Modifier.fillMaxSize(),
                        addSlot = { group -> Box(Modifier.fillMaxSize().testTag("add-$group")) },
                        chrome = DesktopStripChrome,
                    ) { Text("body") }
                }
            }
        }
        waitForIdle()
        val first = onNodeWithTag("view-tab-a").getUnclippedBoundsInRoot()
        val plus = onNodeWithTag("add-g1").getUnclippedBoundsInRoot()
        assertTrue(first.left.value >= 70f, "the first tab starts at ${first.left}, under the traffic lights")
        // The strip runs from the frame's gutter to 6 dp short of the window's right edge.
        assertTrue(plus.right.value <= 1000f - 6f, "the + ends at ${plus.right}, past the strip's end: scrolled out of view")
    }

    /** Where the content of a [width] dp strip at ([x], [y]) in an 800 × 600 dp window ends. */
    private fun contentRight(insets: ChromeInsets, x: Int, width: Int, y: Int = 0): Float {
        var right = -1f
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalWindowChromeInsets provides insets) {
                    Box(Modifier.size(800.dp, 600.dp)) {
                        Box(Modifier.offset(x.dp, y.dp).width(width.dp).height(32.dp).avoidWindowControls()) {
                            Box(Modifier.fillMaxSize().onGloballyPositioned { right = it.positionInRoot().x + it.size.width })
                        }
                    }
                }
            }
            waitForIdle()
        }
        return right
    }

    /**
     * Two panes side by side in a 1000 dp window (the test window is 1024 wide, and a bigger box would
     * be cut to it), the right one holding two tabs and the workspace's split buttons.
     */
    private fun ComposeUiTest.twoPanes(insets: ChromeInsets) {
        setContent {
            CompositionLocalProvider(LocalWindowChromeInsets provides insets) {
                Box(Modifier.size(1000.dp, 600.dp)) {
                    PaneHost(
                        layout = LayoutNode.Split(
                            "row",
                            listOf(0.5, 0.5),
                            listOf(LayoutNode.Group("g1", listOf("a"), "a"), LayoutNode.Group("g2", listOf("b", "c"), "b")),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        addSlot = { group -> Box(Modifier.fillMaxSize().testTag("add-$group")) },
                        chrome = DesktopStripChrome,
                        stripEnd = PaneStripEnd("g2", 66.dp) { Box(Modifier.fillMaxSize().testTag("split-buttons")) },
                    ) { Text("body") }
                }
            }
        }
        waitForIdle()
    }

    /** The right pane's split buttons end by [clearX], and its tabs and + stay in view before them. */
    private fun ComposeUiTest.assertClearOf(clearX: Float) {
        val buttons = onNodeWithTag("split-buttons").getUnclippedBoundsInRoot()
        val plus = onNodeWithTag("add-g2").getUnclippedBoundsInRoot()
        assertTrue(buttons.right.value <= clearX, "the split buttons end at ${buttons.right}, under the window's buttons from $clearX")
        assertTrue(plus.right.value <= buttons.left.value, "the + ends at ${plus.right}, past the split buttons at ${buttons.left}: out of view")
        for (tab in listOf("b", "c")) {
            val right = onNodeWithTag("view-tab-$tab").getUnclippedBoundsInRoot().right.value
            assertTrue(right <= plus.left.value + 0.5f, "tab $tab ends at $right, past the + at ${plus.left}")
        }
    }
}
