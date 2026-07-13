package dev.supermux.desktop.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.editor.PendingEditorOpen
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.ProxyDto
import dev.supermux.net.TerminalClient
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI test proving the SessionDetail split tree reacts to the pane toggles the model owns:
 * flipping editor/terminal/display on the [WorkspaceLayout] mounts the matching ComingSoonPane
 * (tagged `pane_editor` / `pane_terminal` / `pane_display`). This is the headless counterpart to
 * the manual "toggle panes via the menu" check — the model is covered by WorkspaceLayoutTest, and
 * this asserts the rendering wired off it. The chat pane (`pane_chat`) is present by default.
 *
 * DesktopAppState is built with `connectOnInit = false` so no WebSocket/HTTP is opened.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SessionDetailTest {
    private fun app() = DesktopAppState(
        baseUrl = "ws://test:9898",
        token = "t",
        scope = TestScope(UnconfinedTestDispatcher()),
        connectOnInit = false,
    )

    private val session =
        SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "claude")

    // The real editor pane embeds KCEF (embedded Chromium) which can't boot under runComposeUiTest —
    // and even constructing it touches the on-disk EditorPrefsStore — so inject a pure-Compose fake
    // via SessionDetail's `editorPanelContent` seam, tagged `pane_editor` like the real panel. Ignores
    // the pendingOpen/onPendingOpenConsumed args — tests that care about the T5 handoff use
    // [fakeEditorCapturingPendingOpen] instead.
    private val fakeEditor: @Composable (PendingEditorOpen?, () -> Unit) -> Unit = { _, _ ->
        Box(Modifier.fillMaxSize().testTag("pane_editor"))
    }

    // Records every pendingOpen the seam was invoked with (across recompositions) and exposes the
    // onPendingOpenConsumed callback so a test can drive the "consumed exactly once" assertion.
    private class PendingOpenLedger {
        val seen = mutableListOf<PendingEditorOpen?>()
        var consumeCalls = 0
        var lastConsume: (() -> Unit)? = null
    }

    private fun fakeEditorCapturingPendingOpen(ledger: PendingOpenLedger): @Composable (PendingEditorOpen?, () -> Unit) -> Unit =
        { pendingOpen, onConsumed ->
            ledger.seen.add(pendingOpen)
            ledger.lastConsume = { ledger.consumeCalls++; onConsumed() }
            Box(Modifier.fillMaxSize().testTag("pane_editor"))
        }

    @Test
    fun togglingWorkPanesMountsPanes() = runComposeUiTest {
        val layout = WorkspaceLayout()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(
                    app = app(),
                    session = session,
                    agent = null,
                    layout = layout,
                    draft = "",
                    onDraftChange = {},
                    editorPanelContent = fakeEditor,
                )
            }
        }

        // Default panes = chat only: chat present, work panes absent.
        onNodeWithTag("pane_chat").assertIsDisplayed()
        onNodeWithTag("pane_editor").assertDoesNotExist()
        onNodeWithTag("pane_terminal").assertDoesNotExist()
        onNodeWithTag("pane_display").assertDoesNotExist()

        // Toggle editor on → the editor pane mounts; chat stays.
        runOnIdle { layout.toggleEditor("s1") }
        onNodeWithTag("pane_editor").assertIsDisplayed()
        onNodeWithTag("pane_chat").assertIsDisplayed()

        // Toggle terminal on → both editor and terminal panes present (vertical split).
        runOnIdle { layout.toggleTerminal("s1") }
        onNodeWithTag("pane_editor").assertIsDisplayed()
        onNodeWithTag("pane_terminal").assertIsDisplayed()

        // Toggle display on → the display pane joins the right area.
        runOnIdle { layout.toggleDisplay("s1") }
        onNodeWithTag("pane_display").assertIsDisplayed()

        // Hide chat (work present, so the invariant allows it) → chat pane leaves the tree.
        runOnIdle { layout.toggleChat("s1") }
        onNodeWithTag("pane_chat").assertDoesNotExist()
        onNodeWithTag("pane_editor").assertIsDisplayed()
    }

    // ── Chat-tap → editor-at-line (M3-T5) ──────────────────────────────────────────────
    //
    // A file-path ref rendered in the transcript (AssistantMessage/mdAnnotated, linkify=true) carries
    // a `LinkAnnotation.Clickable` over its character range — driven end-to-end through the REAL
    // ChatPanel rather than a seam (there is no seam for the timeline itself). Each seeded message
    // body IS the ref (nothing else on the line) so the link's range is knowable ahead of time; a
    // plain `performClick()` lands at the NODE's center, which — because the row is `fillMaxWidth()`
    // — is usually past the short link's actual glyphs and misses the click-annotation hit-test, so
    // these click near the text's top-left instead (`performTouchInput { click(Offset(4f, 4f)) }`).
    //
    // NOTE: `runOnIdle { ... }` around a plain (non-gesture) state write did NOT reliably force a
    // recomposition in this Skiko/JUnit4 test harness (verified empirically) — invoke such writes
    // directly, then call `waitForIdle()` afterward, as [chatTapOpensTheEditorPaneAndDeliversAWorkdirRelativePendingOpen]
    // does for its `ledger.lastConsume` simulation below.

    private fun seedFileRefMessage(app: DesktopAppState, sessionId: String, id: String, text: String) {
        app.reduce(
            ServerFrame.MessageAppend(
                session = sessionId,
                entry = LogEntry(id = id, ts = "2026-07-09T00:00:00Z", direction = "outbound", text = text),
            ),
        )
    }

    @Test
    fun chatTapOpensTheEditorPaneAndDeliversAWorkdirRelativePendingOpen() = runComposeUiTest {
        val layout = WorkspaceLayout()
        val theApp = app()
        seedFileRefMessage(theApp, "s1", "m1", "src/main.kt:42")
        val ledger = PendingOpenLedger()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(
                    app = theApp, session = session, agent = null, layout = layout,
                    draft = "", onDraftChange = {},
                    editorPanelContent = fakeEditorCapturingPendingOpen(ledger),
                )
            }
        }
        onNodeWithTag("pane_editor").assertDoesNotExist()
        assertEquals(emptyList<PendingEditorOpen?>(), ledger.seen) // not mounted yet: pane off

        onNodeWithText("src/main.kt:42").performTouchInput { click(Offset(4f, 4f)) }
        waitForIdle()

        // Pane flips on (Android SessionWorkspaceDetail:174 parity: layout.setPanes(... editor = true)).
        assertEquals(true, layout.panesFor("s1").editor)
        onNodeWithTag("pane_editor").assertIsDisplayed()
        // ...and the target is workdir-relative (session.workdir = "/w/s1"), with the parsed line.
        assertEquals(PendingEditorOpen("src/main.kt", 42, null), ledger.seen.last())

        // The real EditorPanel would consume it after revealing the file; simulate that ack and
        // confirm the seam is re-invoked with pendingOpen == null exactly once (T5 deliverable:
        // "consumed exactly once").
        ledger.lastConsume?.invoke()
        waitForIdle()
        assertEquals(1, ledger.consumeCalls)
        assertEquals(null, ledger.seen.last())
    }

    @Test
    fun aTapWhileThePaneIsAlreadyOpenUpdatesThePendingOpen() = runComposeUiTest {
        val layout = WorkspaceLayout()
        layout.toggleEditor("s1") // pane already open BEFORE any tap
        val theApp = app()
        seedFileRefMessage(theApp, "s1", "m1", "src/a.kt:1")
        seedFileRefMessage(theApp, "s1", "m2", "src/b.kt:9")
        val ledger = PendingOpenLedger()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(
                    app = theApp, session = session, agent = null, layout = layout,
                    draft = "", onDraftChange = {},
                    editorPanelContent = fakeEditorCapturingPendingOpen(ledger),
                )
            }
        }
        onNodeWithTag("pane_editor").assertIsDisplayed() // already open pre-tap

        onNodeWithText("src/a.kt:1").performTouchInput { click(Offset(4f, 4f)) }
        waitForIdle()
        assertEquals(PendingEditorOpen("src/a.kt", 1, null), ledger.seen.last())
        assertEquals(true, layout.panesFor("s1").editor) // stays open, no double-toggle-off

        // A second tap — WITHOUT the first ever being consumed — overwrites the pending target
        // rather than queuing/ignoring it (Android has no queue: the state is a single slot).
        onNodeWithText("src/b.kt:9").performTouchInput { click(Offset(4f, 4f)) }
        waitForIdle()
        assertEquals(PendingEditorOpen("src/b.kt", 9, null), ledger.seen.last())
        assertEquals(true, layout.panesFor("s1").editor)
    }

    @Test
    fun aTapOnAPathOutsideTheWorkdirIsDroppedWithoutOpeningThePane() = runComposeUiTest {
        val layout = WorkspaceLayout()
        val theApp = app()
        seedFileRefMessage(theApp, "s1", "m1", "/etc/motd.txt:5")
        val ledger = PendingOpenLedger()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(
                    app = theApp, session = session, agent = null, layout = layout,
                    draft = "", onDraftChange = {},
                    editorPanelContent = fakeEditorCapturingPendingOpen(ledger),
                )
            }
        }
        onNodeWithText("/etc/motd.txt:5").performTouchInput { click(Offset(4f, 4f)) }
        waitForIdle()

        assertEquals(false, layout.panesFor("s1").editor)
        // The pane never mounts (never toggled on) — the editorPanelContent seam is never invoked.
        onNodeWithTag("pane_editor").assertDoesNotExist()
        assertTrue(ledger.seen.isEmpty())
    }

    // ── Chat|Native toggle (Task 7) ──────────────────────────────────────────────────
    //
    // The Native pane embeds a SwingPanel (DesktopTerminalPanel) which cannot be hosted under
    // runComposeUiTest (no real AWT window), so these tests inject a pure-Compose fake via
    // SessionDetail's `nativePanelContent` seam. The fake tags itself `native_fake` and captures the
    // onExit callback so the exit-fallback path is drivable headlessly.

    private val codexSession =
        SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "codex")

    @Test
    fun toggleShownForClaudeHiddenForOthers() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = session, agent = null,
                    layout = WorkspaceLayout(), draft = "", onDraftChange = {})
            }
        }
        // claude → the labelled pill is present.
        onNodeWithTag("agent_view_chat").assertIsDisplayed()
        onNodeWithTag("agent_view_native").assertIsDisplayed()
    }

    @Test
    fun toggleHiddenForNonClaude() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = codexSession, agent = null,
                    layout = WorkspaceLayout(), draft = "", onDraftChange = {})
            }
        }
        onNodeWithTag("agent_view_chat").assertDoesNotExist()
        onNodeWithTag("agent_view_native").assertDoesNotExist()
        // And the Native pane is never composed for a non-claude session.
        onNodeWithTag("pane_native").assertDoesNotExist()
    }

    @Test
    fun togglingSwapsContentButKeepsChatInTree() = runComposeUiTest {
        val layout = WorkspaceLayout()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = session, agent = null,
                    layout = layout, draft = "", onDraftChange = {},
                    nativePanelContent = fakeNative)
            }
        }
        // Native is lazy: not composed until first opened.
        onNodeWithTag("pane_chat").assertIsDisplayed()
        onNodeWithTag("native_fake").assertDoesNotExist()

        // Flip to Native → the fake renders AND chat STAYS in the tree (keep-alive, not remounted).
        runOnIdle { layout.setNativeView("s1", true) }
        onNodeWithTag("native_fake").assertIsDisplayed()
        onNodeWithTag("pane_chat").assertExists()

        // Flip back to Chat → chat is displayed again; the Native panel STAYS composed (kept alive,
        // laid out at 0×0) rather than being disposed — the SwingPanel keep-alive contract.
        runOnIdle { layout.setNativeView("s1", false) }
        onNodeWithTag("pane_chat").assertIsDisplayed()
        onNodeWithTag("native_fake").assertExists()
    }

    @Test
    fun onExitFlipsBackToChatAndClearsNativeView() = runComposeUiTest {
        val layout = WorkspaceLayout()
        layout.setNativeView("s1", true) // start in Native
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = session, agent = null,
                    layout = layout, draft = "", onDraftChange = {},
                    nativePanelContent = fakeNative)
            }
        }
        onNodeWithTag("native_fake").assertIsDisplayed()

        // Agent PTY exit → onExit clears the persisted preference and drops the panel back to Chat.
        runOnIdle { capturedOnExit?.invoke() }
        assertEquals(false, layout.nativeView("s1"))
        onNodeWithTag("pane_chat").assertIsDisplayed()
        // A dead PTY is fully disposed (not kept alive) so a later re-open builds a fresh client.
        onNodeWithTag("native_fake").assertDoesNotExist()
    }

    @Test
    fun clickingNativePillPersistsPreferenceViaLayout() = runComposeUiTest {
        val layout = WorkspaceLayout()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = session, agent = null,
                    layout = layout, draft = "", onDraftChange = {},
                    nativePanelContent = fakeNative)
            }
        }
        onNodeWithTag("agent_view_native").performClick()
        // The choice is persisted on the layout (which the workspace snapshot serializes).
        assertEquals(true, layout.nativeView("s1"))
        assertTrue(layout.snapshot().native["s1"] == true)

        onNodeWithTag("agent_view_chat").performClick()
        assertEquals(false, layout.nativeView("s1"))
    }

    @Test
    fun sessionSwitchDisposesOldNativePanelAndMountsFresh() = runComposeUiTest {
        // The hard constraint behind key(session.id): WorkspaceRoot renders ONE SessionDetail in
        // the same composition slot for the selection, so a session switch recomposes this test's
        // single SessionDetail with a new `session` — exactly the reuse that would bind the wrong
        // session's agent PTY without the key. The mount/dispose ledger proves DISTINCT panel
        // instances: a mere recomposition of a reused panel would re-run neither effect.
        val layout = WorkspaceLayout()
        layout.setNativeView("s1", true)
        layout.setNativeView("s2", true)
        val sessionB = SessionInfo(id = "s2", name = "demo2", workdir = "/w/s2", agent = "claude")
        val mounts = mutableListOf<String>()
        val disposals = mutableListOf<String>()
        var current by mutableStateOf(session) // s1
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = current, agent = null,
                    layout = layout, draft = "", onDraftChange = {},
                    nativePanelContent = { _, _ ->
                        val forSession = current.id
                        DisposableEffect(Unit) {
                            mounts.add(forSession)
                            onDispose { disposals.add(forSession) }
                        }
                        Box(Modifier.fillMaxSize().testTag("native_fake_$forSession"))
                    })
            }
        }
        onNodeWithTag("native_fake_s1").assertIsDisplayed()

        // Switch to session B (native pref on for both).
        runOnIdle { current = sessionB }
        onNodeWithTag("native_fake_s2").assertIsDisplayed()
        onNodeWithTag("native_fake_s1").assertDoesNotExist()
        // A's panel was DISPOSED and B's mounted FRESH — not a reused composition slot.
        assertEquals(listOf("s1", "s2"), mounts)
        assertEquals(listOf("s1"), disposals)
    }

    // ── Finish button gate + SM_FINISH_TEST force-open hook ─────────────────────────────────────────

    private val branchedSession =
        SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "claude", session_branch = "feat/x")

    @Test
    fun finishButtonShownOnlyForBranchedSession() = runComposeUiTest {
        // No session_branch → no Finish button in the header.
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = session, agent = null,
                    layout = WorkspaceLayout(), draft = "", onDraftChange = {},
                    editorPanelContent = fakeEditor)
            }
        }
        onNodeWithTag("finish_button").assertDoesNotExist()
    }

    @Test
    fun finishButtonShownForBranchedSession() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = branchedSession, agent = null,
                    layout = WorkspaceLayout(), draft = "", onDraftChange = {},
                    editorPanelContent = fakeEditor)
            }
        }
        onNodeWithTag("finish_button").assertIsDisplayed()
    }

    @Test
    fun forceFinishDialogConsumesTheOneShotFlag() = runComposeUiTest {
        // The SM_FINISH_TEST delivery path: flipping forceFinishDialog true drives the SAME open path
        // the button click uses, then consumes the one-shot source exactly once. Proves the hook is
        // wired into the session_branch block without needing to assert on the (windowed) Dialog.
        var consumed = 0
        var force by mutableStateOf(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = branchedSession, agent = null,
                    layout = WorkspaceLayout(), draft = "", onDraftChange = {},
                    editorPanelContent = fakeEditor,
                    forceFinishDialog = force,
                    onForceFinishConsumed = { consumed++ })
            }
        }
        // Not yet requested → not consumed.
        runOnIdle { assertEquals(0, consumed) }
        // Request the open → the hook fires and consumes exactly once.
        runOnIdle { force = true }
        runOnIdle { assertEquals(1, consumed) }
    }

    // ── SM_GIT_MENU / SM_LINKS_MENU / SM_OVERFLOW_MENU force-open hooks (M4c Task 3) ────────────

    private val gitSession = session.copy(
        git = GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 2, behind = 0, dirty = 1),
    )

    @Test
    fun forceGitMenuOpensTheBadgeDropdownAndConsumesTheOneShotFlag() = runComposeUiTest {
        var consumed = 0
        var force by mutableStateOf<GitMenuForceOp?>(null)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = gitSession, agent = null,
                    layout = WorkspaceLayout(), draft = "", onDraftChange = {},
                    editorPanelContent = fakeEditor,
                    forceGitMenu = force,
                    onForceGitMenuConsumed = { consumed++ })
            }
        }
        onNodeWithTag("git_fetch").assertDoesNotExist()
        runOnIdle { force = GitMenuForceOp.OPEN }
        waitForIdle()
        onNodeWithTag("git_fetch").assertIsDisplayed()
        runOnIdle { assertEquals(1, consumed) }
    }

    @Test
    fun forceLinksMenuOpensTheGlobeDropdownAndConsumesTheOneShotFlag() = runComposeUiTest {
        var consumed = 0
        var force by mutableStateOf(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = session, agent = null,
                    layout = WorkspaceLayout(), draft = "", onDraftChange = {},
                    editorPanelContent = fakeEditor,
                    loadProxies = { listOf(ProxyDto(domain = "d.example", sessionName = "demo", port = 3000)) },
                    forceLinksMenu = force,
                    onForceLinksMenuConsumed = { consumed++ })
            }
        }
        onNodeWithTag("session_links").assertIsDisplayed()
        runOnIdle { force = true }
        waitForIdle()
        onNodeWithText("d.example").assertIsDisplayed()
        runOnIdle { assertEquals(1, consumed) }
    }

    @Test
    fun forceOverflowMenuOpensTheDropdownAndConsumesTheOneShotFlag() = runComposeUiTest {
        var consumed = 0
        var force by mutableStateOf(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = session, agent = null,
                    layout = WorkspaceLayout(), draft = "", onDraftChange = {},
                    editorPanelContent = fakeEditor,
                    forceOverflowMenu = force,
                    onForceOverflowMenuConsumed = { consumed++ })
            }
        }
        onNodeWithTag("overflow_rename").assertDoesNotExist()
        runOnIdle { force = true }
        waitForIdle()
        onNodeWithTag("overflow_rename").assertIsDisplayed()
        runOnIdle { assertEquals(1, consumed) }
    }
}

// Captured onExit from the most recent [fakeNative] composition, so a test can drive the agent-exit
// fallback path without a live PTY.
private var capturedOnExit: (() -> Unit)? = null

/** Pure-Compose stand-in for DesktopTerminalPanel's SwingPanel — tags itself `native_fake` and
 *  records the onExit callback. Ignores the connect factory (never opens a socket under test). */
private val fakeNative: @Composable (() -> TerminalClient, () -> Unit) -> Unit = { _, onExit ->
    capturedOnExit = onExit
    Box(Modifier.fillMaxSize().testTag("native_fake"))
}
