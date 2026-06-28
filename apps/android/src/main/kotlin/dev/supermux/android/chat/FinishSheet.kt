package dev.supermux.android.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.net.FinishReadiness
import dev.supermux.net.FinishResult
import dev.supermux.net.VerifySaveResult
import dev.supermux.net.VerifySuggestResult
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.SessionInfo
import kotlinx.coroutines.launch

/** Header Finish button — a compact M3 [TextButton] with an unacked-result dot overlay.
 *  Red ([colorScheme.error]) when the background job ended `failed`, teal ([colorScheme.primary])
 *  when it ended `done`. Hidden while `running` and once acked (the dot is for a result that
 *  arrived in the background, not an in-flight job — iOS `finishBadge`). */
@Composable
fun FinishButton(
    finishJob: FinishJobDto?,
    isUnacked: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box {
        TextButton(
            onClick = onClick,
            modifier = Modifier.testTag("finish_button"),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_git_merge),
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Finish",
                color = cs.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (isUnacked) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (finishJob?.status == "failed") cs.error else cs.primary)
                    .testTag("finish_unacked_dot"),
            )
        }
    }
}

/** Local draft of an edited verify script (the no_verify recovery path). */
data class VerifyDraft(val content: String, val source: String)

/** The three render states of the sheet, derived from the live [FinishJobDto]. */
private enum class FinishView { Menu, Running, Outcome }

/**
 * The chat **Finish** bottom sheet — native-M3 parity with iOS `FinishSheet.swift`.
 *
 * A three-state machine driven entirely by [finishJob] (kept fresh by the WS `finish_job`
 * frame via the VM `finishJobs` StateFlow): **Menu** (readiness preflight → Merge / Open PR /
 * Keep / Discard) → **Running** (live `stage`) → **Outcome** (per-status recovery). Because
 * `finishJob` is a parameter, a WS flip `running → done|failed` recomposes the body with no
 * extra code. Local input drafts (readiness, runError, confirmingDiscard, commitMessage,
 * verifyDraft) live here as Compose state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinishSheet(
    session: SessionInfo,
    finishJob: FinishJobDto?,
    onReadiness: suspend () -> FinishReadiness?,
    onFinish: (action: String, skipVerify: Boolean?, commitFirst: Boolean?, commitMessage: String?, onKickoff: (Boolean) -> Unit) -> Unit,
    onClearJob: () -> Unit,
    onVerifySuggest: suspend () -> VerifySuggestResult?,
    onVerifySave: suspend (String) -> VerifySaveResult?,
    onSendToAgent: (String) -> Unit,
    onAck: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var readiness by remember { mutableStateOf<FinishReadiness?>(null) }
    var loadingReadiness by remember { mutableStateOf(false) }
    var runError by remember { mutableStateOf<String?>(null) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    var commitMessage by remember { mutableStateOf("Session changes") }
    var verifyDraft by remember { mutableStateOf<VerifyDraft?>(null) }
    var verifySaving by remember { mutableStateOf(false) }

    val view = when {
        finishJob == null -> FinishView.Menu
        finishJob.status == "running" -> FinishView.Running
        else -> FinishView.Outcome
    }

    // On open: clear any stale kickoff error, ack the badge, and (re)load readiness when there's
    // no in-flight job (no job at all, or one that already finished). Mirrors iOS `.task`.
    LaunchedEffect(Unit) {
        runError = null
        onAck()
        if (finishJob == null || finishJob.status == "done") {
            loadingReadiness = true
            readiness = onReadiness()
            loadingReadiness = false
        }
    }

    // Kickoff callback shared by every onFinish(...) — surfaces a rejected kick-off in the menu.
    val kickoff: (Boolean) -> Unit = { ok ->
        if (!ok) runError = "Couldn't start finish — check your connection and try again."
    }

    ModalBottomSheet(
        // Block swipe-dismiss mid-job (iOS hides Done while running); a brief, self-resolving state.
        onDismissRequest = { if (view != FinishView.Running) onDismiss() },
        sheetState = sheetState,
        containerColor = cs.surfaceContainerLow,
        contentColor = cs.onSurface,
        modifier = Modifier.testTag("finish_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            // ── Title ──────────────────────────────────────────────────────────
            val title = when (view) {
                FinishView.Menu -> "Finish · ${readiness?.branch ?: session.session_branch ?: ""}"
                FinishView.Running -> "Finishing"
                FinishView.Outcome -> "Finish"
            }
            Text(
                text = title,
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            when (view) {
                FinishView.Menu -> MenuBody(
                    readiness = readiness,
                    loadingReadiness = loadingReadiness,
                    runError = runError,
                    confirmingDiscard = confirmingDiscard,
                    onConfirmDiscardChange = { confirmingDiscard = it },
                    onFinish = onFinish,
                    kickoff = kickoff,
                    onDismiss = onDismiss,
                )
                FinishView.Running -> RunningBody(stage = finishJob?.stage)
                FinishView.Outcome -> OutcomeBody(
                    finishJob = finishJob,
                    commitMessage = commitMessage,
                    onCommitMessageChange = { commitMessage = it },
                    verifyDraft = verifyDraft,
                    onVerifyDraftChange = { verifyDraft = it },
                    verifySaving = verifySaving,
                    onVerifySavingChange = { verifySaving = it },
                    onFinish = onFinish,
                    kickoff = kickoff,
                    onVerifySuggest = onVerifySuggest,
                    onVerifySave = onVerifySave,
                    onSendToAgent = onSendToAgent,
                    onClearJob = onClearJob,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

// ── Menu ─────────────────────────────────────────────────────────────────────

@Composable
private fun MenuBody(
    readiness: FinishReadiness?,
    loadingReadiness: Boolean,
    runError: String?,
    confirmingDiscard: Boolean,
    onConfirmDiscardChange: (Boolean) -> Unit,
    onFinish: (String, Boolean?, Boolean?, String?, (Boolean) -> Unit) -> Unit,
    kickoff: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var pendingVerify by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        // 1) Kickoff error
        if (runError != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_alert_triangle),
                    contentDescription = null,
                    tint = cs.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(runError, color = cs.error, fontSize = 13.sp)
            }
        }

        // 2) Readiness card
        if (readiness == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loadingReadiness) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Checking branch…", color = cs.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        } else {
            ReadinessCard(readiness)
        }

        Spacer(Modifier.size(4.dp))

        // 3) Action rows
        if (readiness?.nothingToLand == true) {
            Text(
                "No new commits to land",
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            ActionRow("Keep", R.drawable.ic_archive) {
                onFinish("keep", null, null, null, kickoff); onDismiss()
            }
            DiscardRows(confirmingDiscard, onConfirmDiscardChange, onFinish, kickoff)
        } else {
            ActionRow(
                "Merge locally",
                R.drawable.ic_git_merge,
                color = if (readiness?.recommended == "merge") cs.primary else cs.onSurface,
            ) { onConfirmDiscardChange(false); pendingVerify = if (pendingVerify == "merge") null else "merge" }
            if (pendingVerify == "merge") {
                VerifyChoiceRows(
                    prompt = "Run tests before merging?",
                    showSkip = canSkipTests("merge", readiness?.prRequiresGreen ?: false),
                    onRun = { pendingVerify = null; onFinish("merge", false, null, null, kickoff) },
                    onSkip = { pendingVerify = null; onFinish("merge", true, null, null, kickoff) },
                )
            }
            PrRow(readiness) { onConfirmDiscardChange(false); pendingVerify = if (pendingVerify == "pr") null else "pr" }
            if (pendingVerify == "pr") {
                VerifyChoiceRows(
                    prompt = "Run tests before opening the PR?",
                    showSkip = canSkipTests("pr", readiness?.prRequiresGreen ?: false),
                    onRun = { pendingVerify = null; onFinish("pr", false, null, null, kickoff) },
                    onSkip = { pendingVerify = null; onFinish("pr", true, null, null, kickoff) },
                )
            }
            ActionRow("Keep", R.drawable.ic_archive) {
                pendingVerify = null; onFinish("keep", null, null, null, kickoff); onDismiss()
            }
            DiscardRows(
                confirmingDiscard = confirmingDiscard,
                onConfirmDiscardChange = { v -> if (v) pendingVerify = null; onConfirmDiscardChange(v) },
                onFinish = onFinish,
                kickoff = kickoff,
            )
        }
    }
}

@Composable
private fun ReadinessCard(r: FinishReadiness) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            "${r.branch} → ${r.base}",
            color = cs.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (r.behind > 0) "↑${r.ahead} · ↓${r.behind}" else "↑${r.ahead}",
                color = cs.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${r.filesChanged} files · +${r.insertions}/−${r.deletions}",
                color = cs.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        // Conditional chips: conflict preflight + uncommitted
        val showConflictChip = r.conflictPreflight == "will_conflict" || r.conflictPreflight == "clean"
        if (showConflictChip || r.dirtyFiles.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (r.conflictPreflight) {
                    "will_conflict" -> Chip(R.drawable.ic_alert_triangle, "may conflict", cs.tertiary)
                    "clean" -> Chip(R.drawable.ic_check, "no conflict", cs.primary)
                }
                if (r.dirtyFiles.isNotEmpty()) {
                    Chip(R.drawable.ic_alert_triangle, "${r.dirtyFiles.size} uncommitted", cs.tertiary)
                }
            }
        }
    }
}

@Composable
private fun Chip(iconRes: Int, label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(13.dp),
        )
        Text(label, color = color, fontSize = 12.sp)
    }
}

/** Open PR / "Push & open PR" — disabled without a remote (matches iOS `prRow`). */
@Composable
private fun PrRow(
    readiness: FinishReadiness?,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val label = if (readiness?.hasRemote == true && !readiness.ghAvailable) "Push & open PR" else "Open PR"
    val noRemote = readiness != null && !readiness.hasRemote
    ActionRow(
        label = label,
        iconRes = R.drawable.ic_git_pull_request,
        color = if (readiness?.recommended == "pr") cs.primary else cs.onSurface,
        enabled = !noRemote,
        trailing = if (noRemote) {
            { Text("no remote", color = cs.onSurfaceVariant, fontSize = 12.sp) }
        } else null,
    ) { onClick() }
}

@Composable
private fun DiscardRows(
    confirmingDiscard: Boolean,
    onConfirmDiscardChange: (Boolean) -> Unit,
    onFinish: (String, Boolean?, Boolean?, String?, (Boolean) -> Unit) -> Unit,
    kickoff: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ActionRow("Discard", R.drawable.ic_trash, color = cs.error) { onConfirmDiscardChange(true) }
    if (confirmingDiscard) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Text("Discard all work on this branch?", color = cs.onSurface, fontSize = 13.sp)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onConfirmDiscardChange(false); onFinish("discard", null, null, null, kickoff) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.error,
                        contentColor = cs.onError,
                    ),
                ) { Text("Discard") }
                OutlinedButton(onClick = { onConfirmDiscardChange(false) }) { Text("Cancel") }
            }
        }
    }
}

/** Inline Run/Skip choice shown under Merge/Open PR (mirrors the Discard confirm). */
@Composable
private fun VerifyChoiceRows(
    prompt: String,
    showSkip: Boolean,
    onRun: () -> Unit,
    onSkip: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        // Lighter weight (onSurfaceVariant, 12.sp) is intentional: this is a neutral
        // choice prompt, not the destructive Discard confirm (which uses onSurface, 13.sp).
        Text(prompt, color = cs.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRun) { Text("Run tests") }
            if (showSkip) {
                OutlinedButton(
                    onClick = onSkip,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.tertiary),
                ) { Text("Skip tests") }
            }
        }
    }
}

// ── Running ────────────────────────────────────────────────────────────────────

@Composable
private fun RunningBody(stage: String?) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator()
        Text(
            stage ?: "Finishing…",
            color = cs.onSurface,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "You can close this — I'll notify you when it's done.",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Outcome ──────────────────────────────────────────────────────────────────

@Composable
private fun OutcomeBody(
    finishJob: FinishJobDto?,
    commitMessage: String,
    onCommitMessageChange: (String) -> Unit,
    verifyDraft: VerifyDraft?,
    onVerifyDraftChange: (VerifyDraft?) -> Unit,
    verifySaving: Boolean,
    onVerifySavingChange: (Boolean) -> Unit,
    onFinish: (String, Boolean?, Boolean?, String?, (Boolean) -> Unit) -> Unit,
    kickoff: (Boolean) -> Unit,
    onVerifySuggest: suspend () -> VerifySuggestResult?,
    onVerifySave: suspend (String) -> VerifySaveResult?,
    onSendToAgent: (String) -> Unit,
    onClearJob: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val o = finishJob?.outcome
    val oStatus = o?.status ?: ""

    // Done/Dismiss both clear the (terminal) job so reopening returns to the readiness menu.
    val done: () -> Unit = { onClearJob(); onDismiss() }
    val openUrl: (String) -> Unit = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    val letAgentFix: () -> Unit = {
        if (o != null) { onSendToAgent(issueMessage(o)); onClearJob(); onDismiss() }
    }

    Column(Modifier.fillMaxWidth()) {
        when (oStatus) {
            "integrated" -> {
                OutcomeHeader("Merged into ${o?.base ?: "base"}", R.drawable.ic_check, cs.primary)
                DoneRow(done)
            }

            "pr_opened" -> {
                OutcomeHeader("Pull request opened", R.drawable.ic_git_pull_request, cs.onSurface)
                o?.prUrl?.let { url ->
                    ActionRow("View PR", R.drawable.ic_external_link) { openUrl(url) }
                }
                DismissRow(done)
                DoneRow(done)
            }

            "branch_published" -> {
                OutcomeHeader("Branch pushed", R.drawable.ic_git_pull_request, cs.onSurface)
                o?.prError?.let { Caption(it) }
                o?.compareUrl?.let { url ->
                    ActionRow("Open a PR", R.drawable.ic_external_link) { openUrl(url) }
                }
                DismissRow(done)
                DoneRow(done)
            }

            "tests_failed" -> {
                OutcomeHeader("Tests failed", R.drawable.ic_x_circle, cs.error)
                OutputBlock(o?.output)
                DismissRow(done)
                ActionRow("Merge anyway", R.drawable.ic_alert_triangle, color = cs.tertiary) {
                    onFinish("merge", true, null, null, kickoff)
                }
                ActionRow("Let the agent fix it", R.drawable.ic_send, onClick = letAgentFix)
            }

            "sync_conflict", "dirty_overlap" -> {
                OutcomeHeader(
                    if (oStatus == "sync_conflict") "Merge conflicts" else "Base has unsaved changes",
                    R.drawable.ic_alert_triangle,
                    cs.tertiary,
                )
                FileList(o?.files ?: emptyList())
                DismissRow(done)
                ActionRow("Let the agent fix it", R.drawable.ic_send, onClick = letAgentFix)
            }

            "uncommitted" -> {
                OutcomeHeader("These changes aren't committed yet", null, cs.onSurface)
                FileList(o?.files ?: emptyList())
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = onCommitMessageChange,
                    label = { Text("Commit message") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                )
                DismissRow(done)
                val isPr = finishJob?.action == "pr"
                ActionRow(
                    if (isPr) "Commit & open PR" else "Commit & merge",
                    R.drawable.ic_git_merge,
                ) {
                    onFinish(if (isPr) "pr" else "merge", null, true, commitMessage, kickoff)
                }
            }

            "no_verify" -> {
                if (verifyDraft == null) {
                    OutcomeHeader("No .mux/verify.sh configured", null, cs.onSurface)
                    DismissRow(done)
                    ActionRow("Merge without verifying", R.drawable.ic_alert_triangle, color = cs.tertiary) {
                        onFinish("merge", true, null, null, kickoff)
                    }
                    LoadingActionRow("Generate verify", R.drawable.ic_sparkle) {
                        onVerifySuggest()?.let { onVerifyDraftChange(VerifyDraft(it.content, it.source)) }
                    }
                } else {
                    Caption("Draft · ${verifyDraft.source.uppercase()}")
                    OutlinedTextField(
                        value = verifyDraft.content,
                        onValueChange = { onVerifyDraftChange(verifyDraft.copy(content = it)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp)
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                    DismissRow(done)
                    LoadingActionRow("Save", R.drawable.ic_check, enabled = !verifySaving) {
                        onVerifySavingChange(true)
                        try {
                            val r = onVerifySave(verifyDraft.content)
                            if (r?.ok == true) {
                                onVerifyDraftChange(null)
                                onFinish("merge", null, null, null, kickoff)
                            }
                        } finally {
                            onVerifySavingChange(false)
                        }
                    }
                }
            }

            "push_auth_failed", "push_rejected" -> {
                OutcomeHeader("Push failed", R.drawable.ic_x_circle, cs.error)
                o?.message?.let { Caption(it) }
                DismissRow(done)
                ActionRow("Retry", R.drawable.ic_git_pull_request) {
                    onFinish("pr", null, null, null, kickoff)
                }
            }

            "nothing_to_do" -> {
                OutcomeHeader("Nothing to land — no new commits.", null, cs.onSurface)
                DismissRow(done)
            }

            "kept", "discarded" -> {
                OutcomeHeader(
                    if (oStatus == "kept") "Branch kept" else "Work discarded",
                    R.drawable.ic_check,
                    cs.primary,
                )
                DoneRow(done)
            }

            "non_ff" -> {
                OutcomeHeader("Base branch moved", R.drawable.ic_alert_triangle, cs.tertiary)
                Caption("The base branch moved while finishing. Re-sync and merge again.")
                DismissRow(done)
                ActionRow("Merge again", R.drawable.ic_git_merge) {
                    onFinish("merge", null, null, null, kickoff)
                }
            }

            else -> {
                OutcomeHeader("Finish failed", R.drawable.ic_x_circle, cs.error)
                Caption(o?.message ?: oStatus)
                DismissRow(done)
            }
        }
    }
}

// ── Reusable building blocks ───────────────────────────────────────────────────

/** A full-width menu/outcome row: leading icon + label, optional trailing, ≥48dp, Tick haptic. */
@Composable
private fun ActionRow(
    label: String,
    iconRes: Int?,
    color: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val tint = if (enabled) color else cs.onSurfaceVariant.copy(alpha = 0.5f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.clickable { haptic(HapticKind.Tick); onClick() }
                else Modifier,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            label,
            color = tint,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** An [ActionRow] whose onClick runs a suspend body, showing a spinner while in flight. */
@Composable
private fun LoadingActionRow(
    label: String,
    iconRes: Int,
    enabled: Boolean = true,
    onClick: suspend () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val active = enabled && !busy
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (active) Modifier.clickable {
                    haptic(HapticKind.Tick)
                    busy = true
                    scope.launch { try { onClick() } finally { busy = false } }
                } else Modifier,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (active) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            label,
            color = if (active) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.5f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The status header line of an outcome: optional leading icon + colored label. */
@Composable
private fun OutcomeHeader(label: String, iconRes: Int?, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/** Mono file list, one line each, middle-truncated (iOS `fileList`). */
@Composable
private fun FileList(files: List<String>) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) {
        files.forEach { f ->
            Text(
                f,
                color = cs.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

/** Scrollable mono output block (iOS `outputBlock`), selectable, capped at ~220dp. */
@Composable
private fun OutputBlock(text: String?) {
    if (text.isNullOrEmpty()) return
    val cs = MaterialTheme.colorScheme
    SelectionContainer {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(cs.surfaceContainer)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(text, color = cs.onSurface, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DoneRow(onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        TextButton(onClick = onClick) { Text("Done", color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun DismissRow(onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        TextButton(onClick = onClick) {
            Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Build the agent message for a failed finish outcome — pure port of iOS
 * `SessionChrome.issueMessage` (web `FinishSheet.vue` `issueMessage`). Pure + testable.
 */
fun issueMessage(o: FinishResult): String = when (o.status) {
    "sync_conflict" -> "The Finish step merged the base branch in and hit conflicts in:\n" +
        o.files.joinToString("\n") { "- $it" } +
        "\n\nThe worktree is in a conflicted merge state — please resolve the conflicts and commit, then I'll run Finish again."
    "tests_failed" -> "The Finish step ran the tests (`${o.command ?: ""}`) and they failed:\n\n```\n${o.output ?: ""}\n```\n\nPlease fix them so the branch is green, then I'll run Finish again."
    "dirty_overlap" -> "The base checkout has unsaved changes in: ${o.files.joinToString(", ")} — the same files my work touches. Please commit or stash them so Finish can fast-forward."
    "push_rejected" -> "Pushing the branch for a PR was rejected because the remote has diverged: ${o.message ?: ""}. Please reconcile (pull/rebase) and I'll run Finish again."
    else -> "Finish reported: ${o.message ?: o.status}"
}
