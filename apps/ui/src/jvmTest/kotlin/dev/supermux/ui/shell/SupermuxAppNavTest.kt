// Navigation, back and keep-alive on the ONE root (cluster G8).
//
// `AppShellTest` (moved from `:desktop`) covers the wide/Expanded shell it always covered; this
// suite is the new surface G8 added — Nav3 push/pop with a REAL back gesture under Compact, the
// full-pane overlays under Expanded, keep-alive across list⇄chat, the launcher as a route on both
// hosts, and the Usage fold.
package dev.supermux.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.InternalComposeUiApi
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
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

    /**
     * A selected session that resolves to NOTHING must still draw a way out.
     *
     * The route in is real: `pushTapHandleDecision` deliberately applies a tapped notification
     * before the session list has arrived, so the shell can open the chat on a cold start. If the
     * session never turns up — it was killed, or its host is offline and the list is empty — the
     * compact branch used to render nothing whatsoever: no header, no back. iOS is where that
     * costs the most, because it is the host that supplies no `chatFallback` — so the screen had
     * no affordance of its own AND the edge swipe cannot help, since the gesture is gated on the
     * very handler this state fails to register (H4 measured the recogniser: disabled with nothing
     * to pop). A chrome-less pane there is a force-quit.
     *
     * `chatFallback` is deliberately NOT supplied here, which is exactly the iOS configuration.
     */
    @Test fun compact_a_selected_session_that_does_not_exist_still_offers_a_way_back() = runComposeUiTest {
        val ui = ShellUiState().apply { selectedId = "ghost" }
        val app = testHostStore()
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui)
        }
        waitForIdle()

        onNodeWithTag("session_unavailable").assertIsDisplayed()

        onNodeWithTag("session_unavailable_back").performClick()
        waitForIdle()

        assertEquals(null, ui.selectedId)
    }

    @Test fun compact_back_pops_a_pushed_route_before_it_leaves_home() = runComposeUiTest {
        val ui = ShellUiState()
        val app = testHostStore()
        val input = ShellBackInput()
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher().apply { addInput(input) }
        }
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                SupermuxApp(fleet = rememberTestFleet(app), ui = ui)
            }
        }
        waitForIdle()

        // The DEFAULT section on purpose. This test is about the route stack, and the hub now
        // lands a phone straight on any section other than the default — with `Voice` here, the
        // first gesture would pop the hub's own detail to the index and never reach the route.
        ui.openSettings()
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
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
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
        // Both hosts' names for the wide tree renderer resolve (Android's + desktop's).
        onNodeWithTag("tablet_pane_host").assertIsDisplayed()
        onNodeWithTag("workspace_layout_host").assertIsDisplayed()
        onNodeWithTag("phone_workspace_tabs").assertDoesNotExist()

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

    /**
     * Opening a settings section on a phone must actually OPEN it.
     *
     * The hub keeps its compact push stack in a plain `remember`, and it used to also report the
     * section it opened back to the shell, which writes it into `Route.Settings`. That route IS
     * NavDisplay's content key for the entry, so the write disposed the entry and composed a fresh
     * hub — back to the index, before the detail had drawn a frame. Every settings section on a
     * phone was unreachable, and the row read as one that does not respond to a tap.
     *
     * The `settingsSection` slot is supplied here because it is the only thing that draws a
     * section body at all; the assertions are on the hub's own chrome, so they hold whatever that
     * body turns out to contain.
     */
    @Test fun compact_opening_a_settings_section_stays_open() = runComposeUiTest {
        val ui = ShellUiState()
        val app = testHostStore()
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            SupermuxApp(
                fleet = rememberTestFleet(app),
                ui = ui,
                settingsSection = { section, _ -> Text("body:" + section.name) },
            )
        }
        waitForIdle()

        ui.openSettings(SettingsSection.Agents)
        waitForIdle()
        onNodeWithTag("settings_row_voice").assertIsDisplayed()

        onNodeWithTag("settings_row_voice").performClick()
        waitForIdle()
        onNodeWithTag("settings_hub_detail").assertIsDisplayed()
        onNodeWithTag("settings_row_voice").assertDoesNotExist()
        // The CAUSE, not just the symptom: the route was never rewritten. `settingsSection` reads
        // the live `Route.Settings` off the stack, so anything other than the section it was
        // opened with means the compact push reported itself back and the entry was re-keyed.
        assertEquals(SettingsSection.Agents, ui.settingsSection)
    }

    /**
     * A deep link to a section — `openLspSettings`, `settings/lsp` — must LAND on that section on a
     * phone, not on the index with `ShellUiState.lspSettingsOpen` claiming otherwise.
     *
     * The hub seeds its compact push stack from `section` for exactly this, treating the
     * `Route.Settings` default ([SettingsSection.Agents]) as "just Settings" and anything else as a
     * destination. That seeding is only safe because the settings entry has a stable content key;
     * while the route WAS the key, a rewrite would have re-seeded and reopened the section under
     * the user.
     */
    @Test fun compact_a_deep_link_opens_the_section_itself() = runComposeUiTest {
        val ui = ShellUiState()
        val app = testHostStore()
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            SupermuxApp(
                fleet = rememberTestFleet(app),
                ui = ui,
                settingsSection = { section, _ -> Text("body:" + section.name) },
            )
        }
        waitForIdle()

        ui.openLspSettings()
        waitForIdle()

        onNodeWithTag("settings_hub_detail").assertIsDisplayed()
        onNodeWithText("body:" + SettingsSection.EditorLsp.name).assertIsDisplayed()
        // The index is BEHIND us, not under us.
        onNodeWithTag("settings_row_voice").assertDoesNotExist()
        assertTrue(ui.lspSettingsOpen)

        // And back still returns to the index rather than closing the hub outright.
        onNodeWithTag("settings_detail_back").performClick()
        waitForIdle()
        onNodeWithTag("settings_row_voice").assertIsDisplayed()
        assertTrue(ui.settingsOpen)
    }

    /**
     * A rail click must not rebuild the hub.
     *
     * `ShellUiState.settingsSection` rewrites the stack slot with a NEW `Route.Settings(section)`,
     * and the route is NavDisplay's default content key — so before the entry was given a stable
     * `clazzContentKey`, every rail click disposed the whole entry and composed a fresh one. The
     * visible cost was scroll and selection thrown away on each click; the sharper one was
     * ordering, since the outgoing hub's `DisposableEffect` is free to run AFTER the incoming one
     * registers, leaving the shell's `settingsTryClose` pointing at the UNGUARDED `onBack` and
     * letting Escape walk past the unsaved-edits prompt.
     *
     * The probe is a plain `remember` in the section-body slot. That slot is invoked from ONE call
     * site inside the hub, so a section change only recomposes it with a new argument and the
     * counter survives; disposing the entry takes the counter with it. The count is therefore a
     * direct read of "is this the same hub".
     */
    @Test fun wide_a_rail_click_changes_the_section_without_rebuilding_the_hub() = runComposeUiTest {
        val ui = ShellUiState()
        val app = testHostStore()
        setPlatformContent(pointer = true, widthClass = WindowWidthClass.Expanded) {
            SupermuxApp(
                fleet = rememberTestFleet(app),
                ui = ui,
                settingsSection = { section, _ ->
                    var alive by remember { mutableStateOf(0) }
                    Text(
                        "body:" + section.name + ":" + alive,
                        Modifier.testTag("probe").clickable { alive++ },
                    )
                },
            )
        }
        waitForIdle()

        ui.openSettings(SettingsSection.Agents)
        waitForIdle()
        onNodeWithTag("settings_hub_rail").assertIsDisplayed()
        onNodeWithText("body:" + SettingsSection.Agents.name + ":0").assertIsDisplayed()

        onNodeWithTag("probe").performClick()
        onNodeWithTag("probe").performClick()
        waitForIdle()
        onNodeWithText("body:" + SettingsSection.Agents.name + ":2").assertIsDisplayed()

        onNodeWithTag("settings_section_devices").performClick()
        waitForIdle()

        // The section changed — and the hub did not.
        assertEquals(SettingsSection.Devices, ui.settingsSection)
        onNodeWithText("body:" + SettingsSection.Devices.name + ":2").assertIsDisplayed()
    }

    /**
     * The phone tab strip is ONE line, with ONE close button per tab.
     *
     * M3's `text` + `icon` slots stack vertically, which spent ~72dp of a phone screen and put the
     * close button ABOVE its own label; the strip moved to the generic `Tab` overload and lays the
     * title and its close button out itself. Pinned here because that overload also opts out of
     * `TabBaselineLayout`, so nothing but this file decides the height any more.
     */
    @Test fun compact_the_workspace_tab_strip_is_one_line_per_tab() = runComposeUiTest {
        val ui = ShellUiState().apply { selectedId = "s1" }
        val app = oneWorkspaceStore()
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui)
        }
        waitForIdle()

        onNodeWithTag("phone_workspace_tab_strip").assertIsDisplayed()
        // One view in the workspace, so exactly ONE close affordance — the stacked `text` + `icon`
        // arrangement this replaced drew the close button on a line of its own.
        assertEquals(1, onAllNodesWithContentDescription("Close ", substring = true).fetchSemanticsNodes().size)
        // One line: 48dp for the tab, plus the status inset, which is 0 in a test window. The
        // stacked arrangement was ~72dp.
        assertEquals(48.dp, onNodeWithTag("phone_workspace_tab_strip").getBoundsInRoot().height)
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
