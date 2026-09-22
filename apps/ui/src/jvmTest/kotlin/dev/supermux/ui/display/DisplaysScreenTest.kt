package dev.supermux.ui.display

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.DisplayStream
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The shared [DisplaysScreen] (cluster G4) — Android's screen, which desktop GAINED at
 * `Route.Displays`. Android had no test for it, so this is new coverage of the chrome gate
 * `(standalone || compact) && !topBarShown`, the list/empty states, start, and the input-mode split
 * on stopping (swipe under Touch, a button under a pointer).
 */
@OptIn(ExperimentalTestApi::class)
class DisplaysScreenTest {

    private val streams = listOf(
        DisplayStream(id = "d1", sessionName = "demo", provider = "linux-xvfb", transport = "vnc", display = ":1", status = "running"),
        DisplayStream(id = "d2", sessionName = "other", provider = "macos-screen", transport = "vnc", status = "running"),
    )

    private val started = CopyOnWriteArrayList<String>()
    private val stopped = CopyOnWriteArrayList<String>()
    private var listed = 0

    private fun actions(list: List<DisplayStream> = streams) = DisplayActions(
        displays = MutableStateFlow(list),
        listDisplays = { listed++; list },
        startDisplay = { started.add(it) },
        stopDisplay = { stopped.add(it) },
    )

    @Composable
    private fun screen(
        actions: DisplayActions,
        topBarShown: Boolean = false,
        standalone: Boolean = false,
    ) = SupermuxTheme(appearance = AppearanceMode.DARK) {
        DisplaysScreen(actions = actions, onBack = {}, topBarShown = topBarShown, standalone = standalone)
    }

    @Test fun rows_render_from_the_live_flow_and_the_list_is_seeded_once() = runComposeUiTest {
        val a = actions()
        setPlatformContent(platform = FakePlatform()) { screen(a) }
        waitForIdle()
        onNodeWithTag("displays_list").assertIsDisplayed()
        onNodeWithTag("displays_row_d1").assertIsDisplayed()
        onNodeWithTag("displays_row_d2").assertIsDisplayed()
        assertEquals(1, listed)
    }

    @Test fun an_empty_fleet_says_so_instead_of_an_empty_list() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform()) { screen(actions(emptyList())) }
        waitForIdle()
        onNodeWithTag("displays_empty").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag("displays_list").fetchSemanticsNodes().size)
    }

    // ── the cluster-E chrome gate ─────────────────────────────────────────────────────────────

    @Test fun compact_paints_its_own_top_bar_with_the_start_action() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform(), pointer = false, widthClass = WindowWidthClass.Compact) {
            screen(actions())
        }
        waitForIdle()
        onNodeWithTag("displays_start").assertIsDisplayed()
        onNodeWithTag("displays_back").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag("displays_start_button").fetchSemanticsNodes().size)
    }

    @Test fun expanded_hangs_start_in_the_body_and_paints_no_top_bar() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform(), widthClass = WindowWidthClass.Expanded) {
            screen(actions())
        }
        waitForIdle()
        onNodeWithTag("displays_start_button").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag("displays_start").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag("displays_back").fetchSemanticsNodes().size)
    }

    @Test fun standalone_owns_its_chrome_even_when_expanded() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform(), widthClass = WindowWidthClass.Expanded) {
            screen(actions(), standalone = true)
        }
        waitForIdle()
        onNodeWithTag("displays_back").assertIsDisplayed()
        onNodeWithTag("displays_start").assertIsDisplayed()
    }

    @Test fun a_hub_that_already_painted_a_top_bar_gets_none_from_the_screen() = runComposeUiTest {
        setPlatformContent(platform = FakePlatform(), pointer = false, widthClass = WindowWidthClass.Compact) {
            screen(actions(), topBarShown = true)
        }
        waitForIdle()
        assertEquals(0, onAllNodesWithTag("displays_back").fetchSemanticsNodes().size)
        onNodeWithTag("displays_start_button").assertIsDisplayed()
    }

    // ── actions ───────────────────────────────────────────────────────────────────────────────

    @Test fun start_asks_for_the_host_default_display() = runComposeUiTest {
        started.clear()
        setPlatformContent(platform = FakePlatform(), widthClass = WindowWidthClass.Expanded) {
            screen(actions())
        }
        waitForIdle()
        onNodeWithTag("displays_start_button").performClick()
        waitUntil(timeoutMillis = 5_000) { started.isNotEmpty() }
        assertEquals(listOf(""), started.toList())
    }

    @Test fun a_pointer_client_stops_a_stream_with_a_button_not_a_swipe() = runComposeUiTest {
        stopped.clear()
        setPlatformContent(
            platform = FakePlatform(),
            widthClass = WindowWidthClass.Expanded,
            inputMode = InputMode.Pointer,
        ) { screen(actions()) }
        waitForIdle()
        onNodeWithTag("displays_stop_d1").performClick()
        waitUntil(timeoutMillis = 5_000) { stopped.isNotEmpty() }
        assertEquals(listOf("d1"), stopped.toList())
    }

    @Test fun a_touch_client_gets_the_swipe_row_and_no_stop_button() = runComposeUiTest {
        setPlatformContent(
            platform = FakePlatform(),
            pointer = false,
            widthClass = WindowWidthClass.Compact,
            inputMode = InputMode.Touch,
        ) { screen(actions()) }
        waitForIdle()
        assertEquals(0, onAllNodesWithTag("displays_stop_d1").fetchSemanticsNodes().size)
        onNodeWithTag("displays_row_d1").assertIsDisplayed()
    }
}
