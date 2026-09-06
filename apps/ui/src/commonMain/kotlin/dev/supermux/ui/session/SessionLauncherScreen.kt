// The one New-Session launcher (cluster F6) — desktop's screen as the base, with Android's
// touch-side unioned in as branches of the SAME body:
//   - CHROME   `(standalone || compact) && !topBarShown` (cluster E's rule). Android's route mount
//     is `standalone`, so a phone/tablet still gets its "New session" bar + Back; desktop's pane
//     declares `topBarShown` (the shell already painted its own chrome), so it stays bar-less.
//   - CONTAINERS on `LocalPointerAvailable`: the model/effort pickers are Android's `PickerSheet`
//     under touch and desktop's `DropdownMenu` under a pointer, the worktree picker is Android's
//     `ModalBottomSheet` vs desktop's `Dialog` over ONE body, and the attach `+` opens the file
//     dialog directly under a pointer / the Photos·Files·Camera menu under touch (the D3 composer
//     rule — the camera rows are gated on `Caps.camera`, so a DeX Android tablet keeps them).
//   - ENTER is the shared per-EVENT `isComposerSendEnter()`: a soft-IME Return inserts a newline
//     even on a phone with a keyboard paired, a real Enter submits.
// Additive on both hosts: Android's "/" command menu (desktop gains it), the glossary +
// `transcribeDraft` dictation cleanup, camera capture through `Platform.captureImage/captureVideo`
// + `pendingPicks`; desktop's omnibox project picker, `initialWorkdir` (the launcher inside a
// workspace tab), `onClearDraft`, the injectable `micCapture` and the inline broker-refusal text.
//
// THE CARD IS NOT THIS FILE'S (cluster F7). The capsule, its focus border, the staged-chip strip,
// the message field + key policy, the "/" menu, attach/mic/send and the RecordingBar takeover are
// `ui/chat/Composer`, rendered here with the launcher's [dev.supermux.ui.chat.ComposerChrome],
// [dev.supermux.ui.chat.ComposerStaging] (pre-spawn: files are staged, never uploaded) and a
// `toolbar` slot into which this screen drops its OWN pills — agent, model, effort — around the
// composer's controls. What is left below is the launcher: the hero, the project picker, the
// worktree/host pills, the settle-vs-change effects, and submit.
//
// THE SUBTLE PART (identical on both hosts, and the reason it is extracted + unit-tested): the
// [launcherRestoring] gate plus lastSeenAgent / lastSeenWorkdir (NOT one-shot "armed" booleans)
// distinguish a draft-restore SETTLING from a genuine later user change, so restoring a draft never
// wrongly resets the model (on an agent echo) or the base branch (on a workdir echo). This caused a
// real device bug on iOS/Android, so the decision lives in [shouldResetModelOnAgentChange] /
// [shouldResetBaseBranchOnWorkdirChange].
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.DEFAULT_MODEL_ID
import dev.supermux.host.HostView
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.RepoInfo
import dev.supermux.net.effortSpeedometerParams
import dev.supermux.net.resolveReasoningLevel
import dev.supermux.net.showReasoningPicker
import dev.supermux.net.sortEffortLevelsLowToHigh
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.session.chooseDefaultProject
import dev.supermux.session.formatWorkdir
import dev.supermux.session.orderProjectsByRecency
import dev.supermux.session.recentWorkdirs
import dev.supermux.session.sessionsByRecency
import dev.supermux.state.LauncherDraft
import dev.supermux.state.LauncherPrefs
import dev.supermux.state.StagedUpload
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.Composer
import dev.supermux.ui.chat.ComposerActions
import dev.supermux.ui.chat.ComposerChrome
import dev.supermux.ui.chat.ComposerPill
import dev.supermux.ui.chat.ComposerRecordingTakeover
import dev.supermux.ui.chat.ComposerStagedFile
import dev.supermux.ui.chat.ComposerStaging
import dev.supermux.ui.chat.ComposerTags
import dev.supermux.ui.chat.EffortPill
import dev.supermux.ui.chat.ModelPill
import dev.supermux.ui.chat.PickerSheet
import dev.supermux.ui.host.HostDot
import dev.supermux.ui.platform.MicCapture
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.mux_logo
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.widgets.Dialog
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.ui.widgets.Speedometer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/** Identifies this screen to `Platform.pickFiles`/`captureImage` (see the chat composer's twin). */
const val LAUNCHER_PICK_REQUESTER: String = "session-launcher"

// ── Pure settle-vs-change decisions (the subtle part; unit-tested) ───────────────────────────────

/**
 * Should picking/echoing [current] as the agent RESET the model back to Default?
 *
 * Only when this is a genuine LATER change, never a restore-settle: false while [restoring], false
 * on the very first observation ([lastSeen] == null, i.e. "never recorded yet"), and false when the
 * agent hasn't actually changed. lastSeen is the effect's own last-recorded agent — NOT a one-shot
 * boolean, which is what broke on iOS when the restore fired the effect twice for the same agent.
 */
fun shouldResetModelOnAgentChange(lastSeen: String?, current: String, restoring: Boolean): Boolean =
    !restoring && lastSeen != null && lastSeen != current

/**
 * Should observing [current] as the workdir RESET the base branch to the repo's current branch?
 *
 * True on a genuine workdir change ([lastSeen] non-null and different) OR when there is no base
 * branch yet ([baseBranch] blank — so a fresh repo gets its current branch seeded). Never while
 * [restoring] (the draft's own baseBranch must survive the restore-settle). Mirrors Android's
 * two-branch `if (lastSeen != null && lastSeen != workdir) … else if (baseBranch.isBlank()) …`.
 */
fun shouldResetBaseBranchOnWorkdirChange(
    lastSeen: String?,
    current: String,
    baseBranch: String,
    restoring: Boolean,
): Boolean =
    !restoring && ((lastSeen != null && lastSeen != current) || baseBranch.isBlank())

/** Local + remote branches from [RepoInfo], filtered by a case-insensitive [query] substring. */
fun filterBranches(repoInfo: RepoInfo?, query: String): List<String> {
    val all = (repoInfo?.branches?.local ?: emptyList()) + (repoInfo?.branches?.remote ?: emptyList())
    val q = query.trim().lowercase()
    return if (q.isEmpty()) all else all.filter { it.lowercase().contains(q) }
}

/**
 * Max content width for the launcher form. Matches chat's reading column roughly so a wide detail
 * pane doesn't leave a thin ribbon of fields on ultra-wide layouts (a no-op on a phone).
 */
private val LAUNCHER_MAX_WIDTH = 720.dp

/**
 * The New-Session launcher. Broker access is injected through [actions] (cluster F1) — no store ref
 * in the composable — and both hosts mount the same composable: desktop as its launcher pane (and
 * inside a workspace tab, via [initialWorkdir]), Android at `Route.NewSession`.
 *
 * @param onSubmit spawns the session + stages the first message, returning the new session id (or
 *   null when the caller navigated itself). On normal completion the draft is cleared
 *   ([onClearDraft]) and [onOpenSession] is invoked with the id; a thrown exception surfaces as the
 *   inline `launcher_error` text — the broker's own refusal, verbatim.
 * @param onOpenSession opens the session just created. Null where the caller's [onSubmit] already
 *   selected it (desktop's shell does).
 * @param standalone true where this screen IS the route/window rather than a pane inside one.
 *   With `compact` it drives the top bar (cluster E's chrome rule).
 * @param topBarShown true when the caller already painted a top bar for this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionLauncherScreen(
    sessions: List<SessionInfo>,
    home: String,
    onBack: () -> Unit,
    /** Last message per session — drives most-recent-project default (web chooseDefaultProject). */
    lastBySession: Map<String, LogEntry?> = emptyMap(),
    /** Every broker call this screen makes (cluster F1) — see `ui/session/SessionActions.kt`. */
    actions: LauncherActions = LauncherActions(),
    loadPrefs: suspend () -> LauncherPrefs = { LauncherPrefs() },
    onPrefsChange: (LauncherPrefs) -> Unit = {},
    loadDraft: suspend () -> LauncherDraft = { LauncherDraft() },
    onDraftChange: (LauncherDraft) -> Unit = {},
    /**
     * The dispose-path draft write. [onDraftChange] is debounced and typically launches into the
     * composition's scope, which is already cancelled when the WINDOW closes — so the last 400 ms
     * of typing would be lost. The caller passes a write that outlives the composition here;
     * the default keeps the old behaviour for tests and previews.
     */
    onDraftFlush: (LauncherDraft) -> Unit = onDraftChange,
    onClearDraft: () -> Unit = {},
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
    ) -> String?,
    onSaveDraft: suspend (
        workdir: String,
        agent: String,
        model: String?,
        reasoningLevel: String?,
        text: String,
        replaceDraftId: String?,
    ) -> String? = { _, _, _, _, _, _ -> null },
    onOpenSession: ((String) -> Unit)? = null,
    /**
     * Start the project picker on this directory instead of the most-recent
     * default. Set when the composer runs inside a workspace tab so a new chat
     * lands in that workspace's work tree. A DEFAULT, not a lock — the follow
     * effect below stops as soon as it is set, so the user can still pick freely.
     */
    initialWorkdir: String? = null,
    initialDraftId: String? = null,
    initialDraft: SessionInfo? = null,
    /** The mic behind dictation; defaults to the platform's. Tests inject a fake. */
    micCapture: MicCapture? = null,
    // ── Multi-host host picker (spec §5); defaults to single-host (no picker) ──
    hosts: List<HostView> = emptyList(),
    selectedHost: String? = null,
    standalone: Boolean = false,
    topBarShown: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val pointer = LocalPointerAvailable.current
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    val chrome = (standalone || compact) && !topBarShown
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
    // Wait until launcherRestoring is false so the Unit restore's store load cannot
    // clobber server draft_payload (the race both hosts hit independently).
    var reasoningLevel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(initialDraftId, initialDraft?.id, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        val s = initialDraft ?: return@LaunchedEffect
        activeDraftId = s.id
        workdir = s.workdir
        workdirTouched = true
        if (s.agent.isNotBlank()) agent = s.agent
        if (!s.model.isNullOrBlank()) model = s.model
        if (!s.reasoningLevel.isNullOrBlank()) reasoningLevel = s.reasoningLevel
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
        val fetched = actions.launcherAgents()
        if (fetched.isNotEmpty()) {
            agents = fetched
            if (agent !in fetched) agent = fetched.first()
        }
    }

    // Model picker — refetch on agent change; reset selection to Default only on a genuine change.
    LaunchedEffect(selectedHost, agent, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        models = emptyList()
        val loadedModels = actions.launcherModels(agent)
        models = loadedModels
        if (model != null && loadedModels.none { it.id == model }) model = null
        if (shouldResetModelOnAgentChange(lastSeenAgent, agent, launcherRestoring)) model = null
        lastSeenAgent = agent
    }

    // Thinking-level picker — refetch on agent/model change; hide when there's no real choice.
    var reasoningLevels by remember { mutableStateOf(emptyList<ReasoningLevel>()) }
    var reasoningVisible by remember { mutableStateOf(false) }
    LaunchedEffect(selectedHost, agent, model, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        val resp = actions.launcherReasoning(agent, model)
        val levels = resp?.levels ?: emptyList()
        reasoningLevels = levels
        reasoningVisible = resp != null && resp.visible && showReasoningPicker(levels)
        reasoningLevel = if (reasoningVisible) resolveReasoningLevel(levels, launcherReasoning[agent]) else null
    }

    // Worktree picker — refetch repo info on workdir change; reset base branch only on genuine change.
    var repoInfo by remember { mutableStateOf<RepoInfo?>(null) }
    var useWorktree by remember { mutableStateOf(true) }
    var baseBranch by remember { mutableStateOf("") }
    var showWorktreePicker by remember { mutableStateOf(false) }
    var worktreeFetching by remember { mutableStateOf(false) }
    var fetchedRepos by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(selectedHost, workdir, launcherRestoring) {
        if (launcherRestoring) { repoInfo = null; return@LaunchedEffect }
        val switchedRepoHost = lastRepoHost != null && lastRepoHost != selectedHost
        if (switchedRepoHost) fetchedRepos = emptySet()
        val info = if (workdir.isBlank()) null else actions.launcherRepoInfo(workdir, false)
        repoInfo = info
        lastRepoHost = selectedHost
        if (switchedRepoHost || shouldResetBaseBranchOnWorkdirChange(lastSeenWorkdir, workdir, baseBranch, launcherRestoring)) {
            baseBranch = info?.currentBranch ?: ""
        }
        lastSeenWorkdir = workdir
    }
    // Re-list branches whenever the worktree picker opens; network fetch once per repo (web/iOS parity).
    LaunchedEffect(showWorktreePicker, workdir) {
        if (!showWorktreePicker || workdir.isBlank()) return@LaunchedEffect
        val root = repoInfo?.repoRoot
        val shouldFetch = root != null && root !in fetchedRepos
        worktreeFetching = shouldFetch
        val fresh = actions.launcherRepoInfo(workdir, shouldFetch)
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

    // Agent slash commands for the composer "/" menu — refetched when the agent or project changes
    // (iOS NewSessionView `.task(id: "\(agent)|\(workdir)")`). Gated on restore so it never fetches
    // against the pre-restore default agent/workdir; the holder returns [] for a blank workdir.
    var launcherCommands by remember { mutableStateOf(emptyList<SlashCommand>()) }
    LaunchedEffect(selectedHost, agent, workdir, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        launcherCommands = actions.launcherCommands(agent, workdir)
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
        val restoredWorkdir = draft.workdir
        if (restoredWorkdir != null) {
            workdir = restoredWorkdir
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

    // Flush the live (non-debounced) draft on dispose so navigating away (or closing the window)
    // mid-debounce never loses it — UNLESS a successful submit already cleared it (draftCleared),
    // which must not be resurrected.
    DisposableEffect(Unit) {
        onDispose {
            if (!launcherRestoring && !draftCleared) {
                onDraftFlush(
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
        knownProjects = actions.listProjects()
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
    // Hoisted because only this screen can turn them into the spawn's `staged` argument; the shared
    // Composer owns the picking, the recreation-stash re-delivery and the chip strip.
    val staged = remember { mutableStateListOf<ComposerStagedFile>() }
    var stagedIdGen by remember { mutableStateOf(0L) }

    // Follow the most-recently-used project as session/message data hydrates, but freeze once
    // the user engages (picked a path or started composing) — web chooseDefaultProject parity.
    val composing = message.text.isNotBlank() || staged.isNotEmpty()
    // A workspace-seeded workdir wins over the most-recent-project default: the
    // whole point is that a chat opened in a workspace starts in ITS directory.
    LaunchedEffect(initialWorkdir) {
        if (!initialWorkdir.isNullOrBlank()) { workdir = initialWorkdir; workdirTouched = true }
    }
    LaunchedEffect(recentProjectPaths, workdirTouched, composing, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        workdir = chooseDefaultProject(
            current = workdir,
            recent = recentProjectPaths,
            picked = workdirTouched,
            composing = composing,
        )
    }

    // ── The shared chat composer, wearing the launcher's clothes (cluster F7) ────────────────────
    // Solid fill, not chat's translucent one: the launcher sits ON surfaceContainerHigh, so a
    // translucent high fill would disappear; surfaceContainerLowest lifts the capsule off the page.
    val composerChrome = remember(cs) {
        ComposerChrome(
            tags = ComposerTags(
                card = "launcher_composer_card",
                input = "launcher_message",
                attach = "launcher_attach",
                mic = "launcher_mic",
                send = "launcher_submit",
                banner = "launcher_banner",
                micError = "launcher_mic_error",
                slashItemPrefix = "launcher_slash_item_",
                stagedChipPrefix = "launcher_staged_",
            ),
            cardBackground = cs.surfaceContainerLowest,
            cardVerticalPadding = 14.dp,
            animatedFocusBorder = true,
            fieldMinHeight = 120.dp,
            fieldMaxHeight = 280.dp,
            fieldFontSize = 15.sp,
            fieldLineHeight = 22.sp,
            fieldMaxLines = 12,
            capitalizeSentences = true,
            slashInsideCard = true,
            // Pre-spawn there is no session to run a CONTROL command against, so every command is
            // offered and every pick just drops its text in (iOS SlashMenu showsActionGlyph:false).
            slashInsertOnly = true,
            transientLinesInsideCard = true,
            recordingTakeover = ComposerRecordingTakeover.FieldOnTouch,
            largeTouchSend = true,
            sendContentDescription = "Start session",
        )
    }
    val composerActions = remember(actions) {
        ComposerActions(transcribeDraft = actions.transcribeDraft, loadGlossary = actions.fetchGlossary)
    }
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

    // Spawn → (upload staged files) → send first message. onSubmit does the broker work; success
    // clears the draft and hands the new id to [onOpenSession] where the caller wants it.
    fun doSubmit() {
        if (!canSend || submitting) return
        // No haptic here: the shared Composer's send path already fires the Confirm tick, and this
        // is only ever reached through it (the button and Enter are both the composer's).
        submitting = true
        error = null
        val eligible = repoInfo?.eligible == true
        val wantsWorktree = eligible && useWorktree
        val base = if (wantsWorktree && baseBranch.isNotEmpty()) baseBranch else null
        val toUpload = staged.map {
            // Audio → "voice"; everything else null so the broker infers the kind from the MIME.
            StagedUpload(it.source, it.name, it.mime, if (it.mime.startsWith("audio")) "voice" else null)
        }
        scope.launch {
            try {
                val sessionId = onSubmit(
                    workdir.trim(), agent, model, reasoningLevel, message.text.trim(),
                    toUpload, wantsWorktree, base, activeDraftId,
                )
                onClearDraft()
                draftCleared = true
                if (sessionId != null) onOpenSession?.invoke(sessionId)
            } catch (e: Exception) {
                error = e.message ?: "Failed to create session"
            } finally {
                submitting = false
            }
        }
    }

    val hostPill: @Composable () -> Unit = {
        LauncherHostPill(
            hosts = hosts,
            selected = selectedHost,
            enabled = !launcherRestoring,
            pointer = pointer,
            onSelect = actions.setActiveHost,
        )
    }

    // ── One body, two shells: the chrome gate decides whether it sits inside a Scaffold ──────────
    val body: @Composable (Modifier) -> Unit = { outer ->
        BoxWithConstraints(
            outer
                .fillMaxSize()
                .background(cs.surfaceContainerHigh),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg, vertical = Space.xl),
                // A pointer window centres the short form in the pane; a phone keeps Android's
                // top-aligned column under the bar.
                verticalArrangement = if (pointer) Arrangement.Center else Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .widthIn(max = LAUNCHER_MAX_WIDTH)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Space.lg),
                ) {
                    // Multi-host: which broker this session spawns on (defaults to the active host).
                    // A pointer window puts it top-right; touch keeps Android's under-the-heading pill.
                    if (hosts.size > 1 && pointer) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { hostPill() }
                    }

                    // ── Hero: (mark) + "Let's build" + project heading-dropdown + worktree pill ──
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        // Android's route mount leads with the mark; desktop's pane sits inside a
                        // shell that already brands the window, so it keeps its lean hero.
                        if (standalone) {
                            Spacer(Modifier.height(Space.sm))
                            Icon(
                                painter = painterResource(Res.drawable.mux_logo),
                                contentDescription = null,
                                tint = cs.onSurface.copy(alpha = 0.9f),
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.height(Space.md))
                        }
                        Text("Let's build", color = cs.onSurface, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(Space.xs))
                        // Project name IS the dropdown (iOS projectPicker / web heading-variant
                        // parity). The Box is the ANCHOR: where a pointer drives, the shared picker
                        // renders as a dropdown that must hang off this heading.
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Space.sm))
                                    // Gated like the agent/model/effort controls: a project pick
                                    // DURING the restore window (draft.workdir != null) would be
                                    // clobbered by the restore effect settling — ignore taps until
                                    // restore lands.
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
                            // Forge-aware project picker (known projects + typed path +
                            // clone/create): a bottom sheet on a touch device, an anchored
                            // dropdown wherever a pointer drives (cluster F5).
                            ProjectPicker(
                                expanded = projectMenu,
                                current = workdir,
                                projects = projects,
                                home = home,
                                actions = actions,
                                onPick = { workdir = it; workdirTouched = true; error = null },
                                onDismiss = { projectMenu = false },
                            )
                        }
                        if (hosts.size > 1 && !pointer) {
                            Spacer(Modifier.height(Space.sm))
                            hostPill()
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
                                onClick = { showWorktreePicker = true },
                                modifier = Modifier.testTag("launcher_worktree"),
                            )
                        }
                    }

                    // ── The composer card IS the shared chat Composer (cluster F7) ──────────────
                    // Only the pills below are the launcher's own; the capsule, the staged strip,
                    // the field + its key policy, the "/" menu, attach/mic/send and the recording
                    // takeover all come from `ui/chat/Composer`.
                    val agentControl: @Composable () -> Unit = {
                        Box {
                            LauncherAgentPill(
                                agent = agent,
                                pointer = pointer,
                                enabled = !launcherRestoring,
                                onClick = { agentMenu = true },
                                modifier = Modifier.testTag("launcher_agent_pill"),
                            )
                            DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                                agents.forEach { a ->
                                    DropdownMenuItem(
                                        text = { Text(a.replaceFirstChar { it.uppercase() }) },
                                        leadingIcon = { AgentLogo(a, size = if (pointer) 14.dp else 20.dp) },
                                        modifier = Modifier.testTag("agent_$a"),
                                        onClick = {
                                            agent = a
                                            onPrefsChange(
                                                LauncherPrefs(
                                                    agent = a,
                                                    models = launcherModels,
                                                    reasoningLevels = launcherReasoning,
                                                ),
                                            )
                                            agentMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    val modelLabel = model?.let { id ->
                        models.firstOrNull { it.id == id }?.displayName ?: id
                    } ?: "Default"
                    val pickModel: (String?) -> Unit = { newModel ->
                        model = newModel
                        launcherModels = if (newModel != null) {
                            launcherModels + (agent to newModel)
                        } else {
                            launcherModels - agent
                        }
                        onPrefsChange(
                            LauncherPrefs(
                                agent = agent,
                                models = launcherModels,
                                reasoningLevels = launcherReasoning,
                            ),
                        )
                    }
                    val modelControl: @Composable () -> Unit = {
                        Box(Modifier.testTag("launcher_model_picker")) {
                            if (pointer) {
                                ComposerPill(
                                    label = modelLabel,
                                    testTag = null,
                                    onClick = { if (!launcherRestoring) modelMenu = true },
                                    leadingIcon = {
                                        if (hasAgentLogo(agent)) AgentLogo(agent, size = 12.dp)
                                    },
                                )
                                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                    val opts = listOf(DEFAULT_MODEL_ID to "Default") +
                                        models.map { it.id to it.displayName }
                                    opts.forEach { (id, label) ->
                                        val selected = (model ?: DEFAULT_MODEL_ID) == id
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            trailingIcon = {
                                                if (selected) {
                                                    Icon(
                                                        Icons.Filled.Check,
                                                        null,
                                                        Modifier.size(16.dp),
                                                        tint = cs.primary,
                                                    )
                                                }
                                            },
                                            modifier = Modifier.testTag("model_$id"),
                                            onClick = {
                                                pickModel(if (id == DEFAULT_MODEL_ID) null else id)
                                                modelMenu = false
                                            },
                                        )
                                    }
                                }
                            } else {
                                ModelPill(
                                    current = modelLabel,
                                    onClick = { if (!launcherRestoring) modelMenu = true },
                                )
                                if (modelMenu) {
                                    PickerSheet(
                                        title = "Select Model",
                                        options = listOf(DEFAULT_MODEL_ID to "Default") +
                                            models.map { it.id to it.displayName },
                                        current = model ?: DEFAULT_MODEL_ID,
                                        onPick = { pickModel(if (it == DEFAULT_MODEL_ID) null else it) },
                                        onDismiss = { modelMenu = false },
                                    )
                                }
                            }
                        }
                    }
                    val pickEffort: (String) -> Unit = { level ->
                        reasoningLevel = level
                        launcherReasoning = launcherReasoning + (agent to level)
                        onPrefsChange(
                            LauncherPrefs(
                                agent = agent,
                                models = launcherModels,
                                reasoningLevels = launcherReasoning,
                            ),
                        )
                    }
                    val effortControl: @Composable () -> Unit = {
                        if (reasoningVisible) {
                            Box(Modifier.testTag("launcher_effort_picker")) {
                                if (pointer) {
                                    val (gaugeLevels, gaugeValue) = effortSpeedometerParams(
                                        current = reasoningLevel,
                                        levels = reasoningLevels,
                                    )
                                    ComposerPill(
                                        label = reasoningLevel ?: "effort",
                                        testTag = null,
                                        onClick = { if (!launcherRestoring) reasoningMenu = true },
                                        leadingIcon = {
                                            Speedometer(
                                                levels = gaugeLevels,
                                                value = gaugeValue,
                                                tint = cs.onSurfaceVariant,
                                                activeTint = cs.primary,
                                                iconSize = 14.dp,
                                                testTag = "launcher_effort_gauge",
                                            )
                                        },
                                    )
                                    DropdownMenu(
                                        expanded = reasoningMenu,
                                        onDismissRequest = { reasoningMenu = false },
                                    ) {
                                        sortEffortLevelsLowToHigh(reasoningLevels).forEach { level ->
                                            val selected = level.id == reasoningLevel
                                            DropdownMenuItem(
                                                text = { Text(level.id) },
                                                trailingIcon = {
                                                    if (selected) {
                                                        Icon(
                                                            Icons.Filled.Check,
                                                            null,
                                                            Modifier.size(16.dp),
                                                            tint = cs.primary,
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.testTag("effort_${level.id}"),
                                                onClick = {
                                                    pickEffort(level.id)
                                                    reasoningMenu = false
                                                },
                                            )
                                        }
                                    }
                                } else {
                                    EffortPill(
                                        current = reasoningLevel?.replaceFirstChar { it.uppercase() },
                                        onClick = { if (!launcherRestoring) reasoningMenu = true },
                                    )
                                    if (reasoningMenu) {
                                        PickerSheet(
                                            title = "Thinking level",
                                            options = reasoningLevels.map { it.id to (it.description ?: it.id) },
                                            current = reasoningLevel,
                                            onPick = { pickEffort(it) },
                                            onDismiss = { reasoningMenu = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val saveDraftButton: @Composable () -> Unit = {
                        TextButton(
                            onClick = { doSaveDraft() },
                            enabled = canSaveDraft && !submitting,
                            modifier = Modifier.testTag("launcher_save_draft"),
                        ) {
                            Text("Save draft", fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                    }

                    Composer(
                        // Both text APIs bound: the launcher owns a TextFieldValue because a draft
                        // restore, a "/" insert and a dictation append must land the caret at the
                        // end, which the String field cannot express.
                        draft = message.text,
                        onDraftChange = { message = TextFieldValue(it, TextRange(it.length)); error = null },
                        value = message,
                        onValueChange = { message = it; error = null },
                        sending = submitting,
                        agentWorking = false,
                        onSend = { _, _ -> doSubmit() },
                        onInterrupt = {},
                        onTranscribeAudio = actions.transcribeAudio,
                        actions = composerActions,
                        commands = launcherCommands,
                        placeholder = "What should the agent do?",
                        chrome = composerChrome,
                        staging = ComposerStaging(
                            files = staged,
                            onStage = { picked ->
                                stagedIdGen += 1
                                staged.add(
                                    ComposerStagedFile(stagedIdGen, picked.name, picked.mime, picked.source),
                                )
                            },
                            onRemove = { file -> staged.removeAll { it.id == file.id } },
                        ),
                        pickRequester = LAUNCHER_PICK_REQUESTER,
                        micCapture = micCapture,
                        sendEnabled = canSend && !submitting,
                        sendProgress = submitting,
                        toolbar = { composer ->
                            if (pointer) {
                                // Desktop's ONE toolbar: + · agent · model · effort | mic · draft · send.
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        composer.Attach()
                                        agentControl()
                                        modelControl()
                                        effortControl()
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        composer.Mic()
                                        saveDraftButton()
                                        composer.Send()
                                    }
                                }
                            } else {
                                // Android's two thumb-reachable rows: pickers, then actions.
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                                ) {
                                    agentControl()
                                    modelControl()
                                    effortControl()
                                    Spacer(Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                                ) {
                                    composer.Attach()
                                    composer.Mic()
                                    Spacer(Modifier.weight(1f))
                                    saveDraftButton()
                                    composer.Send()
                                }
                            }
                        },
                    )

                    error?.let {
                        Text(it, color = cs.error, fontSize = 12.sp, modifier = Modifier.testTag("launcher_error"))
                    }

                    // Folder caption — a calm restatement of the resolved workdir.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = cs.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            formatWorkdir(workdir, home),
                            color = cs.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    if (chrome) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("New session", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("launcher_back")) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = cs.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
                    modifier = Modifier.testTag("launcher_top_bar"),
                )
            },
            containerColor = cs.surfaceContainerHigh,
        ) { innerPadding ->
            body(Modifier.padding(innerPadding))
        }
    } else {
        body(Modifier)
    }

    if (showWorktreePicker) {
        WorktreePicker(
            pointer = pointer,
            useWorktree = useWorktree,
            onToggle = { useWorktree = it },
            baseBranch = baseBranch,
            repoInfo = repoInfo,
            loading = worktreeFetching,
            onPickBranch = { branch ->
                baseBranch = branch
                useWorktree = true
                showWorktreePicker = false
            },
            onDismiss = { showWorktreePicker = false },
        )
    }
}

/**
 * Compact host chip (identity dot + short host name + chevron) — the launcher's host selector,
 * shown only with >1 paired host (spec §5). Picks which broker the new session spawns on. A pointer
 * gets desktop's borderless-on-surfaceContainer chip; touch keeps Android's outlined pill.
 */
@Composable
private fun LauncherHostPill(
    hosts: List<HostView>,
    selected: String?,
    enabled: Boolean,
    pointer: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    var expanded by remember { mutableStateOf(false) }
    val current = hosts.firstOrNull { it.recordId == selected } ?: hosts.firstOrNull() ?: return
    Box {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(20.dp))
                .background(cs.surfaceContainer)
                .border(1.dp, cs.outline, RoundedCornerShape(20.dp))
                .clickable(enabled = enabled) { haptic.perform(HapticKind.Tick); expanded = true }
                .padding(
                    start = if (pointer) 8.dp else 11.dp,
                    end = if (pointer) 8.dp else 11.dp,
                    top = if (pointer) 4.dp else 5.dp,
                    bottom = if (pointer) 4.dp else 5.dp,
                )
                .testTag("launcher_host_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (pointer) 5.dp else 6.dp),
        ) {
            HostDot(current.colorIndex, size = if (pointer) 8.dp else 9.dp)
            Text(current.shortLabel, color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Select host",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(if (pointer) 14.dp else 12.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("launcher_host_menu"),
        ) {
            hosts.forEach { h ->
                // Both hosts' row tags kept AND both reachable without `useUnmergedTree`: a
                // DropdownMenuItem MERGES its subtree, so Android's tag nested in `text` used to be
                // invisible to the merged semantics tree. It now sits on a plain (non-merging) Box
                // around the item, which stays a node of its own.
                Box(Modifier.testTag("launcher_host_${h.recordId}")) {
                    DropdownMenuItem(
                        leadingIcon = { HostDot(h.colorIndex, size = if (pointer) 9.dp else 10.dp) },
                        text = { Text(h.displayLabel + if (!h.online) " (offline)" else "") },
                        onClick = { onSelect(h.recordId); expanded = false },
                        modifier = Modifier.fillMaxWidth().testTag("launcher_host_item_${h.recordId}"),
                    )
                }
            }
        }
    }
}

/**
 * Agent chip (logo + name + chevron). Borderless matching the chat composer's pills under a
 * pointer; Android's outlined, press-scaled capsule under touch.
 */
@Composable
private fun LauncherAgentPill(
    agent: String,
    pointer: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !pointer) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "agent_pill_scale",
    )
    Row(
        modifier = modifier
            .then(if (pointer) Modifier else Modifier.scale(scale))
            .clip(RoundedCornerShape(if (pointer) Radii.pill else 20.dp))
            .then(
                if (pointer) {
                    Modifier
                } else {
                    Modifier
                        .background(cs.surfaceContainer)
                        .border(1.dp, cs.outline, RoundedCornerShape(20.dp))
                },
            )
            // A pointer host keeps the platform ripple (desktop's agent pill lost it when the two
            // branches were merged); touch suppresses it because the press-scale IS the feedback.
            .clickable(
                interactionSource = interaction,
                indication = if (pointer) LocalIndication.current else null,
                enabled = enabled,
            ) {
                haptic.perform(HapticKind.Tick)
                onClick()
            }
            .padding(
                start = if (pointer) 8.dp else 5.dp,
                end = 8.dp,
                top = if (pointer) 5.dp else 3.dp,
                bottom = if (pointer) 5.dp else 3.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (pointer) 4.dp else 5.dp),
    ) {
        AgentLogo(agent, size = if (pointer) 12.dp else 17.dp)
        Text(
            agent.replaceFirstChar { it.uppercase() },
            color = if (pointer) cs.onSurfaceVariant else cs.onSurface,
            fontSize = if (pointer) 12.sp else 11.sp,
            fontWeight = if (pointer) FontWeight.Normal else FontWeight.Medium,
            maxLines = 1,
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = if (pointer) cs.onSurfaceVariant.copy(alpha = 0.75f) else cs.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** Capsule pill for the worktree toggle — tinted (primary) when worktree is on. */
@Composable
private fun WorktreePill(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val tint = if (active) cs.primary else cs.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainer)
            .clickable { haptic.perform(HapticKind.Tick); onClick() }
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
 * Worktree picker (iOS WorktreeSheet parity): an "isolated worktree" toggle plus a searchable
 * base-branch list (local + remote). Picking a branch enables the toggle and dismisses. ONE body,
 * two containers — Android's [ModalBottomSheet] where there is no pointer, desktop's [Dialog]
 * otherwise. The dismiss is never guarded: a swipe-down mid-fetch simply closes (M3 hides the sheet
 * BEFORE reporting the dismiss, so a guarded one would strand it composed-but-invisible).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorktreePicker(
    pointer: Boolean,
    useWorktree: Boolean,
    onToggle: (Boolean) -> Unit,
    baseBranch: String,
    repoInfo: RepoInfo?,
    loading: Boolean = false,
    onPickBranch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var search by remember { mutableStateOf("") }
    val allBranches = remember(repoInfo) {
        (repoInfo?.branches?.local ?: emptyList()) + (repoInfo?.branches?.remote ?: emptyList())
    }
    val filtered = remember(allBranches, search) { filterBranches(repoInfo, search) }

    val bodyContent: @Composable () -> Unit = {
        Text(
            "Worktree",
            color = cs.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = if (pointer) 8.dp else 12.dp),
        )

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
            Switch(
                checked = useWorktree,
                onCheckedChange = { onToggle(it) },
                modifier = Modifier.testTag("launcher_worktree_toggle"),
            )
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
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                                .testTag("launcher_branch_$branch"),
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

    if (pointer) {
        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surfaceContainerLow)
                    .padding(vertical = 16.dp)
                    .testTag("launcher_worktree_dialog"),
            ) { bodyContent() }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = cs.surfaceContainerLow,
            contentColor = cs.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
                    .testTag("launcher_worktree_sheet"),
            ) { bodyContent() }
        }
    }
}
