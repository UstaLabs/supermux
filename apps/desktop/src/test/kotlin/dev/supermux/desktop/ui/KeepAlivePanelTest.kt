package dev.supermux.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec for the desktop [KeepAlivePanel] — the heavyweight-safe hide/show wrapper for
 * SwingPanel-bearing panes. What can be asserted headlessly is the CONTRACT the strategy rests
 * on: (1) hidden = laid out at 0×0 (the only hiding a heavyweight AWT child respects — SwingPanel
 * mirrors its Compose bounds onto the AWT component), and (2) the content composable is NEVER
 * disposed/remounted across a hide/show cycle, so remembered holders (TerminalClient +
 * JediTermWidget) survive.
 *
 * The actual Swing side (AWT child stops painting at 0×0, same widget re-shows, client stays
 * CONNECTED) cannot run under runComposeUiTest — its scene isn't a real AWT window, so a
 * SwingPanel never attaches — and was verified in this task's live probe run against the broker
 * (hide/show with status logging; see the M2 Task 3 report) plus Task 8's milestone pass.
 */
@OptIn(ExperimentalTestApi::class)
class KeepAlivePanelTest {

    /** Probe content: counts mounts (LaunchedEffect(Unit)) and disposals. */
    private class Probe {
        var mounts = 0
        var disposals = 0
    }

    @Composable
    private fun ProbeContent(probe: Probe) {
        LaunchedEffect(Unit) { probe.mounts++ }
        DisposableEffect(Unit) { onDispose { probe.disposals++ } }
        Box(Modifier.fillMaxSize().testTag("probe_content"))
    }

    @Test
    fun content_survives_hide_show_without_remount_or_disposal() = runComposeUiTest {
        val probe = Probe()
        var visible by mutableStateOf(true)
        setContent {
            KeepAlivePanel(visible = visible) { ProbeContent(probe) }
        }

        waitForIdle()
        assertEquals(1, probe.mounts)

        visible = false
        waitForIdle()
        visible = true
        waitForIdle()

        // One mount, zero disposals: the content slot never left the composition, so a remembered
        // TerminalClient/JediTermWidget holder inside it would have survived the cycle.
        assertEquals(1, probe.mounts, "content remounted across hide/show")
        assertEquals(0, probe.disposals, "content was disposed on hide")
    }

    @Test
    fun hidden_panel_is_laid_out_at_zero_size() = runComposeUiTest {
        var visible by mutableStateOf(true)
        setContent {
            KeepAlivePanel(visible = visible) {
                Box(Modifier.fillMaxSize().testTag("probe_content"))
            }
        }

        onNodeWithTag("probe_content").assertIsDisplayed()

        visible = false
        waitForIdle()

        // Still in the tree (kept alive) but constrained to 0×0 — the layout-level hiding a
        // heavyweight SwingPanel child respects (its AWT bounds follow the Compose bounds).
        val bounds = onNodeWithTag("probe_content").getBoundsInRoot()
        assertEquals(0.dp, bounds.width)
        assertEquals(0.dp, bounds.height)
    }

    @Test
    fun reshown_panel_returns_to_full_size() = runComposeUiTest {
        var visible by mutableStateOf(false)
        setContent {
            Box(Modifier.fillMaxSize()) {
                KeepAlivePanel(visible = visible) {
                    Box(Modifier.fillMaxSize().testTag("probe_content"))
                }
            }
        }

        waitForIdle()
        assertEquals(0.dp, onNodeWithTag("probe_content").getBoundsInRoot().width)

        visible = true
        waitForIdle()

        onNodeWithTag("probe_content").assertIsDisplayed()
        val bounds = onNodeWithTag("probe_content").getBoundsInRoot()
        check(bounds.width > 0.dp && bounds.height > 0.dp) { "re-shown panel still 0-sized: $bounds" }
    }
}
