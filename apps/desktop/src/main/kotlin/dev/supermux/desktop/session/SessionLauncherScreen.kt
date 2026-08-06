// The desktop New-Session launcher — a faithful port of
// apps/android/.../session/SessionLauncherScreen.kt (the composer card: project dropdown, worktree
// pill → dialog, agent/model/thinking pills, message field, staged attachments + Attach, round
// Send). Desktop deltas from Android:
//   - ModalBottomSheet pickers → DropdownMenu (model/effort/agent/project) + a Compose Dialog
//     (worktree).
//   - Android's media/document/camera pickers → ONE java.awt.FileDialog (multi-select) building
//     [dev.supermux.desktop.upload.FileChunkSource]s; the MIME is guessed via Files.probeContentType.
//   - Brand agent logos → [AgentLogo] (paths ported from Android/iOS agent artwork).
//   - Voice dictation (M5-1): the SAME MicButton/DesktopDictationController the chat composer uses
//     (dev.supermux.desktop.chat.Dictation.kt), wired to the id-less /transcribe path (no session
//     yet). The Forge omnibox (clone/create via ProjectPicker, desktop-parity Task 4) is wired.
//     Drag-and-drop staging is a TODO too (the Attach button covers the must-ship path).
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Size
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.theme.Stroke
import dev.supermux.desktop.upload.FileChunkSource
import dev.supermux.net.ChunkSource
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeSearchResponse
import dev.supermux.net.ModelInfo
import dev.supermux.net.PathValidation
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.RemoteRepo
import dev.supermux.net.RepoInfo
import dev.supermux.net.resolveReasoningLevel
import dev.supermux.net.showReasoningPicker
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.session.OmniOption
import dev.supermux.session.ProjectOption
import dev.supermux.session.buildOmniboxOptions
import dev.supermux.session.chooseDefaultProject
import dev.supermux.session.formatWorkdir
import dev.supermux.session.orderProjectsByRecency
import dev.supermux.session.recentWorkdirs
import dev.supermux.session.sessionsByRecency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

/**
 * Project paths filtered by a case-insensitive [query] substring matched against BOTH the raw path
 * and the [formatWorkdir]-style display label (the tilde-prefixed form the picker actually shows).
 * An empty query returns [projects] unchanged. Mirrors iOS `filteredProjects` so a typed "~" or
 * "alpha" narrows the list the same way the user already sees on phone.
 */
internal fun filterProjects(projects: List<String>, home: String, query: String): List<String> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return projects
    return projects.filter { path ->
        path.lowercase().contains(q) || formatWorkdir(path, home).lowercase().contains(q)
    }
}

/**
 * Max content width for the launcher form on desktop. Matches chat's reading column roughly so
 * the detail pane doesn't leave a thin ribbon of fields on ultra-wide layouts.
 */
private val LAUNCHER_MAX_WIDTH = 720.dp

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
    /** Last message per session — drives most-recent-project default (web chooseDefaultProject). */
    lastBySession: Map<String, LogEntry?> = emptyMap(),
    loadProjects: suspend () -> List<String>,
    validatePath: suspend (String) -> PathValidation?,
    loadModels: suspend (agent: String) -> List<ModelInfo>,
    loadReasoningLevels: suspend (agent: String, model: String?) -> ReasoningResponse?,
    /** `fetch=true` refreshes origin remote-tracking refs (once per repo on dialog open). */
    loadRepoInfo: suspend (workdir: String, fetch: Boolean) -> RepoInfo?,
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
        replaceDraftId: String?,
    ) -> Unit,
    onSaveDraft: suspend (
        workdir: String,
        agent: String,
        model: String?,
        reasoningLevel: String?,
        text: String,
        replaceDraftId: String?,
    ) -> String? = { _, _, _, _, _, _ -> null },
    initialDraftId: String? = null,
    initialDraft: SessionInfo? = null,
    transcribeAudio: suspend (bytes: ByteArray, filename: String) -> String? = { _, _ -> null },
    micRecorderFactory: () -> MicCapture = { MicRecorder() },
    // ── Multi-host host picker (spec §5); defaults to single-host (no picker) ──
    hosts: List<HostView> = emptyList(),
    selectedHost: String? = null,
    onSelectHost: (String) -> Unit = {},
    loadAgents: suspend () -> List<String> = { emptyList() },
    // Forge omnibox for the project picker (connections + clone/create). Defaults = "no forges".
    loadForges: suspend () -> List<ForgeConnection> = { emptyList() },
    /** Null response = search failed (distinguishable from empty success). */
    searchForge: suspend (query: String) -> ForgeSearchResponse? = { ForgeSearchResponse() },
    cloneForge: suspend (connectionId: String, owner: String, name: String) -> String? = { _, _, _ -> null },
    createLocalRepo: suspend (name: String) -> String? = { null },
    createForge: suspend (connectionId: String, name: String) -> String? = { _, _ -> null },
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var workdir by remember { mutableStateOf("~") }
    var workdirTouched by remember { mutableStateOf(false) }
    var agent by remember { mutableStateOf("claude") }
    var model by remember { mutableStateOf<String?>(null) } // null == "Default"
    var message by remember { mutableStateOf(TextFieldValue("")) }
    // Broker-known project paths (unordered); UI order is derived below via recency.
    var knownProjects by remember { mutableStateOf(emptyList<String>()) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var agents by remember { mutableStateOf(listOf("claude", "codex", "cursor", "opencode", "grok")) }

    // See the file header + the pure helpers for why launcherRestoring gates the agent/workdir
    // effects and why lastSeenAgent/lastSeenWorkdir (not one-shot booleans) are the safe way to tell
    // a restore-settle from a genuine change. draftCleared guards the dispose-flush after a submit.
    var launcherRestoring by remember { mutableStateOf(true) }
    var draftCleared by remember { mutableStateOf(false) }
    var activeDraftId by remember { mutableStateOf(initialDraftId) }
    // Prefill from a reopened task-list draft. Wins over local LauncherDraft.
    // Wait until launcherRestoring is false so the Unit restore's DataStore load cannot
    // clobber server draft_payload (same race as Android SessionLauncherScreen).
    LaunchedEffect(initialDraftId, initialDraft?.id, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        val s = initialDraft ?: return@LaunchedEffect
        activeDraftId = s.id
        workdir = s.workdir
        workdirTouched = true
        if (s.agent.isNotBlank()) agent = s.agent
        if (!s.model.isNullOrBlank()) model = s.model
        val t = s.draftPayload?.text.orEmpty()
        message = TextFieldValue(t, TextRange(t.length))
    }

    var lastSeenAgent by remember { mutableStateOf<String?>(null) }
    var lastSeenWorkdir by remember { mutableStateOf<String?>(null) }
    var lastSeenHost by remember { mutableStateOf<String?>(null) }
    var lastRepoHost by remember { mutableStateOf<String?>(null) }
    var launcherModels by remember { mutableStateOf(emptyMap<String, String>()) }
    var launcherReasoning by remember { mutableStateOf(emptyMap<String, String>()) }

    var models by remember { mutableStateOf(emptyList<ModelInfo>()) }
    var agentMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var reasoningMenu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }

    LaunchedEffect(selectedHost, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        agents = listOf("claude", "codex", "cursor", "opencode", "grok")
        val fetched = loadAgents()
        if (fetched.isNotEmpty()) {
            agents = fetched
            if (agent !in fetched) agent = fetched.first()
        }
    }

    // Model picker — refetch on agent change; reset selection to Default only on a genuine change.
    LaunchedEffect(selectedHost, agent, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        models = emptyList()
        val loadedModels = loadModels(agent)
        models = loadedModels
        if (model != null && loadedModels.none { it.id == model }) model = null
        if (shouldResetModelOnAgentChange(lastSeenAgent, agent, launcherRestoring)) model = null
        lastSeenAgent = agent
    }

    // Thinking-level picker — refetch on agent/model change; hide when there's no real choice.
    var reasoningLevels by remember { mutableStateOf(emptyList<ReasoningLevel>()) }
    var reasoningLevel by remember { mutableStateOf<String?>(null) }
    var reasoningVisible by remember { mutableStateOf(false) }
    LaunchedEffect(selectedHost, agent, model, launcherRestoring) {
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
    var worktreeFetching by remember { mutableStateOf(false) }
    var fetchedRepos by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(selectedHost, workdir, launcherRestoring) {
        if (launcherRestoring) { repoInfo = null; return@LaunchedEffect }
        val switchedRepoHost = lastRepoHost != null && lastRepoHost != selectedHost
        if (switchedRepoHost) fetchedRepos = emptySet()
        val info = if (workdir.isBlank()) null else loadRepoInfo(workdir, false)
        repoInfo = info
        lastRepoHost = selectedHost
        if (switchedRepoHost || shouldResetBaseBranchOnWorkdirChange(lastSeenWorkdir, workdir, baseBranch, launcherRestoring)) {
            baseBranch = info?.currentBranch ?: ""
        }
        lastSeenWorkdir = workdir
    }
    // Re-list branches whenever the worktree dialog opens; network fetch once per repo (web/iOS parity).
    LaunchedEffect(showWorktreeDialog, workdir) {
        if (!showWorktreeDialog || workdir.isBlank()) return@LaunchedEffect
        val root = repoInfo?.repoRoot
        val shouldFetch = root != null && root !in fetchedRepos
        worktreeFetching = shouldFetch
        val fresh = loadRepoInfo(workdir, shouldFetch)
        if (fresh != null) {
            repoInfo = fresh
            if (baseBranch.isBlank()) baseBranch = fresh.currentBranch.orEmpty()
            if (shouldFetch) {
                val r = fresh.repoRoot
                if (r != null) fetchedRepos = fetchedRepos + r
            }
        }
        worktreeFetching = false
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

    // Fetch known projects per host (network). Ordering is pure/derived below so session
    // recency updates don't re-hit GET /projects.
    LaunchedEffect(selectedHost, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        val switchedHost = lastSeenHost != null && lastSeenHost != selectedHost
        lastSeenHost = selectedHost
        if (switchedHost) {
            model = null
        }
        knownProjects = emptyList()
        knownProjects = loadProjects()
    }

    val lastTs: (SessionInfo) -> String = { lastBySession[it.id]?.ts ?: "" }
    val recentProjectPaths = remember(sessions, lastBySession) {
        recentWorkdirs(sessionsByRecency(sessions, lastTs))
    }
    // Picker list: recently-active projects first (web orderProjectsByRecency parity).
    val projects = remember(knownProjects, recentProjectPaths) {
        orderProjectsByRecency(recentProjectPaths, knownProjects)
    }

    // Invalid host-local paths (e.g. draft workdir from another host) are corrected without
    // freezing the selection — only an explicit picker choice sets workdirTouched.
    // An EMPTY project list means "we could not enumerate projects" (slow host, failed fetch,
    // offline) — not "your workdir is gone". Don't reset a restored draft's workdir on that.
    LaunchedEffect(knownProjects, recentProjectPaths, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        if (knownProjects.isEmpty()) return@LaunchedEffect
        val known = projects.toHashSet()
        if (workdir.isBlank() || (workdir != "~" && workdir !in known && recentProjectPaths.none { it == workdir })) {
            workdir = recentProjectPaths.firstOrNull() ?: knownProjects.firstOrNull() ?: "~"
        }
    }

    // ── Staged attachments (no session yet — uploaded post-spawn by onSubmit) ────────────────────
    val staged = remember { mutableStateListOf<StagedChip>() }
    val stagedIdGen = remember { AtomicLong(0L) }

    // Follow the most-recently-used project as session/message data hydrates, but freeze once
    // the user engages (picked a path or started composing) — web chooseDefaultProject parity.
    val composing = message.text.isNotBlank() || staged.isNotEmpty()
    LaunchedEffect(recentProjectPaths, workdirTouched, composing, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        workdir = chooseDefaultProject(
            current = workdir,
            recent = recentProjectPaths,
            picked = workdirTouched,
            composing = composing,
        )
    }

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
    val canSaveDraft = workdir.isNotBlank() && message.text.isNotBlank()
    fun doSaveDraft() {
        if (!canSaveDraft || submitting) return
        scope.launch {
            submitting = true
            error = null
            try {
                val id = onSaveDraft(workdir.trim(), agent, model, reasoningLevel, message.text.trim(), activeDraftId)
                if (id != null) {
                    onClearDraft()
                    draftCleared = true
                    onBack()
                } else {
                    error = "Couldn't save draft"
                }
            } catch (e: Exception) {
                error = e.message ?: "Couldn't save draft"
            } finally {
                submitting = false
            }
        }
    }


    // Mic dictation (M5-1): the pre-spawn launcher has no live session yet, so [transcribeAudio]
    // always routes through the id-less `/transcribe` path (bound by the caller —
    // AppShell binds this to `app.transcribeAudio(null, bytes, name)`). resetKey = Unit since
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
                onSubmit(workdir.trim(), agent, model, reasoningLevel, message.text.trim(), toUpload, wantsWorktree, base, activeDraftId)
                onClearDraft()
                draftCleared = true
            } catch (e: Exception) {
                error = e.message ?: "Failed to create session"
            } finally {
                submitting = false
            }
        }
    }

    // Cap reading width + center (horizontal + vertical). minHeight = viewport so short forms
    // sit in the middle; when content is taller, the column scrolls normally.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(cs.surfaceContainerHigh),
    ) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.lg, vertical = Space.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    Column(
        Modifier
            .widthIn(max = LAUNCHER_MAX_WIDTH)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        // Multi-host: which broker this session spawns on (defaults to the active host).
        if (hosts.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
                    loadForges = loadForges,
                    searchForge = searchForge,
                    cloneForge = cloneForge,
                    createLocalRepo = createLocalRepo,
                    createForge = createForge,
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
                                leadingIcon = { AgentLogo(a, size = 14.dp) },
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

                TextButton(
                    onClick = { doSaveDraft() },
                    enabled = canSaveDraft && !submitting,
                    modifier = Modifier.testTag("launcher_save_draft"),
                ) { Text("Save draft", fontSize = 12.sp) }
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
    } // form column
    } // scroll + vertical center shell
    } // BoxWithConstraints

    if (showWorktreeDialog) {
        WorktreeDialog(
            useWorktree = useWorktree,
            onToggle = { useWorktree = it },
            baseBranch = baseBranch,
            repoInfo = repoInfo,
            loading = worktreeFetching,
            onPickBranch = { branch ->
                baseBranch = branch
                useWorktree = true
                showWorktreeDialog = false
            },
            onDismiss = { showWorktreeDialog = false },
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
                    text = { Text(h.displayLabel + if (!h.online) " (offline)" else "") },
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
            .padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Cap to label line height (~13.sp) so the mark reads as text-adjacent, not a badge.
        AgentLogo(agent, size = 14.dp)
        Text(agent.replaceFirstChar { it.uppercase() }, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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

/** How many cloud repos to reveal per "Load more" page in the forge omnibox. */
internal const val FORGE_OMNIBOX_PAGE_SIZE = 10

/** Result of mapping an omnibox key to a UI action (pure; unit-tested). */
internal sealed class OmniboxKeyAction {
    data object Dismiss : OmniboxKeyAction()
    /** Hide the in-progress overlay only — host clone/create is not abortable. */
    data object HideResolve : OmniboxKeyAction()
    data class MoveHighlight(val index: Int) : OmniboxKeyAction()
    data class Activate(val index: Int) : OmniboxKeyAction()
}

/**
 * Map a key press to an omnibox action. Null = leave the event for the text field.
 * Pure so keyboard behaviour is testable without skiko key-injection flakiness.
 */
internal fun omniboxKeyAction(
    key: Key,
    highlight: Int,
    count: Int,
    resolving: Boolean,
): OmniboxKeyAction? {
    if (resolving) {
        return if (key == Key.Escape) OmniboxKeyAction.HideResolve else null
    }
    return when (key) {
        Key.DirectionDown -> if (count > 0) OmniboxKeyAction.MoveHighlight((highlight + 1) % count) else null
        Key.DirectionUp -> if (count > 0) OmniboxKeyAction.MoveHighlight((highlight - 1 + count) % count) else null
        Key.Enter, Key.NumPadEnter -> if (count > 0) OmniboxKeyAction.Activate(highlight.coerceIn(0, count - 1)) else null
        Key.Escape -> OmniboxKeyAction.Dismiss
        else -> null
    }
}

/**
 * Project picker with forge omnibox (Android [ProjectPickerSheet] parity): search known projects,
 * type an arbitrary path, clone a remote repo, or create a new one (local / on a forge).
 * Rendered as a [DropdownMenu] (desktop convention for the project heading-dropdown).
 *
 * Clone/create is long-running and **not abortable** on the broker (`git clone` via
 * `execFileSync`). The progress overlay offers **Hide** (not Cancel): the host keeps working;
 * if the user hides, a notice stays visible and a successful finish surfaces a ready path to pick.
 * Search failures are distinct from empty results. Keyboard: autofocus search immediately
 * (never gated on forge loading), ↑/↓, Enter, Escape.
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
    loadForges: suspend () -> List<ForgeConnection> = { emptyList() },
    /** Null = transport/5xx failure (must not look like "no repos found"). */
    searchForge: suspend (String) -> ForgeSearchResponse? = { ForgeSearchResponse() },
    cloneForge: suspend (connectionId: String, owner: String, name: String) -> String? = { _, _, _ -> null },
    createLocalRepo: suspend (name: String) -> String? = { null },
    createForge: suspend (connectionId: String, name: String) -> String? = { _, _ -> null },
    /**
     * Production leaves this true (heading dropdown). UI tests that need real [FocusRequester]
     * semantics set false — headless skiko often does not report IsFocused inside [DropdownMenu].
     */
    useDropdownMenu: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val searchFocus = remember { FocusRequester() }
    var search by remember(expanded) { mutableStateOf("") }
    var manualPath by remember(expanded) { mutableStateOf("") }
    var validating by remember(expanded) { mutableStateOf(false) }
    var validationError by remember(expanded) { mutableStateOf<String?>(null) }
    var connections by remember(expanded) { mutableStateOf(emptyList<ForgeConnection>()) }
    var cloudRepos by remember(expanded) { mutableStateOf(emptyList<RemoteRepo>()) }
    var searching by remember(expanded) { mutableStateOf(false) }
    var searchError by remember(expanded) { mutableStateOf<String?>(null) }
    var searchEmpty by remember(expanded) { mutableStateOf(false) }
    var cloudVisible by remember(expanded) { mutableStateOf(FORGE_OMNIBOX_PAGE_SIZE) }
    var resolving by remember(expanded) { mutableStateOf(false) }
    var resolveLabel by remember(expanded) { mutableStateOf("") }
    var resolveError by remember(expanded) { mutableStateOf<String?>(null) }
    var resolveJob by remember(expanded) { mutableStateOf<Job?>(null) }
    /** User hid the progress overlay while the host op is still in flight. */
    var resolveHid by remember(expanded) { mutableStateOf(false) }
    /** Path completed after Hide — discoverable one-click use (not silent disk materialisation). */
    var readyPath by remember(expanded) { mutableStateOf<String?>(null) }
    var searchAutofocused by remember(expanded) { mutableStateOf(false) }
    var highlight by remember(expanded) { mutableStateOf(0) }

    val query = search.trim()
    val projectOptions = remember(projects, home) {
        projects.map { ProjectOption(it, formatWorkdir(it, home)) }
    }

    // Autofocus immediately when the menu opens — never wait on broker forge loading.
    // searchAutofocused is set only from onFocusChanged (real focus), not after requestFocus(),
    // so tests that see the ready tag have proof the field is focused — not a side-effect flag.
    LaunchedEffect(expanded) {
        if (expanded) {
            runCatching { searchFocus.requestFocus() }
        }
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            connections = loadForges()
        }
    }

    // Debounced forge search (≥2 chars, only with connections) — Android/web parity.
    // Null response → error state; empty repos → empty message (not silent Create-only).
    LaunchedEffect(query, connections, expanded) {
        if (!expanded || connections.isEmpty() || query.length < 2) {
            cloudRepos = emptyList()
            searching = false
            searchError = null
            searchEmpty = false
            cloudVisible = FORGE_OMNIBOX_PAGE_SIZE
            return@LaunchedEffect
        }
        delay(250)
        searching = true
        searchError = null
        searchEmpty = false
        val result = searchForge(query)
        searching = false
        if (result == null) {
            cloudRepos = emptyList()
            searchError = "Couldn't search repositories — check the connection and try again."
            searchEmpty = false
        } else {
            cloudRepos = result.repos
            cloudVisible = FORGE_OMNIBOX_PAGE_SIZE
            val partial = result.errors
                .mapNotNull { it.message.takeIf { m -> m.isNotBlank() } }
                .distinct()
                .take(2)
            searchError = when {
                partial.isNotEmpty() && result.repos.isEmpty() ->
                    partial.joinToString(" · ")
                partial.isNotEmpty() ->
                    "Some forges failed: ${partial.joinToString(" · ")}"
                else -> null
            }
            searchEmpty = result.repos.isEmpty() && partial.isEmpty()
        }
    }

    val pagedCloud = remember(cloudRepos, cloudVisible) {
        cloudRepos.take(cloudVisible)
    }
    val options = remember(query, projectOptions, pagedCloud, connections) {
        buildOmniboxOptions(query, projectOptions, pagedCloud, connections)
    }
    val locals = options.filterIsInstance<OmniOption.Local>()
    val clouds = options.filterIsInstance<OmniOption.Cloud>()
    val creates = options.filterIsInstance<OmniOption.Create>()
    val cloudGroups = remember(clouds, connections) {
        connections.mapNotNull { c ->
            val repos = clouds.filter { it.connectionId == c.id }.map { it.repo }
            if (repos.isEmpty()) null else c to repos
        }
    }
    val hasMoreCloud = cloudRepos.size > cloudVisible

    // Flat actionable rows for keyboard navigation (local pick / clone / create).
    val navTargets = remember(locals, clouds, creates) {
        buildList {
            locals.forEach { add(OmniNav.Local(it.path)) }
            clouds.forEach { add(OmniNav.Clone(it.repo)) }
            creates.forEach { add(OmniNav.Create(it.createTarget, it.label)) }
        }
    }
    LaunchedEffect(navTargets.size) {
        if (highlight >= navTargets.size) highlight = (navTargets.size - 1).coerceAtLeast(0)
    }

    fun pick(path: String) {
        onPick(path)
        onDismiss()
    }

    /**
     * Hide the progress overlay only. Does **not** abort the broker clone/create
     * (host `git clone` is synchronous and uncancellable from the client).
     */
    fun hideResolveProgress() {
        if (!resolving) return
        resolving = false
        resolveHid = true
        resolveError = null
        // Keep resolveLabel so the "continues on the host" banner can name the op.
    }

    fun resolve(label: String, block: suspend () -> String?) {
        // Block while overlay is up OR a hidden host op is still in flight.
        if (resolving || resolveJob?.isActive == true) return
        resolving = true
        resolveHid = false
        resolveLabel = label
        resolveError = null
        readyPath = null
        resolveJob = scope.launch {
            try {
                val path = block()
                if (!path.isNullOrBlank()) {
                    if (resolveHid) {
                        // User already returned to the picker — surface the path for one-click use.
                        readyPath = path
                    } else {
                        pick(path)
                    }
                } else {
                    resolveError = "Couldn't $label — check the connection and try again."
                }
            } catch (_: CancellationException) {
                // Scope disposed (menu closed / composition left) — not user Hide.
            } catch (_: Throwable) {
                resolveError = "Couldn't $label — check the connection and try again."
            } finally {
                resolving = false
                resolveLabel = ""
                resolveHid = false
                resolveJob = null
            }
        }
    }

    fun activateNav(target: OmniNav) {
        when (target) {
            is OmniNav.Local -> pick(target.path)
            is OmniNav.Clone -> resolve("clone ${target.repo.fullName}") {
                cloneForge(target.repo.connectionId, target.repo.owner, target.repo.name)
            }
            is OmniNav.Create -> resolve("create $query") {
                if (target.target == "local") createLocalRepo(query)
                else createForge(target.target, query)
            }
        }
    }

    fun confirmPath() {
        val p = manualPath.trim()
        if (p.isEmpty() || validating || resolving) return
        validating = true
        validationError = null
        scope.launch {
            val res = validatePath(p)
            validating = false
            val resolved = res?.path
            if (res != null && res.ok && !resolved.isNullOrBlank()) {
                pick(resolved)
            } else {
                validationError = res?.error ?: "Invalid path"
            }
        }
    }

    fun onOmniboxKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        return when (val action = omniboxKeyAction(e.key, highlight, navTargets.size, resolving)) {
            is OmniboxKeyAction.Dismiss -> {
                onDismiss()
                true
            }
            is OmniboxKeyAction.HideResolve -> {
                hideResolveProgress()
                true
            }
            is OmniboxKeyAction.MoveHighlight -> {
                highlight = action.index
                true
            }
            is OmniboxKeyAction.Activate -> {
                navTargets.getOrNull(action.index)?.let { activateNav(it) }
                true
            }
            null -> false
        }
    }

    // resolveHid + non-blank label: user hid while host op still in flight (label cleared in finally).
    val hostOpContinues = resolveHid && resolveLabel.isNotEmpty()
    val hostOpVerb = when {
        resolveLabel.startsWith("clone ") -> "Clone"
        resolveLabel.startsWith("create ") -> "Create"
        else -> "Operation"
    }
    val hostOpTarget = resolveLabel
        .removePrefix("clone ")
        .removePrefix("create ")
        .ifBlank { null }

    @Composable
    fun MenuBody() {
        Box(
            Modifier
                .width(Size.omniboxWidth)
                .focusable()
                .onPreviewKeyEvent { onOmniboxKey(it) }
                .testTag("launcher_omnibox_root"),
        ) {
            Column(Modifier.padding(horizontal = Space.md, vertical = Space.xs)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = {
                        search = it
                        resolveError = null
                        readyPath = null
                        highlight = 0
                    },
                    placeholder = {
                        Text(
                            if (connections.isEmpty()) "Search projects…"
                            else "Search projects, repos, or type a path",
                            color = cs.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    enabled = !resolving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocus)
                        .onFocusChanged { if (it.isFocused) searchAutofocused = true }
                        .testTag("launcher_project_search")
                        .onPreviewKeyEvent { onOmniboxKey(it) },
                )
                // Ready only after the search field actually receives focus (onFocusChanged).
                // Holds while loadForges is still pending when autofocus is independent of forges.
                if (searchAutofocused) {
                    Text(
                        "",
                        modifier = Modifier
                            .size(1.dp)
                            .testTag("launcher_project_autofocus_ready"),
                    )
                }
                Spacer(Modifier.height(Space.xs))
                OutlinedTextField(
                    value = manualPath,
                    onValueChange = { manualPath = it; validationError = null },
                    placeholder = { Text("Type a path…", color = cs.onSurfaceVariant) },
                    singleLine = true,
                    enabled = !resolving,
                    trailingIcon = {
                        IconButton(
                            onClick = { confirmPath() },
                            enabled = manualPath.isNotBlank() && !validating && !resolving,
                            modifier = Modifier.testTag("launcher_path_confirm"),
                        ) {
                            if (validating) {
                                CircularProgressIndicator(
                                    Modifier.size(Space.lg),
                                    strokeWidth = Stroke.thin,
                                    color = cs.primary,
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Use this path",
                                    tint = cs.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("launcher_path_input")
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown &&
                                (e.key == Key.Enter || e.key == Key.NumPadEnter)
                            ) {
                                confirmPath()
                                true
                            } else if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                                if (resolving) hideResolveProgress() else onDismiss()
                                true
                            } else {
                                false
                            }
                        },
                )
                validationError?.let {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        it,
                        color = cs.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("launcher_path_error"),
                    )
                }
                if (hostOpContinues) {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        buildString {
                            append(hostOpVerb)
                            if (hostOpTarget != null) {
                                append(' ')
                                append(hostOpTarget)
                            }
                            append(" continues on the host…")
                        },
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("launcher_forge_host_continues"),
                    )
                }
                readyPath?.let { path ->
                    Spacer(Modifier.height(Space.xs))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        modifier = Modifier.testTag("launcher_forge_ready"),
                    ) {
                        Text(
                            "Ready — ${formatWorkdir(path, home)}",
                            color = cs.primary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { pick(path) },
                            modifier = Modifier.testTag("launcher_forge_use_ready"),
                        ) {
                            Text("Use", color = cs.primary)
                        }
                    }
                }
                resolveError?.let {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        it,
                        color = cs.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("launcher_forge_error"),
                    )
                }
                searchError?.let {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        it,
                        color = cs.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("launcher_forge_search_error"),
                    )
                }
            }

            val nothingLocal = locals.isEmpty() && cloudGroups.isEmpty() &&
                creates.isEmpty() && !searching && projects.isNotEmpty() && query.isNotEmpty()
            if (nothingLocal && connections.isEmpty()) {
                Text(
                    "No projects match \"${query}\".",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(horizontal = Space.xl - Space.xs, vertical = Space.sm)
                        .testTag("launcher_project_empty"),
                )
            }
            if (searchEmpty && !searching && query.length >= 2 && connections.isNotEmpty() &&
                cloudGroups.isEmpty()
            ) {
                Text(
                    "No repos match \"${query}\".",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(horizontal = Space.xl - Space.xs, vertical = Space.sm)
                        .testTag("launcher_forge_empty"),
                )
            }

            if (locals.isNotEmpty() || cloudGroups.isNotEmpty() || creates.isNotEmpty() ||
                searching || hasMoreCloud
            ) {
                HorizontalDivider()
            }

            Column(
                Modifier
                    .heightIn(max = Size.omniboxListMax)
                    .verticalScroll(rememberScrollState())
                    .testTag("launcher_omnibox_list"),
            ) {
                if (locals.isNotEmpty()) {
                    Text(
                        "Projects",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(
                            horizontal = Space.xl - Space.xs,
                            vertical = Space.xs,
                        ),
                    )
                    locals.forEachIndexed { i, o ->
                        val selected = o.path == current
                        val navIndex = navTargets.indexOfFirst {
                            it is OmniNav.Local && it.path == o.path
                        }
                        val hi = navIndex == highlight
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        o.path.trimEnd('/').substringAfterLast('/').ifEmpty { o.path },
                                        color = when {
                                            hi -> cs.primary
                                            selected -> cs.primary
                                            else -> cs.onSurface
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                    )
                                    Text(
                                        o.label,
                                        color = cs.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(Space.lg + Space.xs),
                                )
                            },
                            trailingIcon = {
                                if (selected) {
                                    Icon(Icons.Filled.Check, null, Modifier.size(Space.lg), tint = cs.primary)
                                }
                            },
                            enabled = !resolving,
                            modifier = Modifier
                                .testTag("project_row_${o.path}")
                                .then(
                                    if (hi) Modifier.background(cs.primary.copy(alpha = 0.08f))
                                    else Modifier,
                                ),
                            onClick = { pick(o.path) },
                        )
                    }
                } else if (query.isEmpty() && projects.isEmpty() && connections.isEmpty()) {
                    Text(
                        "Type a path or search your projects.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(
                            horizontal = Space.xl - Space.xs,
                            vertical = Space.lg + Space.xs,
                        ),
                    )
                }

                cloudGroups.forEach { (conn, repos) ->
                    Text(
                        "${conn.host} · @${conn.account.login}",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .padding(horizontal = Space.xl - Space.xs, vertical = Space.xs)
                            .testTag("forge_group_${conn.id}"),
                    )
                    repos.forEach { repo ->
                        val navIndex = navTargets.indexOfFirst {
                            it is OmniNav.Clone && it.repo.fullName == repo.fullName &&
                                it.repo.connectionId == repo.connectionId
                        }
                        val hi = navIndex == highlight
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        repo.name,
                                        color = if (hi) cs.primary else cs.onSurface,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                    )
                                    Text(
                                        repo.fullName,
                                        color = cs.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(Space.lg + Space.xs),
                                )
                            },
                            trailingIcon = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                                ) {
                                    Icon(
                                        Icons.Filled.Download,
                                        contentDescription = "Clone",
                                        tint = cs.onSurfaceVariant,
                                        modifier = Modifier.size(Space.md + Space.xs),
                                    )
                                    Text(
                                        "Clone",
                                        color = cs.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            },
                            enabled = !resolving,
                            modifier = Modifier
                                .testTag("forge_clone_${repo.fullName}")
                                .then(
                                    if (hi) Modifier.background(cs.primary.copy(alpha = 0.08f))
                                    else Modifier,
                                ),
                            onClick = {
                                resolve("clone ${repo.fullName}") {
                                    cloneForge(repo.connectionId, repo.owner, repo.name)
                                }
                            },
                        )
                    }
                }

                if (hasMoreCloud && !searching) {
                    TextButton(
                        onClick = {
                            cloudVisible += FORGE_OMNIBOX_PAGE_SIZE
                        },
                        enabled = !resolving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("launcher_forge_load_more"),
                    ) {
                        Text(
                            "Load more (${cloudRepos.size - cloudVisible} remaining)",
                            color = cs.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                if (searching && cloudGroups.isEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.xl - Space.xs, vertical = Space.md + Space.xs)
                            .testTag("launcher_forge_searching"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(Space.lg),
                            strokeWidth = Stroke.thin,
                            color = cs.primary,
                        )
                        Text(
                            "Searching repos…",
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                if (creates.isNotEmpty()) {
                    Text(
                        "Create",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(
                            horizontal = Space.xl - Space.xs,
                            vertical = Space.xs,
                        ),
                    )
                    creates.forEach { c ->
                        val navIndex = navTargets.indexOfFirst {
                            it is OmniNav.Create && it.target == c.createTarget
                        }
                        val hi = navIndex == highlight
                        DropdownMenuItem(
                            text = {
                                Text(
                                    c.label,
                                    color = if (hi) cs.primary else cs.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(Space.lg + Space.xs),
                                )
                            },
                            enabled = !resolving,
                            modifier = Modifier
                                .testTag("forge_create_${c.createTarget}")
                                .then(
                                    if (hi) Modifier.background(cs.primary.copy(alpha = 0.08f))
                                    else Modifier,
                                ),
                            onClick = {
                                resolve("create $query") {
                                    if (c.createTarget == "local") createLocalRepo(query)
                                    else createForge(c.createTarget, query)
                                }
                            },
                        )
                    }
                }
            }

            if (resolving) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(cs.scrim.copy(alpha = 0.35f))
                        .testTag("launcher_forge_resolving"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.lg))
                            .background(cs.surfaceContainerHigh)
                            .padding(horizontal = Space.lg, vertical = Space.md),
                    ) {
                        CircularProgressIndicator(
                            color = cs.primary,
                            strokeWidth = Stroke.thin,
                            modifier = Modifier.size(Space.xl),
                        )
                        Text(
                            when {
                                resolveLabel.startsWith("clone ") ->
                                    "Cloning ${resolveLabel.removePrefix("clone ")}…"
                                resolveLabel.startsWith("create ") ->
                                    "Creating ${resolveLabel.removePrefix("create ")}…"
                                else -> "Working…"
                            },
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("launcher_forge_resolving_label"),
                        )
                        // Honest: broker clone/create is not abortable — Hide only drops the
                        // overlay; the host keeps working (Agents install cancel parity).
                        Text(
                            "Continues on the host if you hide.",
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("launcher_forge_hide_hint"),
                        )
                        TextButton(
                            onClick = { hideResolveProgress() },
                            modifier = Modifier.testTag("launcher_forge_hide"),
                        ) {
                            Text("Hide", color = cs.primary)
                        }
                    }
                }
            }
        }
    }

    if (useDropdownMenu) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                // Escape/outside click while overlay is up only hides progress — host keeps going.
                if (resolving) hideResolveProgress()
                else onDismiss()
            },
            modifier = Modifier.testTag("launcher_project_menu"),
        ) {
            MenuBody()
        }
    } else if (expanded) {
        // Plain host for UI tests that assert real focus (DropdownMenu popup breaks IsFocused).
        Box(Modifier.testTag("launcher_project_menu")) {
            MenuBody()
        }
    }
}

/** Keyboard-navigable row in the forge omnibox. */
private sealed class OmniNav {
    data class Local(val path: String) : OmniNav()
    data class Clone(val repo: RemoteRepo) : OmniNav()
    data class Create(val target: String, val label: String) : OmniNav()
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
    loading: Boolean = false,
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Base branch", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
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
                        when {
                            loading && allBranches.isEmpty() -> "Fetching…"
                            allBranches.isEmpty() -> "No branches"
                            else -> "No match"
                        },
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
