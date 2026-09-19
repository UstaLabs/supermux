package dev.supermux.ui.shell

import dev.supermux.ui.TestIds
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.chat.TestWalkthroughSeam
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.UiPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import dev.supermux.state.HostStore
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import dev.supermux.workspace.singleViewLayout
import dev.supermux.workspace.toDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.supermux.ui.nav.Route
import dev.supermux.ui.session.SessionListMode
import dev.supermux.host.WorkspaceViewingSnapshot
import dev.supermux.host.visibleWorkspaceChatIds
import dev.supermux.net.BrokerApi

/**
 * The shared root (cluster G8). Moved from `:desktop`'s `AppShellTest` with its case names when
 * `AppShell` became `SupermuxApp` in `:ui`; the harness is the same one it always had, with the
 * store wrapped in a one-record [dev.supermux.state.FleetStore] (the root takes a fleet, which is
 * what desktop's `app` was resolved from anyway).
 *
 * M4a Task 5 — wiring the launcher into the app shell. The shell wasn't previously
 * UI-tested (its detail pane, [SessionDetail], drags in the JCEF-backed editor); this suite adds
 * the minimal harness needed to exercise the launcher overlay without ever selecting a session
 * (so [SessionDetail]/JCEF never mounts): a real [HostStore] (connectOnInit=false, HTTP via
 * a ktor MockEngine, outbound WS frames captured through `sendFrameOverride`) and a real
 * [ShellUiState]; the prefs are the in-memory settings store, so no test ever touches a real
 * profile directory.
 *
 * Covers: onNewSession (rail `+` → detail-pane launcher; Ctrl+N/menu flip the SAME
 * `ui.launcherOpen`) opens the overlay; a submit whose `createSessionWithFirstMessage` resolves
 * selects the session, sends the first message, and closes the overlay; a submit that resolves to
 * null (invalid workdir) keeps the overlay open and surfaces the inline error; Escape closes
 * without spawning and leaves the draft on disk.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AppShellTest {

    /** A [HostStore] whose HTTP answers /paths/validate and /sessions (spawn); outbound WS
     *  frames (e.g. the first-message Send) land in [sent] instead of a live socket. */
    private fun appFor(sent: MutableList<ClientFrame>, validateOk: Boolean = true, spawnId: String = "sess-new"): HostStore {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when (req.url.encodedPath) {
                "/paths/validate" -> respond(
                    """{"ok":$validateOk,"path":${if (validateOk) "\"/resolved\"" else "null"}}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                "/sessions" -> respond(
                    """{"id":"$spawnId","name":"feat-x","workdir":"/resolved","agent":"claude"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                else -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = CoroutineScope(Dispatchers.Default), // real clock: BrokerApi.spawn uses withTimeout
            deps = shellTestDeps(),
            connectOnInit = false,
            sendFrameOverride = { sent.add(it) },
            apiOverride = api,
            walkthroughSeam = TestWalkthroughSeam,
        )
    }

    private fun twoWorkspaceApp(sent: MutableList<ClientFrame> = mutableListOf()): HostStore =
        appFor(sent).also { app ->
            val workspaceSessions = listOf("w1" to "s1", "w2" to "s2")
            app.reduce(
                ServerFrame.Snapshot(
                    sessions = workspaceSessions.map { (_, sessionId) ->
                        dev.supermux.proto.SessionInfo(
                            id = sessionId,
                            name = "worker-$sessionId",
                            workdir = "/$sessionId",
                            agent = "claude",
                        )
                    },
                    workspaces = workspaceSessions.map { (workspaceId, sessionId) ->
                        dev.supermux.proto.WorkspaceDto(
                            id = workspaceId,
                            name = "project-$workspaceId",
                            workdir = "/$workspaceId",
                            primarySessionId = sessionId,
                            layout = singleViewLayout("g-$workspaceId", "v-$workspaceId").toDto(),
                            views = listOf(
                                dev.supermux.proto.ViewDto(
                                    id = "v-$workspaceId",
                                    workspaceId = workspaceId,
                                    kind = "chat",
                                    state = kotlinx.serialization.json.JsonObject(
                                        mapOf(
                                            "sessionId" to kotlinx.serialization.json.JsonPrimitive(sessionId),
                                        ),
                                    ),
                                ),
                            ),
                        )
                    },
                ),
            )
        }

    // Cluster G8: viewing presence runs on `:shared`'s own snapshot shape (Android's, which
    // already carried the resolved chat ids) rather than AppShell's local layout+views copy.
    private fun viewingSnapshot(
        workspaceId: String = "w1",
        sessionId: String = "s1",
        appForeground: Boolean = true,
    ) = WorkspaceViewingSnapshot(
        workspaceId = workspaceId,
        visibleChatSessionIds = listOf(sessionId),
        appForeground = appForeground,
    )

    @Test fun on_new_session_opens_the_launcher_overlay() = runComposeUiTest {
        val ui = ShellUiState().apply { sidebarCollapsed = true } // rail mode → TestIds.NEW_SESSION
        val app = appFor(mutableListOf())
        setPlatformContent {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()
        onNodeWithTag("launcher_overlay").assertDoesNotExist()

        onNodeWithTag(TestIds.NEW_SESSION).performClick()
        waitForIdle()

        assertTrue(ui.launcherOpen)
        onNodeWithTag("launcher_overlay").assertIsDisplayed()
        onNodeWithTag("launcher_message").assertIsDisplayed()
    }

    @Test fun submit_with_a_resolved_id_selects_sends_and_closes() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val app = appFor(sent, spawnId = "sess-new")
        val ui = ShellUiState().apply { openLauncher() }
        // The draft lives in the SHARED settings store since cluster F1 (`UiPrefs`), not in
        // `launcher-state.json` — that file is only the one-way migration source now.
        val prefs = UiPrefs(InMemorySettingsStore())
        setPlatformContent(uiPrefs = prefs) {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput("hello there")
        onNodeWithTag("launcher_submit").performClick()
        // The spawn runs on a real dispatcher (see the scope in appFor), so waitForIdle()
        // returns before it lands — wait for the effect, not for composition to settle.
        // Bound generously: under full-suite load the default 1s waitUntil flakes (order/timing).
        waitUntil(timeoutMillis = 10_000) { ui.selectedId != null }
        waitForIdle()

        assertEquals("sess-new", ui.selectedId)
        assertFalse(ui.launcherOpen)
        onNodeWithTag("launcher_overlay").assertDoesNotExist()

        // The BROKER delivers the first message (POST /sessions `firstMessage`), so neither the
        // shell nor the chat that opens sends it — a client Send would deliver it twice.
        assertTrue(sent.filterIsInstance<ClientFrame.Send>().isEmpty())
        assertEquals(null, app.consumePendingFirst("sess-new"))

        // The screen's own post-onSubmit onClearDraft() must win even though `ui.launcherOpen =
        // false` (set by OUR onSubmit, before returning) disposes the SessionLauncherScreen
        // composable right around the same time — the exact race the T4 header note calls "no
        // longer load-bearing". Assert the draft actually landed cleared on disk, not just that
        // the overlay went away.
        assertEquals("", runBlocking { prefs.launcherDraft.first().text })
    }

    @Test fun submit_with_a_null_id_keeps_the_overlay_open_and_surfaces_the_error() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val app = appFor(sent, validateOk = false) // invalid workdir → createSessionWithFirstMessage returns null
        val ui = ShellUiState().apply { openLauncher() }
        setPlatformContent {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput("hello there")
        onNodeWithTag("launcher_submit").performClick()
        // Same real-dispatcher wait as above; here the observable outcome is the error row.
        waitUntil { onAllNodesWithTag("launcher_error").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()

        assertNull(ui.selectedId)
        assertTrue(ui.launcherOpen) // stays open — the caller never got an id to select/close on
        onNodeWithTag("launcher_overlay").assertIsDisplayed()
        onNodeWithTag("launcher_error").assertIsDisplayed()
        assertTrue(sent.filterIsInstance<ClientFrame.Send>().isEmpty()) // never sent a first message
    }

    @Test fun escape_closes_without_spawning_and_the_draft_survives_on_disk() = runComposeUiTest {
        val sent = mutableListOf<ClientFrame>()
        val app = appFor(sent)
        val ui = ShellUiState().apply { openLauncher() }
        val prefs = UiPrefs(InMemorySettingsStore())
        setPlatformContent(uiPrefs = prefs) {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()
        onNodeWithTag("launcher_message").performTextInput("a draft in progress")
        waitForIdle()

        onNodeWithTag("launcher_overlay").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertFalse(ui.launcherOpen)
        assertNull(ui.selectedId) // no session was ever created
        onNodeWithTag("launcher_overlay").assertDoesNotExist()
        // Never spawned/sent — AppShell's own viewing-presence heartbeat may still emit a
        // Viewing frame (unrelated to the launcher), so check the Send frame specifically.
        assertTrue(sent.filterIsInstance<ClientFrame.Send>().isEmpty())
        // The dispose-flush (T4) persists the in-progress text on the way out.
        assertEquals("a draft in progress", runBlocking { prefs.launcherDraft.first().text })
    }

    @Test fun shell_shortcuts_are_gated_off_while_the_launcher_overlay_is_up() = runComposeUiTest {
        // The overlay is modal — a pane/sidebar chord (Ctrl+B) it leaves unhandled must NOT bubble
        // to shellShortcuts and silently mutate the layout behind it. Ctrl+B typed while the
        // launcher's message field is focused should be a no-op on ui.sidebarCollapsed.
        val app = appFor(mutableListOf())
        val ui = ShellUiState().apply { openLauncher() } // sidebarCollapsed defaults false
        setPlatformContent {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()
        assertFalse(ui.sidebarCollapsed) // precondition

        // Focus a node inside the overlay, then send Ctrl+B — it bubbles up to the root Box, where
        // shellShortcuts is gated OFF (…else Modifier) while launcherOpen.
        onNodeWithTag("launcher_message").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        waitForIdle()

        assertFalse(ui.sidebarCollapsed) // NOT toggled — the chord never reached the layout
        assertTrue(ui.launcherOpen)             // ...and the overlay stayed up
    }

    // ── M5-3 notifications ──────────────────────────────────────────────────────────────────
    // Deliberately does NOT assert on the "selected AND focused → suppressed" case here — this
    // Compose test harness's `LocalWindowInfo.current.isWindowFocused` value under
    // `runComposeUiTest` isn't a documented guarantee, and asserting on it would make the test
    // environment-fragile. That exact interaction is exhaustively covered by NotifyDecisionTest
    // (Task 1) with a fully-controlled `windowFocused` boolean; these two tests only assert on
    // conditions that hold true REGARDLESS of the test harness's focus reporting: an unviewed
    // session's reply notifies, and mute suppresses unconditionally.

    private class RecordingNotificationManager : dev.supermux.ui.platform.NotificationManager {
        val calls = mutableListOf<Triple<String, String, String>>()
        override fun notify(sessionId: String, title: String, message: String) {
            calls.add(Triple(sessionId, title, message))
        }
    }

    @Test fun an_unviewed_sessions_agent_reply_notifies_via_the_injected_manager() = runComposeUiTest {
        val app = appFor(mutableListOf())
        app.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(dev.supermux.proto.SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude")),
            ),
        )
        val ui = ShellUiState().apply { selectedId = "other-session" } // s1 is NOT selected
        val fakeManager = RecordingNotificationManager()
        val notify = dev.supermux.ui.notify.NotificationController(fakeManager)
        setPlatformContent {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, notify = notify, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()

        app.reduce(
            ServerFrame.MessageAppend(
                session = "s1",
                entry = dev.supermux.proto.LogEntry(
                    id = "m1", ts = "2026-07-10T00:00:00Z", direction = "outbound", op = "reply", text = "all done",
                ),
            ),
        )
        waitForIdle()

        assertEquals(1, fakeManager.calls.size)
        assertEquals(Triple("s1", "worker-1", "all done"), fakeManager.calls.single())
    }

    @Test fun a_muted_sessions_reply_does_not_notify_even_when_unviewed() = runComposeUiTest {
        val app = appFor(mutableListOf())
        app.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(
                    dev.supermux.proto.SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude", mute = true),
                ),
            ),
        )
        val ui = ShellUiState().apply { selectedId = "other-session" }
        val fakeManager = RecordingNotificationManager()
        val notify = dev.supermux.ui.notify.NotificationController(fakeManager)
        setPlatformContent {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, notify = notify, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()

        app.reduce(
            ServerFrame.MessageAppend(
                session = "s1",
                entry = dev.supermux.proto.LogEntry(
                    id = "m2", ts = "2026-07-10T00:00:01Z", direction = "outbound", op = "reply", text = "muted work",
                ),
            ),
        )
        waitForIdle()

        assertTrue(fakeManager.calls.isEmpty())
    }

    // ── One shell ───────────────────────────────────────────────────────────────────────────
    // Selecting a session used to hand the detail pane to SessionDetail unless SM_WORKSPACES was
    // set. There is no flag and no second shell: the detail pane is the selected session's
    // WORKSPACE, drawn by PaneHost. These two cases pin that, and the "no workspace yet" prompt
    // that replaced the old one-session fallback.

    // `runComposeUiTest` does not guarantee LocalWindowInfo focus, so asserting captured Viewing
    // frames here would be environment-dependent. These tests call the pure decision helper used
    // by AppShell's real presence effect and deterministically cover every visibility gate.

    @Test fun matchingVisibleFocusedWorkspaceSnapshotReturnsItsActiveChatIds() {
        assertEquals(
            listOf("s1"),
            visibleWorkspaceChatIds(
                surfaceVisible = true,
                selectedWorkspaceId = "w1",
                snapshot = viewingSnapshot(),
            ),
        )
    }

    @Test fun launcherCoveredWorkspaceReturnsNoVisibleChatIds() {
        // The launcher is `Route.NewSession` now, so it hides the workspace layer through
        // [workspaceLayerVisible] — which is what `surfaceVisible` carries here.
        assertFalse(
            workspaceLayerVisible(
                currentRoute = Route.NewSession(),
                usageIsPopover = true,
                selectedSessionAvailable = true,
                activeWorkspaceAvailable = true,
            ),
        )
        assertEquals(
            emptyList(),
            visibleWorkspaceChatIds(
                surfaceVisible = false,
                selectedWorkspaceId = "w1",
                snapshot = viewingSnapshot(),
            ),
        )
    }

    @Test fun staleSnapshotFromPreviousWorkspaceReturnsNoVisibleChatIds() {
        assertEquals(
            emptyList(),
            visibleWorkspaceChatIds(
                surfaceVisible = true,
                selectedWorkspaceId = "w2",
                snapshot = viewingSnapshot(workspaceId = "w1", sessionId = "s1"),
            ),
        )
    }

    @Test fun selectedSessionWithoutWorkspaceSnapshotReturnsNoVisibleChatIds() {
        assertEquals(
            emptyList(),
            visibleWorkspaceChatIds(
                surfaceVisible = false,
                selectedWorkspaceId = null,
                snapshot = null,
            ),
        )
    }

    @Test fun unfocusedWorkspaceReturnsNoVisibleChatIds() {
        // "Unfocused" is `appForeground = false`, which the snapshot itself now carries.
        assertEquals(
            emptyList(),
            visibleWorkspaceChatIds(
                surfaceVisible = true,
                selectedWorkspaceId = "w1",
                snapshot = viewingSnapshot(appForeground = false),
            ),
        )
    }

    @Test fun addHostOverlayHidesWorkspaceLayer() {
        // Add-host is `Route.AddHost` since G8 — a route, not a boolean beside the stack.
        assertFalse(
            workspaceLayerVisible(
                currentRoute = Route.AddHost,
                usageIsPopover = true,
                selectedSessionAvailable = true,
                activeWorkspaceAvailable = true,
            ),
        )
        // ...and the Usage card still leaves it visible on a wide host (an anchored popover),
        // while a Compact host pushes a whole screen over it.
        assertTrue(
            workspaceLayerVisible(
                currentRoute = Route.Usage,
                usageIsPopover = true,
                selectedSessionAvailable = true,
                activeWorkspaceAvailable = true,
            ),
        )
        assertFalse(
            workspaceLayerVisible(
                currentRoute = Route.Usage,
                usageIsPopover = false,
                selectedSessionAvailable = true,
                activeWorkspaceAvailable = true,
            ),
        )
    }

    @Test fun switchingWorkspacesKeepsThePreviousWorkspaceLayerMountedAtZeroSize() = runComposeUiTest {
        val app = twoWorkspaceApp()
        val ui = ShellUiState().apply { selectedId = "s1" }
        setPlatformContent {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()

        onNodeWithTag("workspace-layer-w1").assertIsDisplayed()

        ui.selectedId = "s2"
        waitForIdle()

        assertEquals(0.dp, onNodeWithTag("workspace-layer-w1").getBoundsInRoot().width)
        onNodeWithTag("workspace-layer-w2").assertIsDisplayed()

        ui.selectedId = "s1"
        waitForIdle()

        onNodeWithTag("workspace-layer-w1").assertIsDisplayed()
    }

    @Test fun fullPaneRouteKeepsWorkspaceMountedAtZeroSizeAndRestoresItOnBack() = runComposeUiTest {
        val app = twoWorkspaceApp()
        val ui = ShellUiState().apply { selectedId = "s1" }
        setPlatformContent {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()

        onNodeWithTag("workspace-layer-w1").assertIsDisplayed()

        ui.openAppUpdate()
        waitForIdle()

        assertEquals(0.dp, onNodeWithTag("workspace-layer-w1").getBoundsInRoot().width)

        ui.goBack()
        waitForIdle()

        onNodeWithTag("workspace-layer-w1").assertIsDisplayed()
    }

    @Test fun selecting_a_session_draws_its_workspace() = runComposeUiTest {
        val app = appFor(mutableListOf())
        app.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(
                    dev.supermux.proto.SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude"),
                ),
                workspaces = listOf(
                    dev.supermux.proto.WorkspaceDto(
                        id = "w1", name = "proj", workdir = "/w",
                        primarySessionId = "s1",
                        layout = singleViewLayout("g1", "v1").toDto(),
                        views = listOf(
                            dev.supermux.proto.ViewDto(
                                id = "v1", workspaceId = "w1", kind = "chat",
                                state = kotlinx.serialization.json.JsonObject(
                                    mapOf("sessionId" to kotlinx.serialization.json.JsonPrimitive("s1")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val ui = ShellUiState().apply { selectedId = "s1" }
        setPlatformContent {
            SupermuxApp(fleet = rememberTestFleet(app), ui = ui, sessionListMode = SessionListMode.Workspaces)
        }
        waitForIdle()
        onNodeWithTag("workspace_layout_host").assertIsDisplayed()
        onNodeWithTag("view_chat").assertIsDisplayed()
    }

    @Test fun a_session_with_no_workspace_says_so_rather_than_falling_back_to_a_one_session_view() =
        runComposeUiTest {
            val app = appFor(mutableListOf())
            app.reduce(
                ServerFrame.Snapshot(
                    sessions = listOf(
                        dev.supermux.proto.SessionInfo(id = "s1", name = "worker-1", workdir = "/w", agent = "claude"),
                    ),
                ),
            )
            val ui = ShellUiState().apply { selectedId = "s1" }
            setPlatformContent {
                SupermuxApp(fleet = rememberTestFleet(app), ui = ui, sessionListMode = SessionListMode.Workspaces)
            }
            waitForIdle()
            onNodeWithTag("workspace_welcome").assertIsDisplayed()
        }
}
