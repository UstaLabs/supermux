package dev.supermux.android.session

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.chat.ContentResolverChunkSource
import dev.supermux.android.chat.MicButton
import dev.supermux.android.chat.MicDeniedDialog
import dev.supermux.android.chat.EffortPill
import dev.supermux.android.chat.ModelPill
import dev.supermux.android.chat.PickerSheet
import dev.supermux.android.chat.RecordingBar
import dev.supermux.android.chat.TranscribingIndicator
import dev.supermux.android.chat.SlashMenu
import dev.supermux.android.chat.activeSlashQuery
import dev.supermux.android.chat.createImageUri
import dev.supermux.android.chat.createVideoUri
import dev.supermux.android.chat.rememberDictation
import dev.supermux.android.chat.replaceSlashToken
import dev.supermux.android.chat.slashCommandMatches
import dev.supermux.android.chat.slashInsertText
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.resolveReasoningLevel
import dev.supermux.net.showReasoningPicker
import dev.supermux.net.RemoteRepo
import dev.supermux.net.RepoInfo
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.session.formatWorkdir
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sentinel id for the "Default" (null-model) row in the model picker — maps back to a null model. */
private const val DEFAULT_MODEL_ID = "__default__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionLauncherScreen(
    sessions: List<SessionInfo>,
    home: String,
    onBack: () -> Unit,
    loadProjects: suspend () -> List<String>,
    validatePath: suspend (String) -> dev.supermux.net.PathValidation?,
    // Launcher model list for the chosen agent (no session yet); refetched when the agent changes.
    loadModels: suspend (agent: String) -> List<ModelInfo> = { emptyList() },
    // Launcher reasoning ("thinking") levels for the chosen agent+model (no session yet);
    // refetched when either changes. Codex's are per-model; Cursor/OpenCode have none.
    loadReasoningLevels: suspend (agent: String, model: String?) -> ReasoningResponse? = { _, _ -> null },
    // Git status for the chosen project; gates the worktree picker on RepoInfo.eligible.
    loadRepoInfo: suspend (workdir: String) -> RepoInfo? = { null },
    // Agent slash commands for the composer's "/" menu (no session yet); refetched on agent/project
    // change, empty = no menu (iOS NewSessionView previewCommands parity).
    loadCommands: suspend (agent: String, workdir: String) -> List<SlashCommand> = { _, _ -> emptyList() },
    // Forge omnibox for the project picker (connections + clone/create). Defaults = "no forges".
    loadForges: suspend () -> List<ForgeConnection> = { emptyList() },
    searchForge: suspend (query: String) -> List<RemoteRepo> = { emptyList() },
    cloneForge: suspend (connectionId: String, owner: String, name: String) -> String? = { _, _, _ -> null },
    createLocalRepo: suspend (name: String) -> String? = { null },
    createForge: suspend (connectionId: String, name: String) -> String? = { _, _ -> null },
    // Voice dictation — no session yet, so these hit the broker's id-less /transcribe (the session
    // only enriches cleanup context). Same wiring as chat, minus the session id.
    loadGlossary: suspend () -> List<String> = { emptyList() },
    transcribeDraft: suspend (draft: String) -> String? = { null },
    transcribeAudio: suspend (bytes: ByteArray, filename: String) -> String? = { _, _ -> null },
    // Launcher state persistence — sticky agent/model prefs, and an in-progress draft cleared
    // once a session is actually created (see onSubmit's success path below).
    loadLauncherPrefs: suspend () -> LauncherPrefs = { LauncherPrefs() },
    onLauncherPrefsChange: (LauncherPrefs) -> Unit = {},
    loadLauncherDraft: suspend () -> LauncherDraft = { LauncherDraft() },
    onLauncherDraftChange: (LauncherDraft) -> Unit = {},
    // Spawn a session and send the first message. `staged` files upload right after spawn (there is
    // no session id to upload against until then) — see AppViewModel.createSessionWithFirstMessage.
    onSubmit: suspend (workdir: String, agent: String, model: String?, reasoningLevel: String?, message: String, worktree: Boolean, baseBranch: String?, staged: List<StagedUpload>, replaceDraftId: String?) -> String,
    /** Save as draft (no agent process). Returns draft session id. */
    onSaveDraft: suspend (workdir: String, agent: String, model: String?, reasoningLevel: String?, message: String, replaceDraftId: String?) -> String? = { _, _, _, _, _, _ -> null },
    onOpenSession: (String) -> Unit,
    /** Reopened draft session id (web /new?draft=). Prefills composer from draft_payload. */
    initialDraftId: String? = null,
    initialDraft: SessionInfo? = null,
    // ── Multi-host (spec §5). Default-empty/no-op so single-host callers are unchanged. ──
    // The host picker pill selects which host to spawn on; picking one retargets every loader below
    // (models/projects/agents/commands) to that host via the caller's onSelectHost → active host.
    hosts: List<dev.supermux.android.host.HostView> = emptyList(),
    selectedHostId: String? = null,
    onSelectHost: (String) -> Unit = {},
    // The chosen host's installed/available agent kinds (GET /agents/status) — replaces the old
    // hardcoded four-agent list. Empty → keep the default fallback.
    loadAgents: suspend () -> List<String> = { emptyList() },
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    var workdir by remember { mutableStateOf("~") }
    var workdirTouched by remember { mutableStateOf(false) }
    var agent by remember { mutableStateOf("claude") }
    var model by remember { mutableStateOf<String?>(null) }     // null == "Default"
    var message by remember { mutableStateOf(TextFieldValue("")) }
    var projects by remember { mutableStateOf(emptyList<String>()) }
    var showProjectSheet by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    // Launcher state persistence — see this task's header note for why launcherRestoring gates
    // the agent/workdir effects below, and why lastSeenAgent/lastSeenWorkdir (not one-shot
    // "armed" booleans) are the safe way to distinguish restore-settling from a genuine later
    // change. launcherModels mirrors LauncherPrefs.models (the per-agent memory) so a pick can
    // reconstruct the whole prefs blob to persist.
    var launcherRestoring by remember { mutableStateOf(true) }
    // Set once a session is actually created (onSubmit's success path below), which clears the
    // persisted draft but — unlike web's composer, which blanks its own input on submit — leaves
    // this screen's local workdir/message state untouched. onOpenSession pops this screen off the
    // back stack right after, disposing it; the dispose-flush effect further down reads that
    // still-populated (now-stale) local state, so it needs this flag to know the draft was
    // already intentionally cleared and must not be resurrected.
    var draftCleared by remember { mutableStateOf(false) }
    var activeDraftId by remember { mutableStateOf(initialDraftId) }
    var lastSeenAgent by remember { mutableStateOf<String?>(null) }
    var lastSeenWorkdir by remember { mutableStateOf<String?>(null) }
    var lastSeenHostId by remember { mutableStateOf<String?>(null) }
    var lastRepoHostId by remember { mutableStateOf<String?>(null) }
    var launcherModels by remember { mutableStateOf(emptyMap<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var repoInfo by remember { mutableStateOf<RepoInfo?>(null) }
    var useWorktree by remember { mutableStateOf(true) }
    var baseBranch by remember { mutableStateOf("") }
    // The agent options come from the selected host's /agents/status (fixes the old hardcoded four);
    // the literal list is only the fallback until the fetch lands / when the host doesn't answer.
    var agents by remember { mutableStateOf(listOf("claude", "codex", "cursor", "opencode", "grok")) }
    val multiHost = hosts.size >= 2
    LaunchedEffect(selectedHostId, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        val switchedHost = lastSeenHostId != null && lastSeenHostId != selectedHostId
        lastSeenHostId = selectedHostId
        if (switchedHost) {
            model = null
        }
        agents = listOf("claude", "codex", "cursor", "opencode", "grok")
        val fetched = loadAgents()
        if (fetched.isNotEmpty()) {
            agents = fetched
            if (!fetched.contains(agent)) agent = fetched.first()
        }
    }

    // Model picker (mirrors iOS: refetch on agent change, reset selection to Default/null).
    var models by remember { mutableStateOf(emptyList<ModelInfo>()) }
    var showModelSheet by remember { mutableStateOf(false) }
    var agentMenu by remember { mutableStateOf(false) }
    LaunchedEffect(selectedHostId, agent, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        models = emptyList()
        val loadedModels = loadModels(agent)
        models = loadedModels
        if (model != null && loadedModels.none { it.id == model }) model = null
        // Reset only when the live agent genuinely differs from what this effect last recorded
        // — safe against any number of duplicate invocations for the same agent, unlike a
        // one-shot "armed" boolean (see this task's header note for why that broke on iOS).
        // lastSeenAgent == null means "never recorded yet" and must never count as a difference.
        if (lastSeenAgent != null && lastSeenAgent != agent) {
            model = null
        }
        lastSeenAgent = agent
    }

    // Thinking-level picker — mirrors the model picker: refetch the levels the broker offers for
    // this agent+model, resolve the selection (default High, keep a valid sticky choice), and hide
    // when there's no real choice. reasoningLevel is what the launcher sends on spawn;
    // launcherReasoning mirrors LauncherPrefs.reasoningLevels (per-agent memory) so a pick can
    // rebuild the whole prefs blob to persist.
    var reasoningLevels by remember { mutableStateOf(emptyList<ReasoningLevel>()) }
    var reasoningLevel by remember { mutableStateOf<String?>(null) }
    var reasoningVisible by remember { mutableStateOf(false) }
    var launcherReasoning by remember { mutableStateOf(emptyMap<String, String>()) }
    var showReasoningSheet by remember { mutableStateOf(false) }
    LaunchedEffect(selectedHostId, agent, model, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        val resp = loadReasoningLevels(agent, model)
        val levels = resp?.levels ?: emptyList()
        reasoningLevels = levels
        reasoningVisible = resp != null && resp.visible && showReasoningPicker(levels)
        // Resolve against the sticky per-agent choice so an agent switch restores it (defaulting to
        // High when unset); cleared to null when the agent/model offers nothing to send.
        reasoningLevel = if (reasoningVisible) resolveReasoningLevel(levels, launcherReasoning[agent]) else null
    }

    // Worktree picker (iOS: useWorktree defaults on; gated on repoInfo.eligible).
    var showWorktreeSheet by remember { mutableStateOf(false) }
    LaunchedEffect(selectedHostId, workdir, launcherRestoring) {
        if (launcherRestoring) { repoInfo = null; return@LaunchedEffect }
        val info = if (workdir.isBlank()) null else loadRepoInfo(workdir)
        repoInfo = info
        val switchedRepoHost = lastRepoHostId != null && lastRepoHostId != selectedHostId
        lastRepoHostId = selectedHostId
        if (switchedRepoHost || (lastSeenWorkdir != null && lastSeenWorkdir != workdir)) {
            baseBranch = info?.currentBranch ?: ""
        } else if (baseBranch.isBlank()) {
            baseBranch = info?.currentBranch ?: ""
        }
        lastSeenWorkdir = workdir
    }

    // Agent slash commands for the composer "/" menu — refetched when the agent or project changes
    // (iOS NewSessionView `.task(id: "\(agent)|\(workdir)")`). Gated on restore so it never fetches
    // against the pre-restore default agent/workdir; loadCommands returns [] for a blank workdir.
    var launcherCommands by remember { mutableStateOf(emptyList<SlashCommand>()) }
    LaunchedEffect(selectedHostId, agent, workdir, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        launcherCommands = loadCommands(agent, workdir)
    }

    // Restore persisted launcher state once. Runs after useWorktree/baseBranch are declared
    // (Kotlin needs them in scope), but correctness doesn't depend on textual position relative
    // to the two guarded effects above — launcherRestoring blocks their bodies regardless of
    // exactly when this finishes; flipping it false is this effect's LAST assignment, so both
    // guarded effects only ever see the fully-restored values on their first real run.
    LaunchedEffect(Unit) {
        val prefs = loadLauncherPrefs()
        // Validate against the known agent list — web's loadPrefs() does the same
        // (SessionLauncherView.vue:126) — so a future agent type added after this prefs blob
        // was written can't leave `agent` holding a value the picker has no matching row for.
        agent = if (agents.contains(prefs.agent)) prefs.agent else "claude"
        launcherModels = prefs.models
        launcherReasoning = prefs.reasoningLevels
        model = prefs.models[agent]
        val draft = loadLauncherDraft()
        if (draft.workdir != null) {
            workdir = draft.workdir
            workdirTouched = true
        }
        useWorktree = draft.useWorktree
        baseBranch = draft.baseBranch
        message = TextFieldValue(draft.text, TextRange(draft.text.length))
        launcherRestoring = false
    }

    
    // Prefill from a reopened task-list draft (web /new?draft= parity). Wins over local
    // LauncherDraft because the server draft_payload is authoritative.
    LaunchedEffect(initialDraftId, initialDraft?.id) {
        val s = initialDraft
        if (s != null) {
            activeDraftId = s.id
            workdir = s.workdir
            workdirTouched = true
            if (s.agent.isNotBlank()) agent = s.agent
            if (!s.model.isNullOrBlank()) model = s.model
            if (!s.reasoningLevel.isNullOrBlank()) reasoningLevel = s.reasoningLevel
            val t = s.draftPayload?.text.orEmpty()
            message = TextFieldValue(t, TextRange(t.length))
        }
    }

// Persist the in-progress draft, debounced (~400ms) — mirrors ChatScreen.kt's per-session
    // draft save. launcherRestoring gates it so the restore's own assignments (above) don't
    // immediately re-save right back over themselves before they've even settled.
    LaunchedEffect(workdir, workdirTouched, useWorktree, baseBranch, message.text, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        delay(400)
        onLauncherDraftChange(
            LauncherDraft(
                workdir = if (workdirTouched) workdir else null,
                useWorktree = useWorktree,
                baseBranch = baseBranch,
                text = message.text,
            )
        )
    }

    // Flush the current (not debounced) draft state on dispose, so navigating away mid-debounce
    // always saves whatever's on screen instead of losing it to a cancelled coroutine — mirrors
    // LauncherDraftSync.vue's onBeforeUnmount flush on web (commit e9eb0ed). draftCleared guards
    // the one case where flushing the live state would be wrong: a successful submit already
    // cleared the persisted draft and is about to dispose this screen via popBackStack, so the
    // flush must not resurrect that now-stale (just-submitted) state over the intentional clear.
    DisposableEffect(Unit) {
        onDispose {
            if (!launcherRestoring && !draftCleared) {
                onLauncherDraftChange(
                    LauncherDraft(
                        workdir = if (workdirTouched) workdir else null,
                        useWorktree = useWorktree,
                        baseBranch = baseBranch,
                        text = message.text,
                    )
                )
            }
        }
    }

    LaunchedEffect(selectedHostId, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        projects = emptyList()
        val loaded = loadProjects()
        projects = loaded
        if (workdir.isBlank() || (workdir != "~" && workdir !in loaded)) {
            workdir = loaded.firstOrNull() ?: "~"
            workdirTouched = loaded.isNotEmpty()
        }
    }

    // Default workdir from most recent session, like web chooseDefaultProject.
    LaunchedEffect(sessions) {
        if (!workdirTouched && sessions.isNotEmpty()) {
            workdir = sessions.first().workdir.ifBlank { "~" }
        }
    }

    // Voice dictation (shared with the chat composer). No session pre-spawn → the closures below
    // pass no session id, so cleanup runs off the global glossary/engine/model via /transcribe.
    val voice = rememberDictation(
        resetKey = Unit,
        loadGlossary = loadGlossary,
        transcribeDraft = transcribeDraft,
        transcribeAudio = transcribeAudio,
        onAppend = {
            val joined = if (message.text.isBlank()) it else message.text.trimEnd() + " " + it
            message = TextFieldValue(joined, TextRange(joined.length)); error = null
        },
    )

    // ── Attachment staging (no session yet) ────────────────────────────────────────────────────
    // Unlike the chat composer, the launcher has no session to upload against, so files are only
    // STAGED here (name + streaming source) and uploaded post-spawn by onSubmit → the VM (iOS
    // NewSessionView parity). Bounded RAM: the source streams from the content Uri, never buffered.
    data class StagedChip(val id: Long, val name: String, val source: dev.supermux.net.ChunkSource, val mime: String)
    val staged = remember { mutableStateListOf<StagedChip>() }
    val stagedIdGen = remember { AtomicLong(0L) }

    // Name + byte size for a content Uri (DISPLAY_NAME/SIZE, falling back to the fd's statSize).
    // Size is required to chunk the streaming upload later.
    fun queryNameSize(uri: Uri): Pair<String, Long?> {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size == null) {
            size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize.takeIf { s -> s >= 0 } } }.getOrNull()
        }
        return name to size
    }

    // Stage one attachment: build a streaming source + add a chip. No upload here (no session id).
    suspend fun stageFromUri(uri: Uri) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val (name, size) = withContext(Dispatchers.IO) { queryNameSize(uri) }
        if (size == null || size <= 0L) return
        val source = ContentResolverChunkSource(resolver, uri, size)
        staged.add(StagedChip(stagedIdGen.incrementAndGet(), name, source, mime))
    }

    // Files: system document picker (any mime).
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch { stageFromUri(uri) }
    }
    // Photos: modern visual-media picker (no storage permission).
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch { stageFromUri(uri) }
    }
    // Camera photo → our FileProvider URI, then staged back.
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok: Boolean ->
        val uri = cameraUri
        if (ok && uri != null) scope.launch { stageFromUri(uri) }
    }
    // Camera video → our FileProvider URI (separate state so a photo capture can't clobber it).
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok: Boolean ->
        val uri = videoUri
        if (ok && uri != null) scope.launch { stageFromUri(uri) }
    }

    // ── Composer send state ─────────────────────────────────────────────────────────────────────
    // Focus border animates the whole card primary-tinted when the text field is focused (mirrors
    // ChatPanel's composer focus border). Send scales on press + fires a confirm haptic.
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
            try {
                val id = onSaveDraft(workdir.trim(), agent, model, reasoningLevel, message.text.trim(), activeDraftId)
                if (id != null) {
                    onLauncherDraftChange(LauncherDraft())
                    draftCleared = true
                    onBack()
                }
            } finally {
                submitting = false
            }
        }
    }

    // Spawn → (upload staged files) → send first message → open the session. The circular send
    // button spins through the whole flow, then onOpenSession pops this screen (iOS spawn() parity).
    fun doSubmit() {
        if (!canSend || submitting) return
        haptic(HapticKind.Confirm)
        submitting = true
        error = null
        // Only honor worktree/baseBranch when the repo is eligible (iOS parity).
        val eligible = repoInfo?.eligible == true
        val wantsWorktree = eligible && useWorktree
        val base = if (wantsWorktree && baseBranch.isNotEmpty()) baseBranch else null
        val toUpload = staged.map {
            // Audio → "voice"; everything else nil so the broker infers the kind from the MIME
            // (video/* → "video" server-side). Launcher attachments are never audio, but mirror iOS.
            StagedUpload(it.source, it.name, it.mime, if (it.mime.startsWith("audio")) "voice" else null)
        }
        scope.launch {
            try {
                val sessionId = onSubmit(workdir.trim(), agent, model, reasoningLevel, message.text.trim(), wantsWorktree, base, toUpload, activeDraftId)
                onLauncherDraftChange(LauncherDraft())
                draftCleared = true
                onOpenSession(sessionId)
            } catch (e: Exception) {
                error = e.message ?: "Failed to create session"
            } finally {
                submitting = false
            }
        }
    }

    // ── Slash-command menu (mirrors ChatPanel + iOS NewSessionView) ─────────────────────────────
    // Matches for the active "/token" at the end of the draft. Insert-only here: pre-spawn there is
    // no session to run a control command against, so a pick just drops the command's text in (iOS
    // SlashMenu `showsActionGlyph: false`). Keyboard nav mirrors the chat composer for DeX keyboards.
    val slashMatches = slashCommandMatches(message.text, launcherCommands)
    var slashSelectedIndex by remember { mutableIntStateOf(0) }
    var slashDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(activeSlashQuery(message.text)) { slashSelectedIndex = 0; slashDismissed = false }
    val slashMenuOpen = slashMatches.isNotEmpty() && !slashDismissed
    val safeSlashIndex = slashSelectedIndex.coerceIn(0, (slashMatches.size - 1).coerceAtLeast(0))
    fun selectSlashCommand(cmd: SlashCommand) {
        haptic(HapticKind.Tick)
        // The token is always the draft's tail (activeSlashQuery only matches end-of-draft), so the
        // inserted command becomes the new tail — move the caret to the end, not the old offset.
        val inserted = replaceSlashToken(message.text, slashInsertText(cmd))
        message = TextFieldValue(inserted, TextRange(inserted.length))
        error = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New session", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_left),
                            contentDescription = "Back",
                            tint = cs.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainerHigh,
                ),
            )
        },
        containerColor = cs.surfaceContainerHigh,
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            // ── Hero: mark + "Let's build" + project heading-dropdown + worktree pill ──
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(Space.sm))
                Icon(
                    painter = painterResource(R.drawable.mux_logo),
                    contentDescription = null,
                    tint = cs.onSurface.copy(alpha = 0.9f),
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(Space.md))
                Text("Let's build", color = cs.onSurface, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Space.xs))
                // Project name IS the dropdown (iOS projectPicker / web heading-variant parity).
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Space.sm))
                        .clickable { showProjectSheet = true }
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
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = "Select project",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (multiHost) {
                    Spacer(Modifier.height(Space.sm))
                    HostPickerPill(
                        hosts = hosts,
                        selectedHostId = selectedHostId,
                        onSelect = onSelectHost,
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
                        onClick = { showWorktreeSheet = true },
                        modifier = Modifier.testTag("launcher_worktree"),
                    )
                }
            }

            // ── Composer card: chips → text field → [agent · model] → [+ · mic · ● send] ──
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
                                    painter = painterResource(R.drawable.ic_x),
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

                if (voice.active) {
                    // Dictating: the RecordingBar takes over the card body (parity with chat).
                    RecordingBar(
                        seconds = voice.recordingSeconds,
                        liveTranscript = voice.liveTranscript,
                        onStop = { voice.stopMic() },
                        onCancel = { voice.cancelMic() },
                    )
                } else {
                    // ── Text input (placeholder overlay + primary cursor + card focus border) ──
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
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("launcher_message")
                                // Hardware keyboard: with the "/" menu open, ↑/↓ move the highlight,
                                // Enter picks, Esc closes; otherwise Enter submits and Shift+Enter (or
                                // the soft keyboard's return) inserts a newline (DeX / attached kbd).
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) false
                                    else when {
                                        slashMenuOpen && e.key == Key.DirectionDown -> {
                                            slashSelectedIndex = (safeSlashIndex + 1).coerceAtMost(slashMatches.size - 1)
                                            true
                                        }
                                        slashMenuOpen && e.key == Key.DirectionUp -> {
                                            slashSelectedIndex = (safeSlashIndex - 1).coerceAtLeast(0)
                                            true
                                        }
                                        slashMenuOpen && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed -> {
                                            slashMatches.getOrNull(safeSlashIndex)?.let { selectSlashCommand(it) }
                                            true
                                        }
                                        slashMenuOpen && e.key == Key.Escape -> {
                                            slashDismissed = true
                                            true
                                        }
                                        (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed -> {
                                            doSubmit()
                                            true
                                        }
                                        else -> false
                                    }
                                },
                        )
                    }

                    // ── "/" command menu: matches for the active token (insert-only pre-spawn) ──
                    if (slashMenuOpen) {
                        Spacer(Modifier.height(8.dp))
                        SlashMenu(
                            matches = slashMatches,
                            selectedIndex = safeSlashIndex,
                            onSelect = { selectSlashCommand(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(cs.surfaceContainerHigh),
                            testTagPrefix = "launcher_slash_item_",
                            showActionGlyph = false,
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // ── Pickers row: [agent ▾]  [model ▾] ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        Box {
                            AgentPill(
                                agent = agent,
                                // Ignore taps until restore has landed — a fast tap could otherwise
                                // persist prefs built from the not-yet-restored launcherModels.
                                enabled = !launcherRestoring,
                                onClick = { agentMenu = true },
                            )
                            DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                                agents.forEach { a ->
                                    DropdownMenuItem(
                                        text = { Text(a.replaceFirstChar { it.uppercase() }) },
                                        leadingIcon = { AgentLogo(a, size = 20.dp) },
                                        modifier = Modifier.testTag("agent_$a"),
                                        onClick = {
                                            agent = a
                                            onLauncherPrefsChange(LauncherPrefs(agent = a, models = launcherModels, reasoningLevels = launcherReasoning))
                                            agentMenu = false
                                        },
                                    )
                                }
                            }
                        }

                        // Model pill → opens the picker; label is the model's displayName or "Default".
                        Box(Modifier.testTag("launcher_model_picker")) {
                            val modelLabel = model?.let { id -> models.firstOrNull { it.id == id }?.displayName ?: id }
                                ?: "Default"
                            ModelPill(
                                current = modelLabel,
                                onClick = { if (!launcherRestoring) showModelSheet = true },
                            )
                        }
                        // Thinking-level pill → only when the agent offers a real choice.
                        if (reasoningVisible) {
                            Box(Modifier.testTag("launcher_effort_picker")) {
                                EffortPill(
                                    current = reasoningLevel?.replaceFirstChar { it.uppercase() },
                                    onClick = { if (!launcherRestoring) showReasoningSheet = true },
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(10.dp))

                    // ── Actions row: [+ attach] [mic]  <spacer>  [● send] ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        var attachMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { attachMenu = true }, modifier = Modifier.testTag("launcher_attach")) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(cs.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_plus),
                                        contentDescription = "Attach",
                                        tint = cs.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                            DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Photos") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_image), null, Modifier.size(18.dp)) },
                                    modifier = Modifier.testTag("attach_menu_photos"),
                                    onClick = {
                                        attachMenu = false
                                        photoPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Files") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_file), null, Modifier.size(18.dp)) },
                                    modifier = Modifier.testTag("attach_menu_files"),
                                    onClick = { attachMenu = false; filePicker.launch("*/*") },
                                )
                                DropdownMenuItem(
                                    text = { Text("Camera") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(18.dp)) },
                                    modifier = Modifier.testTag("attach_menu_camera"),
                                    onClick = {
                                        attachMenu = false
                                        val uri = createImageUri(context)
                                        cameraUri = uri
                                        takePicture.launch(uri)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Record video") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_play), null, Modifier.size(18.dp)) },
                                    modifier = Modifier.testTag("attach_menu_record_video"),
                                    onClick = {
                                        attachMenu = false
                                        val uri = createVideoUri(context)
                                        videoUri = uri
                                        captureVideo.launch(uri)
                                    },
                                )
                            }
                        }

                        // Mic — dictate the first message (RecordingBar takes over while active).
                        MicButton(
                            onClick = { voice.onMicClick() },
                            enabled = !voice.transcribing && !voice.active,
                            modifier = Modifier.testTag("launcher_mic"),
                        )

                        Spacer(Modifier.weight(1f))

                        TextButton(
                            onClick = { doSaveDraft() },
                            enabled = canSaveDraft && !submitting,
                            modifier = Modifier.testTag("launcher_save_draft"),
                        ) {
                            Text("Save draft", fontSize = 12.sp)
                        }

                        // Circular send — spawns + sends. Spins while submitting; dims when empty.
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
                                    .background(
                                        if (canSend && !submitting) cs.primary
                                        else cs.primary.copy(alpha = 0.35f),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (submitting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = cs.onPrimary,
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_send),
                                        contentDescription = "Start session",
                                        tint = cs.onPrimary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (voice.transcribing) {
                    Spacer(Modifier.height(Space.sm))
                    TranscribingIndicator()
                }
                voice.banner?.let { msg ->
                    Spacer(Modifier.height(Space.xs))
                    Text(msg, color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
            }

            error?.let { Text(it, color = cs.error, fontSize = 12.sp) }

            // Folder caption — a calm restatement of the resolved workdir (iOS/web footer parity).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder_open),
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

    // ── Model picker sheet — reuses the chat composer's PickerSheet style. A leading "Default"
    //    row (sentinel id) maps back to a null model on pick. ──
    if (showModelSheet) {
        val opts = listOf(DEFAULT_MODEL_ID to "Default") + models.map { it.id to it.displayName }
        PickerSheet(
            title = "Select Model",
            options = opts,
            current = model ?: DEFAULT_MODEL_ID,
            onPick = { picked ->
                val newModel = if (picked == DEFAULT_MODEL_ID) null else picked
                model = newModel
                launcherModels = if (newModel != null) launcherModels + (agent to newModel) else launcherModels - agent
                onLauncherPrefsChange(LauncherPrefs(agent = agent, models = launcherModels, reasoningLevels = launcherReasoning))
            },
            onDismiss = { showModelSheet = false },
        )
    }

    // ── Thinking-level sheet — same PickerSheet style as the chat effort switcher. ──
    if (showReasoningSheet) {
        PickerSheet(
            title = "Thinking level",
            options = reasoningLevels.map { it.id to (it.description ?: it.id) },
            current = reasoningLevel,
            onPick = { picked ->
                reasoningLevel = picked
                launcherReasoning = launcherReasoning + (agent to picked)
                onLauncherPrefsChange(LauncherPrefs(agent = agent, models = launcherModels, reasoningLevels = launcherReasoning))
            },
            onDismiss = { showReasoningSheet = false },
        )
    }

    // ── Worktree sheet — toggle + searchable base-branch list (iOS WorktreeSheet). ──
    if (showWorktreeSheet) {
        WorktreeSheet(
            useWorktree = useWorktree,
            onToggle = { useWorktree = it },
            baseBranch = baseBranch,
            repoInfo = repoInfo,
            onPickBranch = { branch ->
                baseBranch = branch
                useWorktree = true
                showWorktreeSheet = false
            },
            onDismiss = { showWorktreeSheet = false },
        )
    }

    // ── Forge-aware project picker (known projects + typed path + clone/create) ──
    if (showProjectSheet) {
        ProjectPickerSheet(
            current = workdir,
            projects = projects,
            home = home,
            loadForges = loadForges,
            searchForge = searchForge,
            cloneForge = cloneForge,
            createLocalRepo = createLocalRepo,
            createForge = createForge,
            onPick = { workdir = it; workdirTouched = true; error = null },
            onDismiss = { showProjectSheet = false },
        )
    }

    if (voice.micDenied) MicDeniedDialog(onDismiss = { voice.micDenied = false })
}

/** Per-agent brand logo on a cream tile (SessionAvatar parity), so a dark mark like Cursor's
 *  stays legible in both themes. Unknown agents (e.g. opencode) fall back to an initial tile. */
@Composable
private fun AgentLogo(agent: String?, size: Dp) {
    val cs = MaterialTheme.colorScheme
    val res = when (agent?.lowercase()) {
        "claude" -> R.drawable.agent_claude
        "codex" -> R.drawable.agent_codex
        "cursor" -> R.drawable.agent_cursor
        "grok" -> R.drawable.agent_grok
        else -> null
    }
    val shape = RoundedCornerShape(size * 0.28f)
    if (res != null) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(Color(0xFFF7F4EE))
                .border(1.dp, cs.outline.copy(alpha = 0.7f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(res),
                contentDescription = null,
                modifier = Modifier.size(size * 0.62f),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(cs.primary),
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
}

/** Compact agent chip (logo + capitalized name + chevron) — the launcher's agent selector,
 *  mirroring the ModelPill styling so the two sit together as one toolbar. */
@Composable
private fun AgentPill(agent: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "agent_pill_scale",
    )
    Row(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outline, RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptic(HapticKind.Tick); onClick()
            }
            .padding(start = 5.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        AgentLogo(agent, size = 17.dp)
        Text(
            text = agent.replaceFirstChar { it.uppercase() },
            color = cs.onSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_down),
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Host picker pill (spec §5) — the launcher's "spawn on which host" selector. Shows the selected
 * host's color dot + name; a tap opens a menu of the paired fleet. Only rendered with 2+ hosts.
 * Picking a host calls [onSelect] (which retargets the active host, and thus every loader here).
 */
@Composable
private fun HostPickerPill(
    hosts: List<dev.supermux.android.host.HostView>,
    selectedHostId: String?,
    onSelect: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val selected = hosts.firstOrNull { it.recordId == selectedHostId } ?: hosts.firstOrNull() ?: return
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(cs.surfaceContainer)
                .border(1.dp, cs.outline, RoundedCornerShape(20.dp))
                .clickable { haptic(HapticKind.Tick); menu = true }
                .padding(horizontal = 11.dp, vertical = 5.dp)
                .testTag("launcher_host_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            dev.supermux.android.host.HostDot(selected.colorIndex, size = 9.dp)
            Text(
                selected.shortLabel,
                color = cs.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = "Select host",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            hosts.forEach { h ->
                DropdownMenuItem(
                    text = { Text(h.displayLabel + if (!h.online) " (offline)" else "") },
                    leadingIcon = { dev.supermux.android.host.HostDot(h.colorIndex, size = 10.dp) },
                    modifier = Modifier.testTag("launcher_host_${h.recordId}"),
                    onClick = { onSelect(h.recordId); menu = false },
                )
            }
        }
    }
}

/** Capsule pill for the worktree toggle — tinted (primary) when worktree is on. */
@Composable
private fun WorktreePill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val tint = if (active) cs.primary else cs.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainer)
            .clickable { haptic(HapticKind.Tick); onClick() }
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_git_branch),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Icon(
            painter = painterResource(R.drawable.ic_chevron_down),
            contentDescription = null,
            tint = tint.copy(alpha = 0.7f),
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * Worktree bottom sheet (iOS WorktreeSheet parity): an "isolated worktree" toggle plus a
 * searchable base-branch list (local + remote). Picking a branch enables the toggle and dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorktreeSheet(
    useWorktree: Boolean,
    onToggle: (Boolean) -> Unit,
    baseBranch: String,
    repoInfo: RepoInfo?,
    onPickBranch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var search by remember { mutableStateOf("") }
    val allBranches = remember(repoInfo) {
        (repoInfo?.branches?.local ?: emptyList()) + (repoInfo?.branches?.remote ?: emptyList())
    }
    val filtered = remember(allBranches, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) allBranches else allBranches.filter { it.lowercase().contains(q) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surfaceContainerLow,
        contentColor = cs.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Worktree",
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            // Toggle row
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
                Text(
                    text = "Base branch",
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search branches", color = cs.onSurfaceVariant) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .testTag("launcher_branch_search"),
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
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                    ) {
                        items(filtered, key = { it }) { branch ->
                            val selected = branch == baseBranch
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPickBranch(branch) }
                                    .background(
                                        if (selected) cs.primary.copy(alpha = 0.10f) else Color.Transparent
                                    )
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = branch,
                                    color = if (selected) cs.primary else cs.onSurface,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1,
                                )
                                if (selected) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        tint = cs.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
