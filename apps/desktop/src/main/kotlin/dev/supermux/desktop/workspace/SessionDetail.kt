// Modeled on apps/android/src/main/kotlin/dev/supermux/android/workspace/SessionWorkspaceDetail.kt —
// the wide-screen detail for ONE session: a minimal identity header + the nested, drag-resizable
// split tree of live panes driven by [layout].panesFor([session].id).
//
// M1 scope: only the Chat pane is a real surface (the desktop ChatPanel). Editor / Terminal /
// Display are ComingSoonPane placeholders (they arrive in M3 / M2 / M5). Deliberately SKIPPED for
// M1 (present in the Android original, all TODO(M4) here): git badge menus, Finish button,
// AgentViewToggle (Chat⇄Native), the session-links menu, and the overflow (⋮) management menu.
//
// The split structure and the "chat stays in the same composition slot" discipline are copied
// exactly from Android so a pane toggle never remounts (and never blinks) the chat pane:
// ```
//   chat + work → [ Chat | RightArea ]            (horizontal, chatFraction)
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import dev.supermux.desktop.chat.ChatPanel
import dev.supermux.desktop.session.SessionAvatar
import dev.supermux.desktop.session.SessionStatusRail
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.SessionInfo

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

@Composable
fun SessionDetail(
    app: DesktopAppState,
    session: SessionInfo,
    agent: AgentStatus?,
    layout: WorkspaceLayout,
    draft: String,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme

    // ── individual panes (each fills its split slot) ──
    // Chat is defined ONCE and always rendered through the same split slot, so toggling a work pane
    // never remounts it (mirrors the Android fix for the whole-page-blink bug).
    val chatPane: @Composable () -> Unit = {
        ChatPanel(
            app = app,
            session = session,
            draft = draft,
            onDraftChange = onDraftChange,
            modifier = Modifier.fillMaxSize().testTag("pane_chat"),
            showHeader = false, // this SessionDetail owns the identity header
        )
    }
    val editorPane: @Composable () -> Unit = { ComingSoonPane("Editor", "M3", "pane_editor") }
    val terminalPane: @Composable () -> Unit = { ComingSoonPane("Terminal", "M2", "pane_terminal") }
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
            // TODO(M4): git badge menu, Finish button, AgentViewToggle (Chat⇄Native), session-links
            // menu, and the overflow (⋮) management menu — all present in the Android original.
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
                    first = chatPane,
                    second = if (p.hasWork) rightArea else null,
                )
                else -> rightArea() // invariant guarantees a non-empty pane set
            }
        }
    }
}
