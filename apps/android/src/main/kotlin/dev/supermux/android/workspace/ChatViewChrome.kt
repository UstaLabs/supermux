package dev.supermux.android.workspace

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermux.android.R
import dev.supermux.android.chat.ChatDetailPrefs
import dev.supermux.android.chat.FinishButton
import dev.supermux.android.chat.FinishSheet
import dev.supermux.android.session.SessionStatusRail
import dev.supermux.net.FinishReadiness
import dev.supermux.net.GitOpResult
import dev.supermux.net.ProxyDto
import dev.supermux.net.VerifySaveResult
import dev.supermux.net.VerifySuggestResult
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.gitBadge
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.util.proxyDisplayUrl
import dev.supermux.util.proxyUrl

fun gitOpResultText(r: GitOpResult?): String = when (r?.status) {
    null -> "Failed"
    "pushed" -> "Pushed"
    "up_to_date" -> "Up to date"
    "clean" -> "Pulled"
    "rejected_non_ff" -> "Push rejected — pull first"
    "conflict" -> "Conflict in ${r.files.size} file(s)"
    "dirty" -> "Uncommitted changes block the pull"
    "auth_failed" -> "Auth failed"
    "error" -> r.message ?: "Error"
    else -> r.status
}

/**
 * Chat-pane header on the tablet workspace: git rail, links, Chat⇄Native, Finish, overflow
 * (Fetch/Pull/Push/Publish). Reuses [SessionStatusRail], [FinishButton], [AgentViewToggle].
 * Settings/Usage/Devices stay on the session-list overflow (they have no chat home).
 */
@Composable
fun ChatViewHeader(
    session: SessionInfo,
    working: Boolean,
    nativeView: Boolean,
    onSetNative: (Boolean) -> Unit,
    sessionLinks: List<ProxyDto>,
    finishJob: FinishJobDto?,
    onFinishReadiness: suspend () -> FinishReadiness?,
    onFinish: (String, Boolean?, Boolean?, String?, (Boolean) -> Unit) -> Unit,
    onClearFinishJob: () -> Unit,
    onVerifySuggest: suspend () -> VerifySuggestResult?,
    onVerifySave: suspend (String) -> VerifySaveResult?,
    onSendToAgent: (String) -> Unit,
    onGitOp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionStatusRail(git = session.git, working = working)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.name,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            gitBadge(session.git)?.let { badge ->
                val label = if (badge.kind == GitBadgeKind.BASE && badge.compareRef.isNotEmpty())
                    "${badge.compareRef} ${badge.text}" else badge.text
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (sessionLinks.isNotEmpty()) {
            SessionLinksMenu(sessionLinks)
        }
        if (session.agent == "claude") {
            AgentViewToggle(
                nativeView = nativeView,
                onSetNative = onSetNative,
                modifier = Modifier.testTag("toggle_native"),
            )
        }
        if (session.session_branch != null) {
            var showFinishSheet by remember(session.id) { mutableStateOf(false) }
            var ackedStartedAt by rememberSaveable(session.id) { mutableStateOf(0.0) }
            val isUnacked = finishJob != null &&
                finishJob.status != "running" &&
                finishJob.startedAt != ackedStartedAt
            FinishButton(
                finishJob = finishJob,
                isUnacked = isUnacked,
                onClick = {
                    ackedStartedAt = finishJob?.startedAt ?: ackedStartedAt
                    showFinishSheet = true
                },
            )
            if (showFinishSheet) {
                FinishSheet(
                    session = session,
                    finishJob = finishJob,
                    onReadiness = onFinishReadiness,
                    onFinish = onFinish,
                    onClearJob = onClearFinishJob,
                    onVerifySuggest = onVerifySuggest,
                    onVerifySave = onVerifySave,
                    onSendToAgent = onSendToAgent,
                    onAck = { ackedStartedAt = finishJob?.startedAt ?: ackedStartedAt },
                    onDismiss = { showFinishSheet = false },
                )
            }
        }
        ChatOverflowMenu(
            session = session,
            onGitOp = onGitOp,
        )
    }
}

@Composable
fun SessionLinksMenu(sessionLinks: List<ProxyDto>) {
    var showLinks by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    Box {
        IconButton(onClick = { showLinks = true }, modifier = Modifier.testTag("session_links")) {
            Icon(
                painter = painterResource(R.drawable.ic_globe),
                contentDescription = "Links",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = showLinks, onDismissRequest = { showLinks = false }) {
            sessionLinks.forEach { p ->
                DropdownMenuItem(
                    text = { Text(proxyDisplayUrl(p)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_external_link),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = { showLinks = false; uriHandler.openUri(proxyUrl(p)) },
                )
            }
        }
    }
}

@Composable
private fun ChatOverflowMenu(
    session: SessionInfo,
    onGitOp: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val overflowContext = LocalContext.current
    ChatDetailPrefs.ensureLoaded(overflowContext)
    val chatDetailLevel by ChatDetailPrefs.level.collectAsState()
    var showOverflow by remember { mutableStateOf(false) }
    var detailSubmenu by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { showOverflow = true },
            modifier = Modifier.testTag("workspace_overflow"),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = "More",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = showOverflow,
            onDismissRequest = { showOverflow = false; detailSubmenu = false },
        ) {
            DropdownMenuItem(
                text = {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Detail")
                        Text(
                            chatDetailLevel.label,
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                modifier = Modifier.testTag("workspace_overflow_detail"),
                onClick = { detailSubmenu = true },
            )
            if (session.git != null) {
                DropdownMenuItem(
                    text = { Text("Fetch") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                    },
                    onClick = { showOverflow = false; onGitOp("fetch") },
                )
                DropdownMenuItem(
                    text = { Text("Pull") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_git_pull_request), contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                    },
                    onClick = { showOverflow = false; onGitOp("pull") },
                )
                if (session.git?.unpublished == true) {
                    DropdownMenuItem(
                        text = { Text("Publish") },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_cloud_off), contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                        },
                        onClick = { showOverflow = false; onGitOp("publish") },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Push") },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_git_merge), contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                        },
                        onClick = { showOverflow = false; onGitOp("push") },
                    )
                }
            }
        }
        DropdownMenu(expanded = detailSubmenu, onDismissRequest = { detailSubmenu = false }) {
            listOf(
                ChatDetailLevel.LOW to "Messages only · tools on status line",
                ChatDetailLevel.MEDIUM to "Quiet tool lines between messages",
                ChatDetailLevel.HIGH to "Terminal windows & file diffs",
            ).forEach { (level, desc) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(level.label)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        ChatDetailPrefs.set(overflowContext, level)
                        detailSubmenu = false
                        showOverflow = false
                    },
                )
            }
        }
    }
}

fun toastGitOp(context: android.content.Context, result: GitOpResult?) {
    Toast.makeText(context, gitOpResultText(result), Toast.LENGTH_SHORT).show()
}
