// Navigation, back and keep-alive on the ONE root (cluster G8).
//
// `AppShellTest` (moved from `:desktop`) covers the wide/Expanded shell it always covered; this
// suite is the new surface G8 added — Nav3 push/pop with a REAL back gesture under Compact, the
// full-pane overlays under Expanded, keep-alive across list⇄chat, the launcher as a route on both
// hosts, and the Usage fold.
package dev.supermux.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.backhandler.LocalCompatNavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.state.HostStore
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.chat.testHostStore
import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.session.SessionListMode
import dev.supermux.workspace.singleViewLayout
import dev.supermux.workspace.toDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class SupermuxAppNavTest {

    private fun oneWorkspaceStore(): HostStore = testHostStore().also { app ->
        app.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude")),
                workspaces = listOf(
                    WorkspaceDto(
                        id = "w1", name = "proj", workdir = "/w",
                        primarySessionId = "s1",
                        layout = singleViewLayout("g1", "v1").toDto(),
                        views = listOf(
                            ViewDto(
                                id = "v1", workspaceId = "w1", kind = "chat",
                                state = JsonObject(mapOf("sessionId" to JsonPrimitive("s1"))),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    // ── Compact: push / pop, and a REAL predictive back gesture ────────────────────────────────

    @Test fun compact_back_pops_a_pushed_route_before_it_leaves_home() = runComposeUiTest {
        val ui = ShellUiState()
        val app = testHostStore()
        val input = ShellBackInput()
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher().apply { addInput(input) }
        }
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            CompositionLocalProvider(LocalCompatNavigationEventDispatcherOwner provides owner) {
                SupermuxApp(fleet = rememberTestFleet(app), ui = ui)
            }
        }
        waitForIdle()

        ui.openSettings(SettingsSection.Voice)
        waitForIdle()
        assertTrue(ui.settingsOpen)

        runOnIdle { input.back() }
        waitForIdle()
        assertEquals(listOf(Route.Home), ui.backStack.toList())
    }

    @Test fun compact_back_clears_the_selection_before_it_leaves_the_app() = runComposeUiTest {
        val ui = ShellUiState().apply { selectedId = "s1" }
        val app = oneWorkspaceStore()
        val input = ShellBackInput()
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher().apply { addInput(input) }
        }
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            CompositionLocalProvider(LocalCompatNavigationEventDispatcherOwner provides owner) {
                SupermuxApp(fleet = rememberTestFleet(app), ui = ui)
            }
        }
        waitForIdle()
        assertEquals("s1", ui.selectedId)

        // A REAL predictive-back gesture, progress and all — the root's single owner.
        runOnIdle { input.back() }
        waitForIdle()

        assertEquals(null, ui.selectedId)
        assertEquals(listOf(Route.Home), ui.backStack.toList())
    }

    // ── Compact: the launcher and Usage are REAL screens ───────────────────────────────────────

    @Test fun the_launcher_is_a_route_under_compact_and_a_detail_pane_when_wide() = runComposeUiTest {
        val ui = ShellUiState()
        val app = testHostStore()
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui)
        }
        waitForIdle()
        ui.openLauncher()
        waitForIdle()
        assertEquals(Route.NewSession(), ui.currentRoute)
        // Compact draws the whole screen; there is no detail-pane wrapper to tag.
        onNodeWithTag("launcher_overlay").assertDoesNotExist()
        onNodeWithTag("launcher_message").assertIsDisplayed()
    }

    @Test fun the_usage_route_keeps_the_workspace_layer_composed_when_wide() = runComposeUiTest {
        val ui = ShellUiState().apply { selectedId = "s1" }
        val app = oneWorkspaceStore()
        setPlatformContent {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()
        onNodeWithTag("workspace-layer-w1").assertIsDisplayed()

        ui.openUsage()
        waitForIdle()
        // A popover, not a push: the workspace keeps its size (unlike a full-pane route).
        assertTrue(ui.usageOpen)
        assertTrue(onNodeWithTag("workspace-layer-w1").getBoundsInRoot().width > 0.dp)

        ui.closeUsage()
        waitForIdle()
        assertFalse(ui.usageOpen)
    }

    // ── Expanded: a full-pane overlay hides the workspace without disposing it ─────────────────

    @Test fun a_full_pane_overlay_zero_sizes_the_workspace_and_restores_it_on_back() = runComposeUiTest {
        val ui = ShellUiState().apply { selectedId = "s1" }
        val app = oneWorkspaceStore()
        setPlatformContent {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()
        onNodeWithTag("workspace-layer-w1").assertIsDisplayed()

        ui.navigate(Route.Displays)
        waitForIdle()
        onNodeWithTag("displays_overlay").assertIsDisplayed()
        assertEquals(0.dp, onNodeWithTag("workspace-layer-w1").getBoundsInRoot().width)

        ui.goBack()
        waitForIdle()
        onNodeWithTag("workspace-layer-w1").assertIsDisplayed()
    }

    // ── Compact: the workspace survives list ⇄ chat ────────────────────────────────────────────

    @Test fun the_workspace_layer_survives_going_back_to_the_list_and_returning() = runComposeUiTest {
        val ui = ShellUiState().apply { selectedId = "s1" }
        val app = oneWorkspaceStore()
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui)
        }
        waitForIdle()
        onNodeWithTag("phone_workspace_tabs").assertIsDisplayed()

        ui.selectedId = null
        waitForIdle()
        // The list is on top; the workspace layer is still COMPOSED (keep-alive), just hidden.
        onNodeWithTag("workspace-layer-w1").assertExists()

        ui.selectedId = "s1"
        waitForIdle()
        onNodeWithTag("phone_workspace_tabs").assertIsDisplayed()
    }
}

/** Fires a real completed back gesture at whatever `BackHandler`s the composition registered. */
private class ShellBackInput : NavigationEventInput() {
    fun back() {
        dispatchOnBackStarted(NavigationEvent())
        dispatchOnBackProgressed(NavigationEvent(progress = 1f))
        dispatchOnBackCompleted()
    }
}
