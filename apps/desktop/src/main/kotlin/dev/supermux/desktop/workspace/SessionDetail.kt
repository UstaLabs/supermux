// Modeled on apps/android/src/main/kotlin/dev/supermux/android/workspace/SessionWorkspaceDetail.kt —
// the wide-screen detail for ONE session: a minimal identity header + the nested, drag-resizable
// split tree of live panes driven by [layout].panesFor([session].id).
//
// Live surfaces: Chat (M1), Terminal — scratch tabs + the Native agent PTY (M2), and Editor (M3, the
// KCEF-backed code editor). Display remains a ComingSoonPane placeholder (it arrives in M5).
// Deliberately SKIPPED for M1 (present in the Android original, all TODO(M4) here): git badge menus,
// Finish button, the session-links menu, and the overflow (⋮) management menu. The AgentViewToggle
// (Chat⇄Native) was pulled forward into M2 (terminal UX) — see the chatOrNative slot below.
//
// The split structure and the "chat stays in the same composition slot" discipline are copied
// exactly from Android so a pane toggle never remounts (and never blinks) the chat pane. The
// Chat⇄Native pair is the one keep-alive exception (mirrors Android's chatOrNative):
// ```
//   chat + work → [ Chat|Native | RightArea ]     (horizontal, chatFraction)
//   RightArea:  work + display → [ WorkColumn | Display ]   (horizontal, workDisplayFraction)
//   WorkColumn: editor + terminal → [ Editor / Terminal ]  (vertical, editorTermFraction)
// ```
package dev.supermux.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import dev.supermux.desktop.chat.ChatPanel
import dev.supermux.desktop.editor.EditorPanel
import dev.supermux.desktop.editor.EditorPrefsStore
import dev.supermux.desktop.editor.PendingEditorOpen
import dev.supermux.desktop.session.SessionAvatar
import dev.supermux.desktop.session.SessionStatusRail
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.terminal.DesktopTerminalPanel
import dev.supermux.desktop.terminal.TerminalTabs
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.ui.KeepAlivePanel
import dev.supermux.desktop.ui.keepAlivePanel
import dev.supermux.net.TerminalClient
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.SessionInfo
import dev.supermux.session.inferHomeDir
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.toWorkdirRelativePath

/**
 * Placeholder for a pane whose real surface lands in a later milestone: a centered title + an
 * "arrives in <milestone>" subline on a subtle surfaceVariant background. [testTagName] tags the
 * pane for UI tests (e.g. `pane_editor`).
 */
@Composable
fun ComingSoonPane(title: String, milestone: String, testTagName: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxSize()
            .background(cs.surfaceVariant.copy(alpha = 0.4f))
            .testTag(testTagName),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(Space.xs))
            Text(
                "arrives in $milestone",
                color = cs.onSurfaceVariant,
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * App-aware wrapper around the editor [EditorPanel]: binds the session's fs* broker calls to the
 * panel's path-only lambdas (exactly as Android's ChatScreen binds the AppViewModel wrappers), owns
 * the on-disk [EditorPrefsStore] (loaded once, font-zoom writes back), and forwards the app-wide
 * fs_changed stream + editor_open/close lifecycle. Kept OUT of the panel so [EditorPanel] itself
 * stays disk-free and runComposeUiTest-able; this wrapper is the seam SessionDetail defaults to.
 */
@Composable
fun DesktopEditorPanel(
    app: DesktopAppState,
    session: SessionInfo,
    // Chat-tap → editor-at-line handoff (M3-T5): SessionDetail owns the pendingOpen state (it also
    // decides the pane-flip), this wrapper just forwards it straight through to [EditorPanel].
    pendingOpen: PendingEditorOpen? = null,
    onPendingOpenConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val prefsStore = remember { EditorPrefsStore() }
    var prefs by remember { mutableStateOf(prefsStore.load()) }
    EditorPanel(
        sessionId = session.id,
        workdir = session.workdir,
        fsList = { path -> app.fsList(session, path) },
        fsRead = { path -> app.fsRead(session, path) },
        fsWrite = { path, content -> app.fsWrite(session, path, content) },
        fsSearch = { q -> app.fsSearch(session, q) },
        fsChanges = app.fsChanges,
        editorOpen = { app.editorOpen(session) },
        editorClose = { app.editorClose(session) },
        pendingOpen = pendingOpen,
        onPendingOpenConsumed = onPendingOpenConsumed,
        prefs = prefs,
        onFontSize = { px ->
            // The engine already applied the zoom live; persist it so it survives reopen/relaunch.
            // NOTE: this writeback does NOT touch `sessionId`/content — EditorSurface's engine is
            // keyed only on `kcefReady` (WebCodeEditor.kt), so a zoom change never re-keys or
            // reloads the browser; only cmSetFontSize is pushed (EditorPushPlanner parity).
            val next = prefs.copy(fontSize = px).clamped()
            prefs = next
            prefsStore.save(next)
        },
        modifier = modifier,
    )
}

@Composable
fun SessionDetail(
    app: DesktopAppState,
    session: SessionInfo,
    agent: AgentStatus?,
    layout: WorkspaceLayout,
    draft: String,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Injectable seam for the Native (agent-PTY) panel — defaults to the real [DesktopTerminalPanel].
    // Its SwingPanel cannot be hosted under `runComposeUiTest` (no real AWT window), so the UI tests
    // inject a lightweight pure-Compose fake to exercise the toggle + keep-alive + onExit wiring.
    nativePanelContent: @Composable (connect: () -> TerminalClient, onExit: () -> Unit) -> Unit = {
        connect, onExit ->
        DesktopTerminalPanel(connect = connect, modifier = Modifier.fillMaxSize(), onExit = onExit)
    },
    // Injectable seam for the Editor panel — defaults to the real KCEF-backed [DesktopEditorPanel].
    // Same reason as nativePanelContent: KCEF (embedded Chromium) can't boot under runComposeUiTest,
    // and even constructing the real panel touches the on-disk EditorPrefsStore — so SessionDetail's
    // tests inject a pure-Compose fake tagged `pane_editor`. Extended in M3-T5 to carry the
    // chat-tap pendingOpen handoff through to the real panel (see [pendingEditorOpen] below); a test's
    // fake can capture the args to assert delivery/consumption without touching KCEF.
    editorPanelContent: @Composable (pendingOpen: PendingEditorOpen?, onPendingOpenConsumed: () -> Unit) -> Unit = {
        pendingOpen, onConsumed ->
        DesktopEditorPanel(
            app = app,
            session = session,
            pendingOpen = pendingOpen,
            onPendingOpenConsumed = onConsumed,
            modifier = Modifier.fillMaxSize().testTag("pane_editor"),
        )
    },
) {
    val cs = MaterialTheme.colorScheme

    // Chat-tap → editor-at-line (Android ChatScreen:221 / SessionWorkspaceDetail:174 parity): a tap
    // on a file-path ref in the transcript converts to a workdir-relative [PendingEditorOpen], flips
    // the editor pane on, and hands the target to EditorPanel via the seam above. `remember(session.id)`
    // so a session switch starts with a clean slate (no stale reveal leaking into the new session).
    var pendingEditorOpen by remember(session.id) { mutableStateOf<PendingEditorOpen?>(null) }
    val onOpenFile: (FilePathRef) -> Unit = remember(session.id) {
        { ref ->
            val rel = toWorkdirRelativePath(ref.path, session.workdir, inferHomeDir(session.workdir))
            if (rel == null) {
                // No toast surface on desktop yet (TODO(M4): a snackbar host) — log-and-drop, same
                // intent as Android's Toast (a path outside the session's project is not openable).
                println("[SessionDetail] onOpenFile: '${ref.path}' is outside session workdir '${session.workdir}' — dropped")
            } else {
                pendingEditorOpen = PendingEditorOpen(rel, ref.line, ref.endLine)
                layout.setPanes(session.id, layout.panesFor(session.id).copy(editor = true))
            }
        }
    }

    // ── individual panes (each fills its split slot) ──
    // Chat/Native slot: defined ONCE and always rendered through the same split slot, so toggling a
    // work pane never remounts it (mirrors the Android fix for the whole-page-blink bug). The
    // Chat⇄Native flip is the keep-alive exception — mirrors Android's chatOrNative:
    //   • Chat is PURE Compose → the lightweight `Modifier.keepAlivePanel(visible)` variant hides it
    //     (stays composed under Native so its unsaved draft survives the flip; never remounts).
    //   • Native is a SwingPanel (HEAVYWEIGHT AWT child) → the `KeepAlivePanel` composable variant
    //     that lays it out at 0×0 when hidden (alpha/zIndex don't hide a heavyweight child).
    //   • Native is LAZY: not composed at all until the user first opens it (Android openedPanels
    //     parity), then kept alive across flips so its agent PTY / grid survive.
    //   • key(session.id) wraps the Native panel: [DesktopTerminalPanel]'s `remember { connect() }`
    //     is deliberately unkeyed, and WorkspaceRoot renders ONE SessionDetail in the same
    //     composition slot for ui.selectedId — so on a session switch this slot recomposes with a
    //     new `session`. Without the key, the reused `remember` would bind the WRONG session's
    //     agent PTY into the new session's chat slot.
    val chatOrNative: @Composable () -> Unit = {
        val native = layout.nativeView(session.id) && session.agent == "claude"
        // Once Native has been shown for this session, keep it composed (kept-alive) so a flip back
        // to Chat doesn't drop its PTY; reset per session so a switch starts closed. Latched in an
        // effect, not a bare state write during composition (which Compose may re-execute/discard).
        // session.id must be an effect key too: on a session switch with native on for BOTH
        // sessions, `native` doesn't change (true→true) while the remember resets — without the
        // key the effect wouldn't relaunch and the new session's Native would never mount.
        var nativeOpened by remember(session.id) { mutableStateOf(false) }
        LaunchedEffect(session.id, native) { if (native) nativeOpened = true }
        Box(Modifier.fillMaxSize()) {
            ChatPanel(
                app = app,
                session = session,
                draft = draft,
                onDraftChange = onDraftChange,
                modifier = Modifier.keepAlivePanel(visible = !native).testTag("pane_chat"),
                showHeader = false, // this SessionDetail owns the identity header
                onOpenFile = onOpenFile,
            )
            if (nativeOpened) {
                key(session.id) {
                    KeepAlivePanel(visible = native, modifier = Modifier.testTag("pane_native")) {
                        nativePanelContent(
                            { app.connectAgentTerminal(session.id) },
                            // Agent PTY exited (the broker's exit frame — see DesktopTerminalPanel's
                            // onExit KDoc) → clear the persisted preference (so a dead PTY never
                            // re-opens on restart) and drop the kept-alive panel so a later re-open
                            // builds a fresh client rather than showing the dead one. NB: Android
                            // still fires its onExit off the CONNECTED→DISCONNECTED status heuristic,
                            // which false-positives on reconnects/broker restarts — desktop diverges
                            // deliberately (web is the reference; consider backporting to Android).
                            {
                                layout.setNativeView(session.id, false)
                                nativeOpened = false
                            },
                        )
                    }
                }
            }
        }
    }
    val editorPane: @Composable () -> Unit = {
        editorPanelContent(pendingEditorOpen) { pendingEditorOpen = null }
    }
    // Real scratch terminal with web-parity tabs (list/add/close). One strip per session.
    val terminalPane: @Composable () -> Unit = {
        // Only ever composed when the terminal pane is on (the split slot is null otherwise), so
        // active=true here; the intra-strip active/inactive tab distinction is handled inside.
        TerminalTabs(
            app = app,
            sessionId = session.id,
            active = true,
            modifier = Modifier.fillMaxSize().testTag("pane_terminal"),
        )
    }
    val displayPane: @Composable () -> Unit = { ComingSoonPane("Display", "M5", "pane_display") }

    // Editor and/or Terminal stacked vertically (the "work" column).
    val workColumn: @Composable () -> Unit = {
        val p = layout.panesFor(session.id)
        when {
            // Editor stays in the same split slot so it doesn't remount when the terminal toggles.
            p.editor -> ResizableSplit(
                axis = SplitAxis.Vertical,
                fraction = layout.editorTermFraction,
                onFractionChange = layout::setEditorTermFraction,
                range = WorkspaceLayout.EDITORTERM_MIN..WorkspaceLayout.EDITORTERM_MAX,
                testTag = "divider_editor_terminal",
                first = editorPane,
                second = if (p.terminal) terminalPane else null,
            )
            p.terminal -> terminalPane()
        }
    }
    // The work column and/or the display, side by side.
    val rightArea: @Composable () -> Unit = {
        val p = layout.panesFor(session.id)
        when {
            // Work column stays in the same split slot so it doesn't remount when Display toggles.
            p.editor || p.terminal -> ResizableSplit(
                axis = SplitAxis.Horizontal,
                fraction = layout.workDisplayFraction,
                onFractionChange = layout::setWorkDisplayFraction,
                range = WorkspaceLayout.WORKDISP_MIN..WorkspaceLayout.WORKDISP_MAX,
                testTag = "divider_work_display",
                first = workColumn,
                second = if (p.display) displayPane else null,
            )
            p.display -> displayPane()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(cs.surfaceContainerLow),
    ) {
        // Header: identity + status + the pane toggles.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(cs.surfaceContainerLow)
                .padding(horizontal = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionAvatar(name = session.name, agent = session.agent, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(Space.sm))
            // git/sync status + working spinner (git comes off SessionInfo).
            SessionStatusRail(git = session.git, working = agent?.working == true)
            Spacer(Modifier.width(Space.xs))
            Text(
                text = session.name,
                style = MaterialTheme.typography.titleLarge,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.sm))
            // Chat ⇄ Native (raw agent PTY) toggle — claude only, and only while the Chat pane is
            // visible (it flips ChatPanel ⇄ agent-PTY inside that pane; see chatOrNative above).
            // Pulled forward from M4 because it is terminal UX.
            if (session.agent == "claude" && layout.panesFor(session.id).chat) {
                AgentViewToggle(
                    nativeView = layout.nativeView(session.id),
                    onSetNative = { layout.setNativeView(session.id, it) },
                    modifier = Modifier.testTag("toggle_native"),
                )
                Spacer(Modifier.width(Space.xs))
            }
            // TODO(M4): git badge menu, Finish button, session-links menu, and the overflow (⋮)
            // management menu — all present in the Android original.
            PaneToggleCluster(layout = layout, sessionId = session.id)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(cs.outlineVariant),
        )

        // Content: the nested split tree, driven by layout.panesFor(session.id).
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val p = layout.panesFor(session.id)
            when {
                // Chat always renders through the SAME split, so it never remounts (and the whole
                // page never blinks) when a work pane toggles — the work area is just the split's
                // second slot, present only when there's work to show.
                p.chat -> ResizableSplit(
                    axis = SplitAxis.Horizontal,
                    fraction = layout.chatFraction,
                    onFractionChange = layout::setChatFraction,
                    range = WorkspaceLayout.CHAT_MIN..WorkspaceLayout.CHAT_MAX,
                    testTag = "divider_chat_work",
                    first = chatOrNative,
                    second = if (p.hasWork) rightArea else null,
                )
                else -> rightArea() // invariant guarantees a non-empty pane set
            }
        }
    }
}
