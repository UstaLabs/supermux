// Ported from apps/android/src/main/kotlin/dev/supermux/android/workspace/SessionsRail.kt —
// keep in sync until a shared UI module exists.
//
// Desktop adaptations vs. the Android source:
//   - `statusBarsPadding()` dropped (no system status bar on desktop).
//   - Bundled drawables → compose.materialIconsExtended: ic_chevron_right → Icons.Filled.ChevronRight,
//     ic_plus → Icons.Filled.Add.
//   - Desktop `SessionAvatar(name, agent, modifier)` has no `sessionId` param (see SessionListPanel).
//   - `pointerHoverIcon(PointerIcon.Hand)` on the tappable avatars (mouse affordance).
package dev.supermux.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.session.SessionAvatar
import dev.supermux.desktop.session.SessionStatusRail
import dev.supermux.desktop.theme.Space
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.SessionInfo

/**
 * Slim (~64dp) collapsed sidebar shown in place of the session list when
 * [WorkspaceLayout.sidebarCollapsed] is true. Top: an expand chevron ([onExpand]) and a "+"
 * new-session button ([onNewSession]); below, a vertical scrollable column of session
 * [SessionAvatar]s. Tapping one calls [onSelect]; the active session is ringed. Each avatar carries
 * its [SessionStatusRail] status dot at the bottom-end corner (working spinner / git status).
 */
@Composable
fun SessionsRail(
    sessions: List<SessionInfo>,
    selectedId: String?,
    agentState: Map<String, AgentStatus>,
    onSelect: (String) -> Unit,
    onExpand: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxHeight()
            .width(64.dp)
            .background(cs.surfaceContainerHigh)
            .padding(vertical = Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        IconButton(onClick = onExpand, modifier = Modifier.testTag("rail_expand")) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Expand sidebar",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onNewSession, modifier = Modifier.testTag("rail_new")) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "New session",
                tint = cs.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Box(Modifier.width(28.dp).height(1.dp).background(cs.outlineVariant))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Spacer(Modifier.height(Space.xs))
            sessions.forEach { s ->
                RailSessionItem(
                    session = s,
                    selected = s.id == selectedId,
                    working = agentState[s.id]?.working == true,
                    onClick = { onSelect(s.id) },
                )
            }
            Spacer(Modifier.height(Space.sm))
        }
    }
}

/** A single tappable avatar in the rail, ringed when [selected], with a corner status dot. */
@Composable
private fun RailSessionItem(
    session: SessionInfo,
    selected: Boolean,
    working: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(48.dp)
            .clip(shape)
            .background(if (selected) cs.surfaceContainerHighest else Color.Transparent)
            .then(if (selected) Modifier.border(2.dp, cs.primary, shape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .testTag("rail_session_${session.id}"),
        contentAlignment = Alignment.Center,
    ) {
        SessionAvatar(
            name = session.name,
            agent = session.agent,
            modifier = Modifier.size(36.dp),
        )
        // Status dot (working spinner / git status), badged over the avatar's bottom-end corner.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .clip(CircleShape)
                .background(cs.surfaceContainerHigh)
                .padding(1.dp),
        ) {
            SessionStatusRail(git = session.git, working = working)
        }
    }
}
