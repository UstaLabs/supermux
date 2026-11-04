package dev.supermux.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.ui.TestIds
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The collapsed sidebar: what it draws, and the list beside it — opened by hovering a project
 * (menu-bar style: the next tile switches it at once) or by a tap on touch.
 */
@OptIn(ExperimentalTestApi::class)
class SidebarRailTest {

    private fun row(id: String, sid: String, unread: Boolean = false, active: Boolean = false, ts: String? = null) =
        RailWorkspace(id = id, name = "Workspace $id", sessionId = sid, working = false, unread = unread, active = active, settled = false, lastTs = ts)

    private val supermux = RailProject("p:h:sm", "Supermux", listOf(row("w1", "s1", unread = true), row("w2", "s2", active = true)))
    private val website = RailProject("p:h:web", "Supermux Website", listOf(row("w3", "s3")))
    private val archive = RailProject("p:h:old", "Old Thing", listOf(row("w4", "s4", ts = "2026-11-01T10:00:00Z")))
    private val beta = RailProject("p:h:beta", "Beta", listOf(row("w5", "s5", ts = "2026-11-03T10:00:00Z")))
    private val alpha = RailProject("p:h:alpha", "Alpha", listOf(row("w6", "s6", ts = "2026-10-01T10:00:00Z")))
    private val model = SidebarRailModel(
        agents = listOf(
            RailAgent("a1", "pa1", "Tally the TPM", working = false, unread = true, active = false, online = true, dozing = false, preview = null),
            RailAgent("a2", "pa2", "Dockie", working = false, unread = false, active = false, online = false, dozing = true, preview = null),
        ),
        all = listOf(supermux, website, archive, beta, alpha),
        inUse = setOf(supermux.key, website.key),
    )

    private fun ComposeUiTest.rail(
        opened: MutableList<String> = mutableListOf(),
        pointer: Boolean = true,
        onNew: () -> Unit = {},
        onShowSidebar: () -> Unit = {},
        // 320 dp: room for 2 Agents and 4 tiles — the 2 in use, the most recent other, All projects.
        height: Dp = 320.dp,
    ) = setContent {
        CompositionLocalProvider(LocalPointerAvailable provides pointer) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                Box(Modifier.size(900.dp, height)) {
                    SidebarRail(
                        model = model,
                        avatars = emptyMap(),
                        onNewSession = onNew,
                        onOpenSession = { opened += it },
                        onShowSidebar = onShowSidebar,
                        footer = {},
                    )
                }
            }
        }
    }

    @Test
    fun whenRoomIsShortTheProjectsInUseAndTheMostRecentOtherGetATile() = runComposeUiTest {
        var created = 0
        val opened = mutableListOf<String>()
        rail(opened, onNew = { created++ })
        onNodeWithTag(TestIds.NEW_SESSION).assertIsDisplayed().performClick()
        onNodeWithTag("rail_agent_a1").assertIsDisplayed().performClick()
        onNodeWithTag("rail_agent_a2").assertIsDisplayed()
        onNodeWithTag("rail_project_p:h:sm").assertIsDisplayed()
        onNodeWithTag("rail_project_p:h:web").assertIsDisplayed()
        onNodeWithTag("rail_project_p:h:beta").assertIsDisplayed()
        onNodeWithTag("rail_project_p:h:old").assertDoesNotExist()
        onNodeWithTag("rail_project_p:h:alpha").assertDoesNotExist()
        onNodeWithTag("rail_all_projects").assertIsDisplayed()
        assertEquals(1, created)
        assertEquals(listOf("pa1"), opened, "an Agent opens its own chat")
    }

    @Test
    fun withRoomToSpareEveryProjectGetsATileAndThereIsNoAllButton() = runComposeUiTest {
        rail(height = 700.dp)
        for (key in listOf("sm", "web", "old", "beta", "alpha")) onNodeWithTag("rail_project_p:h:$key").assertIsDisplayed()
        onNodeWithTag("rail_all_projects").assertDoesNotExist()
    }

    @Test
    fun hoveringAProjectShowsItsListAndTheNextTileSwitchesIt() = runComposeUiTest {
        val opened = mutableListOf<String>()
        rail(opened)
        onNodeWithTag("rail_flyout_p:h:sm").assertDoesNotExist()

        onNodeWithTag("rail_project_p:h:sm").performMouseInput { moveTo(Offset(2f, 2f)); moveTo(center) }
        // The first list waits a beat, so a pointer crossing the rail opens nothing. (waitForIdle
        // does not run a pending delay down: the clock has to be moved.)
        mainClock.advanceTimeBy(60)
        onNodeWithTag("rail_flyout_p:h:sm").assertDoesNotExist()
        mainClock.advanceTimeBy(100)
        waitForIdle()
        onNodeWithTag("rail_flyout_p:h:sm").assertIsDisplayed()
        onNodeWithText("1 new").assertIsDisplayed()

        onNodeWithTag("rail_project_p:h:web").performMouseInput { moveTo(Offset(2f, 2f)); moveTo(center) }
        waitForIdle()
        onNodeWithTag("rail_flyout_p:h:web").assertIsDisplayed()
        onNodeWithTag("rail_flyout_p:h:sm").assertDoesNotExist()

        onNodeWithTag("rail_row_w3").performClick()
        waitForIdle()
        assertEquals(listOf("s3"), opened, "a row opens its workspace's chat")
        onNodeWithTag("rail_flyout_p:h:web").assertDoesNotExist()
    }

    @Test
    fun onTouchATapOpensTheList() = runComposeUiTest {
        var shown = 0
        rail(pointer = false, onShowSidebar = { shown++ })
        onNodeWithTag("rail_project_p:h:sm").performClick()
        waitForIdle()
        onNodeWithTag("rail_flyout_p:h:sm").assertIsDisplayed()
        onNodeWithText("Show in sidebar").performClick()
        waitForIdle()
        assertEquals(1, shown)
        onNodeWithTag("rail_flyout_p:h:sm").assertDoesNotExist()
    }

    @Test
    fun allProjectsListsEveryProjectAndDrillsIn() = runComposeUiTest {
        val opened = mutableListOf<String>()
        rail(opened, pointer = false)
        onNodeWithTag("rail_all_projects").performClick()
        waitForIdle()
        onNodeWithTag("rail_flyout_all").assertIsDisplayed()
        onNodeWithText("Old Thing").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag("rail_flyout_p:h:old").assertIsDisplayed()
        onNodeWithTag("rail_row_w4").performClick()
        waitForIdle()
        assertEquals(listOf("s4"), opened)
    }
}
