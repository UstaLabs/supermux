// The chat **Finish** flow, shared by both hosts (cluster D4).
//
// Base: desktop's `FinishDialogContent` (940-line `desktop/chat/FinishDialog.kt`), which was itself
// a port of Android's `FinishSheet` — the 3-state machine, the 15 outcome bodies and `issueMessage`
// are byte-faithful to both. What this file adds is the CONTAINER branch:
//
//   • WindowWidthClass.Compact → Material3 [ModalBottomSheet] (Android's `finish_sheet` shape)
//   • anything wider           → the [Dialog] window (desktop's shape)
//
// Unioned in from Android: `onAck` (acking the unacked-result dot when the flow opens). Unioned in
// from desktop: `finishDotIsError` off `:shared` (instead of an inline `status == "failed"`), the
// extra test tags (`finish_dialog`, `finish_readiness_card`, `finish_running`, `finish_outcome`,
// `finish_run_tests`, `finish_skip_tests`, `finish_done`, `finish_dismiss`), Material icons instead
// of Android's `R.drawable.ic_*`, and `LocalPlatform.openUrl` instead of an Intent/AWT call.
package dev.supermux.ui.chat

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.canSkipTests
import dev.supermux.chat.finishDotIsError
import dev.supermux.net.FinishReadiness
import dev.supermux.net.FinishResult
import dev.supermux.net.VerifySaveResult
import dev.supermux.net.VerifySuggestResult
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.retain.rememberRetained
import dev.supermux.ui.retain.rememberRetainedScope
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.widgets.Dialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural

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
                imageVector = Icons.AutoMirrored.Filled.CallMerge,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                localized(Str.composer_finish_button),
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
                    .background(if (finishDotIsError(finishJob)) cs.error else cs.primary)
                    .testTag("finish_unacked_dot"),
            )
        }
    }
}

/** Local draft of an edited verify script (the no_verify recovery path). */
data class VerifyDraft(val content: String, val source: String)

/** The three render states of the flow, derived from the live [FinishJobDto]. */
private enum class FinishView { Menu, Running, Outcome }

/**
 * The chat **Finish** flow — one adaptive surface, native-M3 parity with iOS `FinishSheet.swift`.
 *
 * A three-state machine driven entirely by [finishJob] (kept fresh by the WS `finish_job` frame via
 * [dev.supermux.state.HostStore.finishJobs]): **Menu** (readiness preflight → Merge / Open PR /
 * Keep / Discard) → **Running** (live `stage`) → **Outcome** (per-status recovery). Because
 * `finishJob` is a parameter, a WS flip `running → done|failed` recomposes the body with no extra
 * code.
 *
 * The container is the ONLY thing that differs by size class: a [ModalBottomSheet] tagged
 * `finish_sheet` on a Compact window (a phone — Android's original shape), a [Dialog] window
 * anywhere else (desktop's shape). Both wrap the same [FinishFlowContent], which is also the
 * windowless `runComposeUiTest` seam.
 *
 * [onAck] (Android) marks the unacked-result dot seen the moment the flow opens.
 *
 * [form] keeps what is typed and the verify work in flight outside this composition, for a flow
 * that a rotation or a width change rebuilds (#89); null keeps them in this composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinishFlow(
    session: SessionInfo,
    finishJob: FinishJobDto?,
    onReadiness: suspend () -> FinishReadiness?,
    onFinish: (action: String, skipVerify: Boolean?, commitFirst: Boolean?, commitMessage: String?, onKickoff: (Boolean) -> Unit) -> Unit,
    onClearJob: () -> Unit,
    onVerifySuggest: suspend () -> VerifySuggestResult?,
    onVerifySave: suspend (String) -> VerifySaveResult?,
    onSendToAgent: (String) -> Unit,
    onDismiss: () -> Unit,
    onAck: () -> Unit = {},
    form: FinishForm? = null,
) {
    val cs = MaterialTheme.colorScheme
    // Block dismiss mid-job (a brief, self-resolving state); Android hides Done while running.
    val running = finishJob != null && finishJob.status == "running"
    val body: @Composable (Modifier) -> Unit = { mod ->
        FinishFlowContent(
            session = session,
            finishJob = finishJob,
            onReadiness = onReadiness,
            onFinish = onFinish,
            onClearJob = onClearJob,
            onVerifySuggest = onVerifySuggest,
            onVerifySave = onVerifySave,
            onSendToAgent = onSendToAgent,
            onDismiss = onDismiss,
            onAck = onAck,
            modifier = mod,
            form = form,
        )
    }
    if (LocalWindowWidthClass.current == WindowWidthClass.Compact) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { if (!running) onDismiss() },
            sheetState = sheetState,
            containerColor = cs.surfaceContainerLow,
            contentColor = cs.onSurface,
            modifier = Modifier.testTag("finish_sheet"),
        ) {
            body(Modifier.fillMaxWidth())
        }
    } else {
        Dialog(onDismissRequest = { if (!running) onDismiss() }) {
            body(
                Modifier
                    .width(440.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surfaceContainerLow),
            )
        }
    }
}

/**
 * The Finish flow BODY (container-free) — the full 3-state machine + local input drafts (readiness,
 * runError, confirmingDiscard, commitMessage, verifyDraft). Extracted so the state machine is
 * testable under `runComposeUiTest` without a real window (the plan's seam). Internal, not private,
 * so the tests in this module's `jvmTest` source set can render it.
 */
@Composable
internal fun FinishFlowContent(
    session: SessionInfo,
    finishJob: FinishJobDto?,
    onReadiness: suspend () -> FinishReadiness?,
    onFinish: (action: String, skipVerify: Boolean?, commitFirst: Boolean?, commitMessage: String?, onKickoff: (Boolean) -> Unit) -> Unit,
    onClearJob: () -> Unit,
    onVerifySuggest: suspend () -> VerifySuggestResult?,
    onVerifySave: suspend (String) -> VerifySaveResult?,
    onSendToAgent: (String) -> Unit,
    onDismiss: () -> Unit,
    onAck: () -> Unit = {},
    modifier: Modifier = Modifier,
    form: FinishForm? = null,
) {
    val cs = MaterialTheme.colorScheme

    var readiness by remember { mutableStateOf<FinishReadiness?>(null) }
    var loadingReadiness by remember { mutableStateOf(false) }
    var runError by remember { mutableStateOf<String?>(null) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    // What is typed, and the verify work in flight, live in [form] when the caller keeps one (#89):
    // the work then runs on the fleet's scope, and the flow on screen when it lands reads its result.
    val kept = form ?: remember { FinishForm() }
    val workScope = if (form != null) rememberRetainedScope() else rememberCoroutineScope()
    var commitMessage by kept::commitMessage
    var verifyDraft by kept::verifyDraft
    val verifySaving = kept.verifySaving

    val view = when {
        finishJob == null -> FinishView.Menu
        finishJob.status == "running" -> FinishView.Running
        else -> FinishView.Outcome
    }

    // On open: clear any stale kickoff error, ack the badge (Android), and (re)load readiness when
    // there's no in-flight job (no job at all, or one that already finished). iOS `.task`.
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
    val kickoffError = localized(Str.composer_finish_kickoff_failed)
    val kickoff: (Boolean) -> Unit = { ok ->
        if (!ok) runError = kickoffError
    }

    // "Generate verify" and the draft's Save: started here, finished on [workScope], and cancelled
    // when the flow closes ([FinishForm.close]), as this flow's own scope used to cancel them.
    val generate: () -> Unit = {
        if (!kept.generating) {
            kept.generating = true
            kept.work = workScope.launch {
                try {
                    onVerifySuggest()?.let { kept.verifyDraft = VerifyDraft(it.content, it.source) }
                } finally {
                    kept.generating = false
                }
            }
        }
    }
    val saveVerify: () -> Unit = {
        val draft = kept.verifyDraft
        if (draft != null && !kept.verifySaving) {
            kept.verifySaving = true
            kept.work = workScope.launch {
                try {
                    if (onVerifySave(draft.content)?.ok == true) {
                        kept.verifyDraft = null
                        kept.verifySaved = true
                    }
                } finally {
                    kept.verifySaving = false
                }
            }
        }
    }
    // A saved verify.sh kicks the merge off, from the flow on screen when the save lands.
    LaunchedEffect(kept, kept.verifySaved) {
        if (!kept.verifySaved) return@LaunchedEffect
        kept.verifySaved = false
        onFinish("merge", null, null, null, kickoff)
    }

    Column(
        modifier
            .padding(bottom = 24.dp)
            .testTag("finish_dialog"),
    ) {
        // ── Title ──────────────────────────────────────────────────────────────────
        val title = when (view) {
            FinishView.Menu -> localized(Str.composer_finish_title_branch, readiness?.branch ?: session.session_branch ?: "")
            FinishView.Running -> localized(Str.composer_finish_title_running)
            FinishView.Outcome -> localized(Str.composer_finish_button)
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
                generating = kept.generating,
                onGenerate = generate,
                onSaveVerify = saveVerify,
                onFinish = onFinish,
                kickoff = kickoff,
                onSendToAgent = onSendToAgent,
                onClearJob = onClearJob,
                onDismiss = onDismiss,
            )
        }
    }
}

private typealias OnFinish =
    (action: String, skipVerify: Boolean?, commitFirst: Boolean?, commitMessage: String?, onKickoff: (Boolean) -> Unit) -> Unit

// ── Menu ─────────────────────────────────────────────────────────────────────

@Composable
private fun MenuBody(
    readiness: FinishReadiness?,
    loadingReadiness: Boolean,
    runError: String?,
    confirmingDiscard: Boolean,
    onConfirmDiscardChange: (Boolean) -> Unit,
    onFinish: OnFinish,
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
                    imageVector = Icons.Filled.WarningAmber,
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
                    Text(localized(Str.composer_finish_checking), color = cs.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        } else {
            ReadinessCard(readiness)
        }

        Spacer(Modifier.size(4.dp))

        // 3) Action rows
        if (readiness?.nothingToLand == true) {
            Text(
                localized(Str.composer_finish_nothing_to_land),
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            ActionRow(localized(Str.composer_finish_keep), Icons.Filled.Archive) {
                onFinish("keep", null, null, null, kickoff); onDismiss()
            }
            DiscardRows(confirmingDiscard, onConfirmDiscardChange, onFinish, kickoff)
        } else {
            ActionRow(
                localized(Str.composer_finish_merge_locally),
                Icons.AutoMirrored.Filled.CallMerge,
                color = if (readiness?.recommended == "merge") cs.primary else cs.onSurface,
            ) { onConfirmDiscardChange(false); pendingVerify = if (pendingVerify == "merge") null else "merge" }
            if (pendingVerify == "merge") {
                VerifyChoiceRows(
                    prompt = localized(Str.composer_finish_tests_before_merge),
                    showSkip = canSkipTests("merge", readiness?.prRequiresGreen ?: false),
                    onRun = { pendingVerify = null; onFinish("merge", false, null, null, kickoff) },
                    onSkip = { pendingVerify = null; onFinish("merge", true, null, null, kickoff) },
                )
            }
            PrRow(readiness) { onConfirmDiscardChange(false); pendingVerify = if (pendingVerify == "pr") null else "pr" }
            if (pendingVerify == "pr") {
                VerifyChoiceRows(
                    prompt = localized(Str.composer_finish_tests_before_pr),
                    showSkip = canSkipTests("pr", readiness?.prRequiresGreen ?: false),
                    onRun = { pendingVerify = null; onFinish("pr", false, null, null, kickoff) },
                    onSkip = { pendingVerify = null; onFinish("pr", true, null, null, kickoff) },
                )
            }
            ActionRow(localized(Str.composer_finish_keep), Icons.Filled.Archive) {
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
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("finish_readiness_card"),
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
                localizedPlural(Plurals.composer_finish_files_changed, r.filesChanged, r.filesChanged, r.insertions, r.deletions),
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
                    "will_conflict" -> Chip(Icons.Filled.WarningAmber, localized(Str.composer_finish_may_conflict), cs.tertiary)
                    "clean" -> Chip(Icons.Filled.Check, localized(Str.composer_finish_no_conflict), cs.primary)
                }
                if (r.dirtyFiles.isNotEmpty()) {
                    Chip(Icons.Filled.WarningAmber, localizedPlural(Plurals.composer_finish_uncommitted, r.dirtyFiles.size, r.dirtyFiles.size), cs.tertiary)
                }
            }
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
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
    val label = if (readiness?.hasRemote == true && !readiness.ghAvailable) {
        localized(Str.composer_finish_push_open_pr)
    } else {
        localized(Str.composer_finish_open_pr)
    }
    val noRemote = readiness != null && !readiness.hasRemote
    ActionRow(
        label = label,
        icon = Icons.AutoMirrored.Filled.CallSplit,
        color = if (readiness?.recommended == "pr") cs.primary else cs.onSurface,
        enabled = !noRemote,
        trailing = if (noRemote) {
            { Text(localized(Str.composer_finish_no_remote), color = cs.onSurfaceVariant, fontSize = 12.sp) }
        } else null,
    ) { onClick() }
}

@Composable
private fun DiscardRows(
    confirmingDiscard: Boolean,
    onConfirmDiscardChange: (Boolean) -> Unit,
    onFinish: OnFinish,
    kickoff: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ActionRow(localized(Str.composer_finish_discard), Icons.Filled.DeleteOutline, color = cs.error) { onConfirmDiscardChange(true) }
    if (confirmingDiscard) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Text(localized(Str.composer_finish_discard_confirm), color = cs.onSurface, fontSize = 13.sp)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onConfirmDiscardChange(false); onFinish("discard", null, null, null, kickoff) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.error,
                        contentColor = cs.onError,
                    ),
                ) { Text(localized(Str.composer_finish_discard)) }
                OutlinedButton(onClick = { onConfirmDiscardChange(false) }) { Text(localized(Str.composer_cancel)) }
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
            Button(onClick = onRun, modifier = Modifier.testTag("finish_run_tests")) { Text(localized(Str.composer_finish_run_tests)) }
            if (showSkip) {
                OutlinedButton(
                    onClick = onSkip,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.tertiary),
                    modifier = Modifier.testTag("finish_skip_tests"),
                ) { Text(localized(Str.composer_finish_skip_tests)) }
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
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag("finish_running"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator()
        Text(
            stage ?: localized(Str.composer_finish_running),
            color = cs.onSurface,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            localized(Str.composer_finish_running_hint),
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
    generating: Boolean,
    onGenerate: () -> Unit,
    onSaveVerify: () -> Unit,
    onFinish: OnFinish,
    kickoff: (Boolean) -> Unit,
    onSendToAgent: (String) -> Unit,
    onClearJob: () -> Unit,
    onDismiss: () -> Unit,
) {
    val platform = LocalPlatform.current
    val cs = MaterialTheme.colorScheme
    val o = finishJob?.outcome
    val oStatus = o?.status ?: ""

    // Done/Dismiss both clear the (terminal) job so reopening returns to the readiness menu.
    val done: () -> Unit = { onClearJob(); onDismiss() }
    val letAgentFix: () -> Unit = {
        if (o != null) { onSendToAgent(issueMessage(o)); onClearJob(); onDismiss() }
    }

    Column(Modifier.fillMaxWidth().testTag("finish_outcome")) {
        when (oStatus) {
            "integrated" -> {
                OutcomeHeader(localized(Str.composer_finish_merged_into, o?.base ?: localized(Str.composer_finish_base)), Icons.Filled.Check, cs.primary)
                DoneRow(done)
            }

            "pr_opened" -> {
                OutcomeHeader(localized(Str.composer_finish_pr_opened), Icons.AutoMirrored.Filled.CallSplit, cs.onSurface)
                o?.prUrl?.let { url ->
                    ActionRow(localized(Str.composer_finish_view_pr), Icons.AutoMirrored.Filled.OpenInNew) { platform.openUrl(url) }
                }
                DismissRow(done)
                DoneRow(done)
            }

            "branch_published" -> {
                OutcomeHeader(localized(Str.composer_finish_branch_pushed), Icons.AutoMirrored.Filled.CallSplit, cs.onSurface)
                o?.prError?.let { Caption(it) }
                o?.compareUrl?.let { url ->
                    ActionRow(localized(Str.composer_finish_open_a_pr), Icons.AutoMirrored.Filled.OpenInNew) { platform.openUrl(url) }
                }
                DismissRow(done)
                DoneRow(done)
            }

            "tests_failed" -> {
                OutcomeHeader(localized(Str.composer_finish_tests_failed), Icons.Filled.Cancel, cs.error)
                OutputBlock(o?.output)
                DismissRow(done)
                ActionRow(localized(Str.composer_finish_merge_anyway), Icons.Filled.WarningAmber, color = cs.tertiary) {
                    onFinish("merge", true, null, null, kickoff)
                }
                ActionRow(localized(Str.composer_finish_let_agent_fix), Icons.AutoMirrored.Filled.Send, onClick = letAgentFix)
            }

            "sync_conflict", "dirty_overlap" -> {
                OutcomeHeader(
                    if (oStatus == "sync_conflict") {
                        localized(Str.composer_finish_merge_conflicts)
                    } else {
                        localized(Str.composer_finish_base_unsaved)
                    },
                    Icons.Filled.WarningAmber,
                    cs.tertiary,
                )
                FileList(o?.files ?: emptyList())
                DismissRow(done)
                ActionRow(localized(Str.composer_finish_let_agent_fix), Icons.AutoMirrored.Filled.Send, onClick = letAgentFix)
            }

            "uncommitted" -> {
                OutcomeHeader(localized(Str.composer_finish_uncommitted_title), null, cs.onSurface)
                FileList(o?.files ?: emptyList())
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = onCommitMessageChange,
                    label = { Text(localized(Str.composer_finish_commit_message)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .testTag("finish_commit_message"),
                )
                DismissRow(done)
                val isPr = finishJob?.action == "pr"
                ActionRow(
                    if (isPr) localized(Str.composer_finish_commit_open_pr) else localized(Str.composer_finish_commit_merge),
                    Icons.AutoMirrored.Filled.CallMerge,
                ) {
                    onFinish(if (isPr) "pr" else "merge", null, true, commitMessage, kickoff)
                }
            }

            "no_verify" -> {
                if (verifyDraft == null) {
                    OutcomeHeader(localized(Str.composer_finish_no_verify), null, cs.onSurface)
                    DismissRow(done)
                    ActionRow(localized(Str.composer_finish_merge_unverified), Icons.Filled.WarningAmber, color = cs.tertiary) {
                        onFinish("merge", true, null, null, kickoff)
                    }
                    LoadingActionRow(localized(Str.composer_finish_generate_verify), Icons.Filled.AutoAwesome, busy = generating, onClick = onGenerate)
                } else {
                    Caption(localized(Str.composer_finish_draft, verifyDraft.source.uppercase()))
                    OutlinedTextField(
                        value = verifyDraft.content,
                        onValueChange = { onVerifyDraftChange(verifyDraft.copy(content = it)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp)
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .testTag("finish_verify_draft"),
                    )
                    DismissRow(done)
                    LoadingActionRow(localized(Str.composer_finish_save), Icons.Filled.Check, busy = verifySaving, onClick = onSaveVerify)
                }
            }

            "push_auth_failed", "push_rejected" -> {
                OutcomeHeader(localized(Str.composer_finish_push_failed), Icons.Filled.Cancel, cs.error)
                o?.message?.let { Caption(it) }
                DismissRow(done)
                ActionRow(localized(Str.composer_finish_retry), Icons.AutoMirrored.Filled.CallSplit) {
                    onFinish("pr", null, null, null, kickoff)
                }
            }

            "nothing_to_do" -> {
                OutcomeHeader(localized(Str.composer_finish_nothing_to_do), null, cs.onSurface)
                DismissRow(done)
            }

            "kept", "discarded" -> {
                OutcomeHeader(
                    if (oStatus == "kept") localized(Str.composer_finish_kept) else localized(Str.composer_finish_discarded),
                    Icons.Filled.Check,
                    cs.primary,
                )
                DoneRow(done)
            }

            "non_ff" -> {
                OutcomeHeader(localized(Str.composer_finish_base_moved), Icons.Filled.WarningAmber, cs.tertiary)
                Caption(localized(Str.composer_finish_base_moved_hint))
                DismissRow(done)
                ActionRow(localized(Str.composer_finish_merge_again), Icons.AutoMirrored.Filled.CallMerge) {
                    onFinish("merge", null, null, null, kickoff)
                }
            }

            else -> {
                OutcomeHeader(localized(Str.composer_finish_failed), Icons.Filled.Cancel, cs.error)
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
    icon: ImageVector?,
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
                if (enabled) Modifier.clickable { haptic.perform(HapticKind.Tick); onClick() }
                else Modifier,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
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

/**
 * An [ActionRow] for work in flight: a spinner while [busy]. The caller runs the work, so it can
 * outlive this row (#89: a rotation rebuilds the flow; see [FinishForm]).
 */
@Composable
private fun LoadingActionRow(
    label: String,
    icon: ImageVector,
    busy: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val active = enabled && !busy
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (active) Modifier.clickable {
                    haptic.perform(HapticKind.Tick)
                    onClick()
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
                imageVector = icon,
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
private fun OutcomeHeader(label: String, icon: ImageVector?, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
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
        TextButton(onClick = onClick, modifier = Modifier.testTag("finish_done")) {
            Text(localized(Str.composer_done), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DismissRow(onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        TextButton(onClick = onClick, modifier = Modifier.testTag("finish_dismiss")) {
            Text(localized(Str.composer_dismiss), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Build the agent message for a failed finish outcome — pure port of iOS
 * `SessionChrome.issueMessage` (web `FinishSheet.vue` `issueMessage`). Pure + testable.
 */
fun issueMessage(o: FinishResult): String = when (o.status) {
    // A prompt for the agent, kept English (FinishDialogTest asserts it).
    "sync_conflict" -> "The Finish step merged the base branch in and hit conflicts in:\n" + // i18n-ok: agent prompt
        o.files.joinToString("\n") { "- $it" } +
        "\n\nThe worktree is in a conflicted merge state — please resolve the conflicts and commit, then I'll run Finish again."
    "tests_failed" -> "The Finish step ran the tests (`${o.command ?: ""}`) and they failed:\n\n```\n${o.output ?: ""}\n```\n\nPlease fix them so the branch is green, then I'll run Finish again." // i18n-ok: agent prompt
    "dirty_overlap" -> "The base checkout has unsaved changes in: ${o.files.joinToString(", ")} — the same files my work touches. Please commit or stash them so Finish can fast-forward." // i18n-ok: agent prompt
    "push_rejected" -> "Pushing the branch for a PR was rejected because the remote has diverged: ${o.message ?: ""}. Please reconcile (pull/rebase) and I'll run Finish again." // i18n-ok: agent prompt
    else -> "Finish reported: ${o.message ?: o.status}" // i18n-ok: agent prompt
}

/**
 * Everything the Finish flow needs from the host, for ONE session. Passing this to a header
 * ([FinishHeaderButton], [dev.supermux.ui.chat.ChatPanel]) is what puts Finish on screen; a null
 * binding means the host has no finish plumbing and the button is not drawn at all.
 */
class FinishBindings(
    val job: FinishJobDto?,
    val readiness: suspend () -> FinishReadiness?,
    val finish: (action: String, skipVerify: Boolean?, commitFirst: Boolean?, commitMessage: String?, onKickoff: (Boolean) -> Unit) -> Unit,
    val clearJob: () -> Unit,
    val verifySuggest: suspend () -> VerifySuggestResult?,
    val verifySave: suspend (String) -> VerifySaveResult?,
    val sendToAgent: (String) -> Unit,
)

/**
 * The header affordance: [FinishButton] plus the flow it opens, with the unacked-dot bookkeeping
 * both hosts were duplicating. Only worktree-backed sessions get it (iOS gates on
 * `session.session_branch`), so callers can render it unconditionally.
 *
 * The acked `startedAt` is `rememberSaveable` so a result stays "seen" across a rotation or
 * process death (Android's behaviour, now desktop's too).
 */
@Composable
fun FinishHeaderButton(session: SessionInfo, bindings: FinishBindings) {
    if (session.session_branch == null) return
    // The open flow, what is typed in it and its verify work, kept with the session (#89): an
    // Android rotation recreated the chat and closed the flow, and took the commit message, a
    // verify.sh draft and a "Generate verify" in flight with it.
    val form = rememberRetained("finish:${session.id}") { FinishForm() }
    var showFlow by form::open
    var ackedStartedAt by rememberSaveable(session.id) { mutableStateOf(0.0) }
    val job = bindings.job
    val isUnacked = job != null && job.status != "running" && job.startedAt != ackedStartedAt
    FinishButton(
        finishJob = job,
        isUnacked = isUnacked,
        onClick = {
            ackedStartedAt = job?.startedAt ?: ackedStartedAt
            showFlow = true
        },
    )
    if (showFlow) {
        FinishFlow(
            session = session,
            finishJob = job,
            onReadiness = bindings.readiness,
            onFinish = bindings.finish,
            onClearJob = bindings.clearJob,
            onVerifySuggest = bindings.verifySuggest,
            onVerifySave = bindings.verifySave,
            onSendToAgent = bindings.sendToAgent,
            onAck = { ackedStartedAt = job?.startedAt ?: ackedStartedAt },
            onDismiss = { form.close() },
            form = form,
        )
    }
}

/**
 * One session's Finish flow outside its composition (`rememberRetained`, #89): open or not, the
 * commit message, the verify.sh draft, and the verify work in flight. Closing it resets all of it
 * and cancels that work, as closing the flow did when this lived in the flow.
 */
class FinishForm {
    var open by mutableStateOf(false)
    var commitMessage by mutableStateOf("Session changes") // i18n-ok: a git commit message default
    var verifyDraft by mutableStateOf<VerifyDraft?>(null)
    /** "Generate verify" in flight. */
    var generating by mutableStateOf(false)
    var verifySaving by mutableStateOf(false)
    /** The draft was saved: the flow on screen kicks the merge off. */
    var verifySaved by mutableStateOf(false)
    internal var work: Job? = null

    fun close() {
        work?.cancel()
        work = null
        open = false
        commitMessage = "Session changes" // i18n-ok: a git commit message default
        verifyDraft = null
        generating = false
        verifySaving = false
        verifySaved = false
    }
}
