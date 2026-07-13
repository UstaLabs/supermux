// The desktop New-Session launcher — a faithful port of
// apps/android/.../session/SessionLauncherScreen.kt (the composer card: project dropdown, worktree
// pill → dialog, agent/model/thinking pills, message field, staged attachments + Attach, round
// Send). Desktop deltas from Android:
//   - ModalBottomSheet pickers → DropdownMenu (model/effort/agent/project) + a Compose Dialog
//     (worktree).
//   - Android's media/document/camera pickers → ONE java.awt.FileDialog (multi-select) building
//     [dev.supermux.desktop.upload.FileChunkSource]s; the MIME is guessed via Files.probeContentType.
//   - Brand agent logos (Android drawables) → a letter tile ([AgentLetterTile]); desktop ships no
//     per-agent brand assets.
//   - Voice dictation (M5-1): the SAME MicButton/DesktopDictationController the chat composer uses
//     (dev.supermux.desktop.chat.Dictation.kt), wired to the id-less /transcribe path (no session
//     yet). The slash-command "/" menu (no loadCommands seam on this screen) and the Forge omnibox
//     clone/create UI (TODO(M4-forge)) are still omitted. Drag-and-drop staging is a TODO too (the
//     Attach button covers the must-ship path).
//
// THE SUBTLE PART (ported 1:1 from Android): the [launcherRestoring] gate plus lastSeenAgent /
// lastSeenWorkdir (NOT one-shot "armed" booleans) distinguish a draft-restore SETTLING from a
// genuine later user change, so restoring a draft never wrongly resets the model (on an agent
// echo) or the base branch (on a workdir echo). This caused a real device bug on iOS/Android, so
// the pure decision is extracted into [shouldResetModelOnAgentChange] /
// [shouldResetBaseBranchOnWorkdirChange] and unit-tested (SessionLauncherScreenTest).
package dev.supermux.desktop.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.supermux.desktop.chat.MicButton
import dev.supermux.desktop.chat.MicCapture
import dev.supermux.desktop.chat.MicRecorder
import dev.supermux.desktop.chat.rememberDesktopDictation
import dev.supermux.desktop.host.HostDot
import dev.supermux.desktop.host.HostView
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.upload.FileChunkSource
import dev.supermux.net.ChunkSource
import dev.supermux.net.ModelInfo
import dev.supermux.net.PathValidation
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.RepoInfo
import dev.supermux.net.resolveReasoningLevel
import dev.supermux.net.showReasoningPicker
import dev.supermux.proto.SessionInfo
import dev.supermux.session.formatWorkdir
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/** Sentinel id for the "Default" (null-model) row in the model picker — maps back to a null model. */
internal const val DEFAULT_MODEL_ID = "__default__"

// ── Pure settle-vs-change decisions (the subtle part; unit-tested) ───────────────────────────────

/**
 * Should picking/echoing [current] as the agent RESET the model back to Default?
 *
 * Only when this is a genuine LATER change, never a restore-settle: false while [restoring], false
 * on the very first observation ([lastSeen] == null, i.e. "never recorded yet"), and false when the
 * agent hasn't actually changed. lastSeen is the effect's own last-recorded agent — NOT a one-shot
 * boolean, which is what broke on iOS when the restore fired the effect twice for the same agent.
 */
internal fun shouldResetModelOnAgentChange(lastSeen: String?, current: String, restoring: Boolean): Boolean =
    !restoring && lastSeen != null && lastSeen != current

/**
 * Should observing [current] as the workdir RESET the base branch to the repo's current branch?
 *
 * True on a genuine workdir change ([lastSeen] non-null and different) OR when there is no base
 * branch yet ([baseBranch] blank — so a fresh repo gets its current branch seeded). Never while
 * [restoring] (the draft's own baseBranch must survive the restore-settle). Mirrors Android's
 * two-branch `if (lastSeen != null && lastSeen != workdir) … else if (baseBranch.isBlank()) …`.
 */
internal fun shouldResetBaseBranchOnWorkdirChange(
    lastSeen: String?,
    current: String,
    baseBranch: String,
    restoring: Boolean,
): Boolean =
    !restoring && ((lastSeen != null && lastSeen != current) || baseBranch.isBlank())

// ── Attachment staging (pure, unit-tested) ───────────────────────────────────────────────────────

/** Best-effort MIME for a file (java.nio Files.probeContentType), octet-stream when unknown. */
internal fun probeMime(file: File): String =
    runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: "application/octet-stream"

/**
 * Stage one picked [file] as a [StagedUpload] over a streaming [FileChunkSource] (bounded RAM —
 * bytes are read on demand at upload time, never buffered). Audio → kind "voice"; everything else
 * leaves kind null so the broker infers it from the MIME (mirrors the Android launcher).
 */
internal fun stagedUploadFor(file: File): StagedUpload {
    val mime = probeMime(file)
    return StagedUpload(FileChunkSource(file), file.name, mime, if (mime.startsWith("audio")) "voice" else null)
}

/** Local + remote branches from [RepoInfo], filtered by a case-insensitive [query] substring. */
internal fun filterBranches(repoInfo: RepoInfo?, query: String): List<String> {
    val all = (repoInfo?.branches?.local ?: emptyList()) + (repoInfo?.branches?.remote ?: emptyList())
    val q = query.trim().lowercase()
    return if (q.isEmpty()) all else all.filter { it.lowercase().contains(q) }
}

/** One attachment staged before any session exists (uploaded post-spawn by the caller's onSubmit). */
private data class StagedChip(val id: Long, val name: String, val source: ChunkSource, val mime: String)

/** Blocking AWT multi-select file picker. Runs on the caller (UI) thread — modal by AWT contract. */
private fun pickFiles(): List<File> {
    val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files?.toList() ?: emptyList()
}

/**
 * The New-Session launcher screen. Broker access is injected as suspend-lambdas (Android's style —
 * no VM ref in the composable); the app shell (M4a Task 5) binds these to [DesktopAppState].
 *
 * @param onSubmit spawns the session + stages the first message. On normal completion the draft is
 *   cleared ([onClearDraft]); a thrown exception surfaces as the inline error. The caller wires this
 *   to createSessionWithFirstMessage → select + sendMessage(consumeFirstUploads) → close (Task 5).
 */
@Composable
fun SessionLauncherScreen(
    sessions: List<SessionInfo>,
    home: String,
    onBack: () -> Unit,
    loadProjects: suspend () -> List<String>,
    validatePath: suspend (String) -> PathValidation?,
    loadModels: suspend (agent: String) -> List<ModelInfo>,
    loadReasoningLevels: suspend (agent: String, model: String?) -> ReasoningResponse?,
    loadRepoInfo: suspend (workdir: String) -> RepoInfo?,
    loadPrefs: suspend () -> LauncherPrefs,
    onPrefsChange: (LauncherPrefs) -> Unit,
    loadDraft: suspend () -> LauncherDraft,
    onDraftChange: (LauncherDraft) -> Unit,
    onClearDraft: () -> Unit,
    onSubmit: suspend (
        workdir: String,
        agent: String,
        model: String?,
        reasoningLevel: String?,
        text: String,
        staged: List<StagedUpload>,
        worktree: Boolean,
        baseBranch: String?,
    ) -> Unit,
    transcribeAudio: suspend (bytes: ByteArray, filename: String) -> String? = { _, _ -> null },
    micRecorderFactory: () -> MicCapture = { MicRecorder() },
    // ── Multi-host host picker (spec §5); defaults to single-host (no picker) ──
    hosts: List<HostView> = emptyList(),
    selectedHost: String? = null,
    onSelectHost: (String) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var workdir by remember { mutableStateOf("~") }
    var workdirTouched by remember { mutableStateOf(false) }
    var agent by remember { mutableStateOf("claude") }
    var model by remember { mutableStateOf<String?>(null) } // null == "Default"
    var message by remember { mutableStateOf(TextFieldValue("")) }
    var projects by remember { mutableStateOf(emptyList<String>()) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val agents = listOf("claude", "codex", "cursor", "opencode")

    // See the file header + the pure helpers for why launcherRestoring gates the agent/workdir
    // effects and why lastSeenAgent/lastSeenWorkdir (not one-shot booleans) are the safe way to tell
    // a restore-settle from a genuine change. draftCleared guards the dispose-flush after a submit.
    var launcherRestoring by remember { mutableStateOf(true) }
    var draftCleared by remember { mutableStateOf(false) }
    var lastSeenAgent by remember { mutableStateOf<String?>(null) }
    var lastSeenWorkdir by remember { mutableStateOf<String?>(null) }
    var launcherModels by remember { mutableStateOf(emptyMap<String, String>()) }
    var launcherReasoning by remember { mutableStateOf(emptyMap<String, String>()) }

    var models by remember { mutableStateOf(emptyList<ModelInfo>()) }
    var agentMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var reasoningMenu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }

    // Model picker — refetch on agent change; reset selection to Default only on a genuine change.
    LaunchedEffect(agent, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        models = loadModels(agent)
        if (shouldResetModelOnAgentChange(lastSeenAgent, agent, launcherRestoring)) model = null
        lastSeenAgent = agent
    }

    // Thinking-level picker — refetch on agent/model change; hide when there's no real choice.
    var reasoningLevels by remember { mutableStateOf(emptyList<ReasoningLevel>()) }
    var reasoningLevel by remember { mutableStateOf<String?>(null) }
    var reasoningVisible by remember { mutableStateOf(false) }
    LaunchedEffect(agent, model, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        val resp = loadReasoningLevels(agent, model)
        val levels = resp?.levels ?: emptyList()
        reasoningLevels = levels
        reasoningVisible = resp != null && resp.visible && showReasoningPicker(levels)
        reasoningLevel = if (reasoningVisible) resolveReasoningLevel(levels, launcherReasoning[agent]) else null
    }

    // Worktree picker — refetch repo info on workdir change; reset base branch only on genuine change.
    var repoInfo by remember { mutableStateOf<RepoInfo?>(null) }
    var useWorktree by remember { mutableStateOf(true) }
    var baseBranch by remember { mutableStateOf("") }
    var showWorktreeDialog by remember { mutableStateOf(false) }
    LaunchedEffect(workdir, launcherRestoring) {
        if (launcherRestoring) { repoInfo = null; return@LaunchedEffect }
        val info = if (workdir.isBlank()) null else loadRepoInfo(workdir)
        repoInfo = info
        if (shouldResetBaseBranchOnWorkdirChange(lastSeenWorkdir, workdir, baseBranch, launcherRestoring)) {
            baseBranch = info?.currentBranch ?: ""
        }
        lastSeenWorkdir = workdir
    }

    // Restore persisted prefs + draft ONCE. Flipping launcherRestoring false is the LAST assignment
    // so the guarded effects above only ever see fully-restored values on their first real run.
    LaunchedEffect(Unit) {
        val prefs = loadPrefs()
        agent = if (agents.contains(prefs.agent)) prefs.agent else "claude"
        launcherModels = prefs.models
        launcherReasoning = prefs.reasoningLevels
        model = prefs.models[agent]
        val draft = loadDraft()
        if (draft.workdir != null) {
            workdir = draft.workdir
            workdirTouched = true
        }
        useWorktree = draft.useWorktree
        baseBranch = draft.baseBranch
        message = TextFieldValue(draft.text, TextRange(draft.text.length))
        launcherRestoring = false
    }

    // Debounced (~400ms) draft save — gated on restoring so the restore's own writes don't re-save
    // right over themselves before settling, AND on draftCleared so a fast submit that clears the
    // draft cancels any still-pending delay (draftCleared is a KEY, so flipping it relaunches this
    // effect, cancelling the in-flight coroutine before its delay elapses). Without this a pending
    // save from just before submit would re-write the just-cleared draft — a resurrection race that
    // must not depend on the caller closing the launcher promptly.
    LaunchedEffect(workdir, workdirTouched, useWorktree, baseBranch, message.text, launcherRestoring, draftCleared) {
        if (launcherRestoring || draftCleared) return@LaunchedEffect
        delay(400)
        onDraftChange(
            LauncherDraft(
                workdir = if (workdirTouched) workdir else null,
                useWorktree = useWorktree,
                baseBranch = baseBranch,
                text = message.text,
            ),
        )
    }

    // Flush the live (non-debounced) draft on dispose so navigating away mid-debounce never loses
    // it — UNLESS a successful submit already cleared it (draftCleared), which must not be resurrected.
    DisposableEffect(Unit) {
        onDispose {
            if (!launcherRestoring && !draftCleared) {
                onDraftChange(
                    LauncherDraft(
                        workdir = if (workdirTouched) workdir else null,
                        useWorktree = useWorktree,
                        baseBranch = baseBranch,
                        text = message.text,
                    ),
                )
            }
        }
    }

    LaunchedEffect(Unit) { projects = loadProjects() }

    // Default workdir from the most recent session (web chooseDefaultProject parity).
    LaunchedEffect(sessions) {
        if (!workdirTouched && sessions.isNotEmpty()) {
            workdir = sessions.first().workdir.ifBlank { "~" }
        }
    }

    // ── Staged attachments (no session yet — uploaded post-spawn by onSubmit) ────────────────────
    val staged = remember { mutableStateListOf<StagedChip>() }
    val stagedIdGen = remember { AtomicLong(0L) }

    // ── Composer focus/send affordances ──────────────────────────────────────────────────────────
    val composerInteraction = remember { MutableInteractionSource() }
    val composerFocused by composerInteraction.collectIsFocusedAsState()
    val cardBorder by animateColorAsState(
        targetValue = if (composerFocused) cs.primary else cs.outlineVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "composer_card_border",
    )
    val sendInteraction = remember { MutableInteractionSource() }
    val sendPressed by sendInteraction.collectIsPressedAsState()
    val sendScale by animateFloatAsState(
        targetValue = if (sendPressed) 0.88f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "send_scale",
    )
    val canSend = workdir.isNotBlank() && (message.text.isNotBlank() || staged.isNotEmpty())

    // Mic dictation (M5-1): the pre-spawn launcher has no live session yet, so [transcribeAudio]
    // always routes through the id-less `/transcribe` path (bound by the caller —
    // WorkspaceRoot binds this to `app.transcribeAudio(null, bytes, name)`). resetKey = Unit since
    // there's only ever one launcher instance (no per-session scoping needed here).
    val dictation = rememberDesktopDictation(
        resetKey = Unit,
        transcribeAudio = transcribeAudio,
        onAppend = { cleaned ->
            val sep = if (message.text.isBlank()) "" else " "
            val newText = message.text + sep + cleaned
            message = TextFieldValue(newText, TextRange(newText.length))
        },
        recorderFactory = micRecorderFactory,
    )

    // Spawn → (upload staged files) → send first message. onSubmit does the broker work; success
    // clears the draft (leaving local state — the caller closes this screen right after).
    fun doSubmit() {
        if (!canSend || submitting) return
        submitting = true
        error = null
        val eligible = repoInfo?.eligible == true
        val wantsWorktree = eligible && useWorktree
        val base = if (wantsWorktree && baseBranch.isNotEmpty()) baseBranch else null
        val toUpload = staged.map {
            StagedUpload(it.source, it.name, it.mime, if (it.mime.startsWith("audio")) "voice" else null)
        }
        scope.launch {
            try {
                onSubmit(workdir.trim(), agent, model, reasoningLevel, message.text.trim(), toUpload, wantsWorktree, base)
                onClearDraft()
                draftCleared = true
            } catch (e: Exception) {
                error = e.message ?: "Failed to create session"
            } finally {
                submitting = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.surfaceContainerHigh)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.lg, vertical = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        // ── Top bar: back + title ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("launcher_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(Space.sm))
            Text("New session", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            // Multi-host: which broker this session spawns on (defaults to the active host). Hidden
            // with one host. Sits end-aligned in the top bar so it reads as scoping the whole flow.
            if (hosts.size > 1) {
                Spacer(Modifier.weight(1f))
                LauncherHostPicker(hosts = hosts, selected = selectedHost, enabled = !launcherRestoring, onSelect = onSelectHost)
            }
        }

        // ── Hero: "Let's build" + project heading-dropdown + worktree pill ──
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("Let's build", color = cs.onSurface, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Space.xs))
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Space.sm))
                        // Gated like the agent/model/effort controls: a project pick DURING the
                        // restore window (draft.workdir != null) would be clobbered by the restore
                        // effect settling — ignore taps until restore lands.
                        .clickable(enabled = !launcherRestoring) { projectMenu = true }
                        .padding(horizontal = Space.sm, vertical = Space.xs)
                        .testTag("launcher_project_field"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Text(
                        formatWorkdir(workdir, home),
                        color = cs.onSurfaceVariant,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Select project",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                ProjectPicker(
                    expanded = projectMenu,
                    current = workdir,
                    projects = projects,
                    home = home,
                    validatePath = validatePath,
                    onPick = { workdir = it; workdirTouched = true; error = null },
                    onDismiss = { projectMenu = false },
                )
            }
            if (repoInfo?.eligible == true) {
                Spacer(Modifier.height(Space.sm))
                val worktreeLabel = when {
                    !useWorktree -> "No worktree"
                    baseBranch.isNotEmpty() -> baseBranch
                    else -> repoInfo?.currentBranch ?: "HEAD"
                }
                WorktreePill(
                    label = worktreeLabel,
                    active = useWorktree,
                    onClick = { showWorktreeDialog = true },
                    modifier = Modifier.testTag("launcher_worktree"),
                )
            }
        }

        // ── Composer card ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(cs.surfaceContainerLowest)
                .border(1.dp, cardBorder, RoundedCornerShape(24.dp))
                .padding(14.dp),
        ) {
            // Staged attachment chips (name + remove; upload happens post-spawn).
            if (staged.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = Space.sm),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    staged.forEach { att ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(cs.surfaceContainerHigh)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(att.name, color = cs.onSurface, fontSize = 12.sp, maxLines = 1)
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove",
                                tint = cs.onSurfaceVariant,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { staged.removeAll { it.id == att.id } },
                            )
                        }
                    }
                }
            }

            // Text input (placeholder overlay + primary cursor + card focus border).
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 116.dp)) {
                if (message.text.isEmpty()) {
                    Text("What should the agent do?", color = cs.onSurfaceVariant, fontSize = 15.sp)
                }
                BasicTextField(
                    value = message,
                    onValueChange = { message = it; error = null },
                    textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(cs.primary),
                    interactionSource = composerInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("launcher_message")
                        // Enter submits; Shift+Enter inserts a newline.
                        .onPreviewKeyEvent { e ->
                            if (
                                e.type == KeyEventType.KeyDown &&
                                (e.key == Key.Enter || e.key == Key.NumPadEnter) &&
                                !e.isShiftPressed
                            ) {
                                doSubmit(); true
                            } else {
                                false
                            }
                        },
                )
            }

            Spacer(Modifier.height(10.dp))

            // Pickers row: [agent ▾]  [model ▾]  [effort ▾]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Box {
                    AgentPill(
                        agent = agent,
                        enabled = !launcherRestoring,
                        onClick = { agentMenu = true },
                        modifier = Modifier.testTag("launcher_agent_pill"),
                    )
                    DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                        agents.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.replaceFirstChar { it.uppercase() }) },
                                leadingIcon = { AgentLetterTile(a, size = 20.dp) },
                                modifier = Modifier.testTag("agent_$a"),
                                onClick = {
                                    agent = a
                                    onPrefsChange(LauncherPrefs(agent = a, models = launcherModels, reasoningLevels = launcherReasoning))
                                    agentMenu = false
                                },
                            )
                        }
                    }
                }

                Box(Modifier.testTag("launcher_model_picker")) {
                    val modelLabel = model?.let { id -> models.firstOrNull { it.id == id }?.displayName ?: id } ?: "Default"
                    LauncherPill(label = modelLabel, onClick = { if (!launcherRestoring) modelMenu = true })
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        val opts = listOf(DEFAULT_MODEL_ID to "Default") + models.map { it.id to it.displayName }
                        opts.forEach { (id, label) ->
                            val selected = (model ?: DEFAULT_MODEL_ID) == id
                            DropdownMenuItem(
                                text = { Text(label) },
                                trailingIcon = { if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = cs.primary) },
                                modifier = Modifier.testTag("model_$id"),
                                onClick = {
                                    val newModel = if (id == DEFAULT_MODEL_ID) null else id
                                    model = newModel
                                    launcherModels = if (newModel != null) launcherModels + (agent to newModel) else launcherModels - agent
                                    onPrefsChange(LauncherPrefs(agent = agent, models = launcherModels, reasoningLevels = launcherReasoning))
                                    modelMenu = false
                                },
                            )
                        }
                    }
                }

                if (reasoningVisible) {
                    Box(Modifier.testTag("launcher_effort_picker")) {
                        LauncherPill(
                            label = reasoningLevel?.replaceFirstChar { it.uppercase() } ?: "Effort",
                            onClick = { if (!launcherRestoring) reasoningMenu = true },
                        )
                        DropdownMenu(expanded = reasoningMenu, onDismissRequest = { reasoningMenu = false }) {
                            reasoningLevels.forEach { level ->
                                val selected = level.id == reasoningLevel
                                DropdownMenuItem(
                                    text = { Text(level.description ?: level.id) },
                                    trailingIcon = { if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = cs.primary) },
                                    modifier = Modifier.testTag("effort_${level.id}"),
                                    onClick = {
                                        reasoningLevel = level.id
                                        launcherReasoning = launcherReasoning + (agent to level.id)
                                        onPrefsChange(LauncherPrefs(agent = agent, models = launcherModels, reasoningLevels = launcherReasoning))
                                        reasoningMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))

            // Actions row: [+ attach]  <spacer>  [● send]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                IconButton(
                    onClick = {
                        // AWT FileDialog is modal + blocks the UI thread; stage each pick.
                        pickFiles().forEach { file ->
                            val up = stagedUploadFor(file)
                            staged.add(StagedChip(stagedIdGen.incrementAndGet(), up.name, up.source, up.mime))
                        }
                    },
                    modifier = Modifier.testTag("launcher_attach"),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(cs.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Attach", tint = cs.onSurfaceVariant, modifier = Modifier.size(19.dp))
                    }
                }

                Spacer(Modifier.width(Space.xs))

                MicButton(
                    recording = dictation.recording,
                    transcribing = dictation.transcribing,
                    micUnavailable = dictation.micUnavailable,
                    onClick = { if (dictation.recording) dictation.stopMic() else dictation.startMic() },
                    modifier = Modifier.testTag("launcher_mic"),
                )

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = { doSubmit() },
                    enabled = canSend && !submitting,
                    interactionSource = sendInteraction,
                    modifier = Modifier.testTag("launcher_submit"),
                ) {
                    Box(
                        modifier = Modifier
                            .scale(sendScale)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (canSend && !submitting) cs.primary else cs.primary.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Start session", tint = cs.onPrimary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        error?.let { Text(it, color = cs.error, fontSize = 12.sp, modifier = Modifier.testTag("launcher_error")) }
        dictation.errorMessage?.let {
            Text(it, color = cs.error, fontSize = 12.sp, modifier = Modifier.testTag("launcher_mic_error"))
        }

        // Folder caption — a calm restatement of the resolved workdir.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = cs.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(formatWorkdir(workdir, home), color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
        }
    }

    if (showWorktreeDialog) {
        WorktreeDialog(
            useWorktree = useWorktree,
            onToggle = { useWorktree = it },
            baseBranch = baseBranch,
            repoInfo = repoInfo,
            onPickBranch = { branch ->
                baseBranch = branch
                useWorktree = true
                showWorktreeDialog = false
            },
            onDismiss = { showWorktreeDialog = false },
        )
    }
}

/** Per-agent letter tile (desktop ships no brand logos — the Android AgentLogo fallback path). */
@Composable
private fun AgentLetterTile(agent: String?, size: Dp) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier = Modifier.size(size).clip(shape).background(cs.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (agent?.take(1) ?: "?").uppercase(),
            color = cs.onPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.5f).sp,
        )
    }
}

/**
 * Compact host chip (identity dot + short host name + chevron) — the launcher's host selector,
 * shown only with >1 paired host (spec §5). Picks which broker the new session spawns on.
 */
@Composable
private fun LauncherHostPicker(
    hosts: List<HostView>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val current = hosts.firstOrNull { it.recordId == selected } ?: hosts.firstOrNull() ?: return
    Box {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(20.dp))
                .background(cs.surfaceContainer)
                .border(1.dp, cs.outline, RoundedCornerShape(20.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                .testTag("launcher_host_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            HostDot(current.colorIndex, size = 8.dp)
            Text(current.shortLabel, color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.testTag("launcher_host_menu")) {
            hosts.forEach { h ->
                DropdownMenuItem(
                    leadingIcon = { HostDot(h.colorIndex, size = 9.dp) },
                    text = { Text(h.displayName + if (!h.online) " (offline)" else "") },
                    onClick = { onSelect(h.recordId); expanded = false },
                    modifier = Modifier.testTag("launcher_host_item_${h.recordId}"),
                )
            }
        }
    }
}

/** Compact agent chip (letter + capitalized name + chevron) — the launcher's agent selector. */
@Composable
private fun AgentPill(agent: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "agent_pill_scale",
    )
    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outline, RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(start = 5.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        AgentLetterTile(agent, size = 17.dp)
        Text(agent.replaceFirstChar { it.uppercase() }, color = cs.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

/** Shared rounded chip (label + chevron) for the model / effort pickers — desktop take on Android's
 *  ModelPill/EffortPill (which don't exist in the desktop chat package). */
@Composable
private fun LauncherPill(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pill_scale",
    )
    Row(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outline, RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label.take(20), color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

/** Capsule pill for the worktree toggle — tinted (primary) when worktree is on. */
@Composable
private fun WorktreePill(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tint = if (active) cs.primary else cs.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainer)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = tint.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
    }
}

/**
 * Forge-free project picker: a typed-and-validated path entry + the known-projects list, rendered
 * in a [DropdownMenu] (Android's ModalBottomSheet ProjectPickerSheet, minus the forge omnibox —
 * TODO(M4-forge)). Picking a project or a validated path calls [onPick] and dismisses; an invalid
 * typed path shows the broker's validation error inline (and does NOT pick).
 */
@Composable
internal fun ProjectPicker(
    expanded: Boolean,
    current: String,
    projects: List<String>,
    home: String,
    validatePath: suspend (String) -> PathValidation?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var manualPath by remember(expanded) { mutableStateOf("") }
    var validating by remember(expanded) { mutableStateOf(false) }
    var validationError by remember(expanded) { mutableStateOf<String?>(null) }

    fun confirmPath() {
        val p = manualPath.trim()
        if (p.isEmpty() || validating) return
        validating = true
        validationError = null
        scope.launch {
            val res = validatePath(p)
            validating = false
            val resolved = res?.path
            if (res != null && res.ok && !resolved.isNullOrBlank()) {
                onPick(resolved)
                onDismiss()
            } else {
                validationError = res?.error ?: "Invalid path"
            }
        }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.testTag("launcher_project_menu")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(360.dp)) {
            OutlinedTextField(
                value = manualPath,
                onValueChange = { manualPath = it; validationError = null },
                placeholder = { Text("Type a path…", color = cs.onSurfaceVariant) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { confirmPath() }, enabled = manualPath.isNotBlank() && !validating, modifier = Modifier.testTag("launcher_path_confirm")) {
                        if (validating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.primary)
                        else Icon(Icons.Filled.Check, contentDescription = "Use this path", tint = cs.onSurfaceVariant)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("launcher_path_input")
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                            confirmPath(); true
                        } else {
                            false
                        }
                    },
            )
            validationError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = cs.error, fontSize = 12.sp, modifier = Modifier.testTag("launcher_path_error"))
            }
        }
        if (projects.isNotEmpty()) HorizontalDivider()
        projects.forEach { path ->
            val selected = path == current
            DropdownMenuItem(
                text = { Text(formatWorkdir(path, home), color = if (selected) cs.primary else cs.onSurface, fontSize = 14.sp, maxLines = 1) },
                trailingIcon = { if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = cs.primary) },
                modifier = Modifier.testTag("project_row_$path"),
                onClick = { onPick(path); onDismiss() },
            )
        }
    }
}

/**
 * Worktree dialog (Android WorktreeSheet parity): an "isolated worktree" toggle plus a searchable
 * base-branch list (local + remote). Picking a branch enables the toggle and dismisses. Rendered as
 * a Compose [Dialog] (Android's ModalBottomSheet has no desktop equivalent).
 */
@Composable
private fun WorktreeDialog(
    useWorktree: Boolean,
    onToggle: (Boolean) -> Unit,
    baseBranch: String,
    repoInfo: RepoInfo?,
    onPickBranch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var search by remember { mutableStateOf("") }
    val allBranches = remember(repoInfo) {
        (repoInfo?.branches?.local ?: emptyList()) + (repoInfo?.branches?.remote ?: emptyList())
    }
    val filtered = remember(allBranches, search) { filterBranches(repoInfo, search) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surfaceContainerLow)
                .padding(vertical = 16.dp),
        ) {
            Text("Worktree", color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(!useWorktree) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Run in isolated worktree", color = cs.onSurface, fontSize = 14.sp)
                    Text(
                        "Runs on a fresh branch cut from the base below, so your working copy stays untouched.",
                        color = cs.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = useWorktree, onCheckedChange = { onToggle(it) }, modifier = Modifier.testTag("launcher_worktree_toggle"))
            }

            if (useWorktree) {
                Text("Base branch", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search branches", color = cs.onSurfaceVariant) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("launcher_branch_search"),
                )
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Text(
                        if (allBranches.isEmpty()) "No branches" else "No match",
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(filtered, key = { it }) { branch ->
                            val selected = branch == baseBranch
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPickBranch(branch) }
                                    .background(if (selected) cs.primary.copy(alpha = 0.10f) else Color.Transparent)
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    branch,
                                    color = if (selected) cs.primary else cs.onSurface,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1,
                                )
                                if (selected) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
