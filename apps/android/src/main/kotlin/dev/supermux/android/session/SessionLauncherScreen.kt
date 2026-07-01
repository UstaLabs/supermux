package dev.supermux.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.chat.MicButton
import dev.supermux.android.chat.MicDeniedDialog
import dev.supermux.android.chat.ModelPill
import dev.supermux.android.chat.PickerSheet
import dev.supermux.android.chat.RecordingBar
import dev.supermux.android.chat.TranscribingIndicator
import dev.supermux.android.chat.rememberDictation
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ModelInfo
import dev.supermux.net.RemoteRepo
import dev.supermux.net.RepoInfo
import dev.supermux.proto.SessionInfo
import dev.supermux.session.formatWorkdir
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // Git status for the chosen project; gates the worktree picker on RepoInfo.eligible.
    loadRepoInfo: suspend (workdir: String) -> RepoInfo? = { null },
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
    onSubmit: suspend (workdir: String, agent: String, model: String?, message: String, worktree: Boolean, baseBranch: String?) -> String,
    onOpenSession: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var workdir by remember { mutableStateOf("~") }
    var workdirTouched by remember { mutableStateOf(false) }
    var agent by remember { mutableStateOf("claude") }
    var model by remember { mutableStateOf<String?>(null) }     // null == "Default"
    var message by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf(emptyList<String>()) }
    var showProjectSheet by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    // Launcher state persistence — see this task's header note for why launcherRestoring gates
    // the agent/workdir effects below, and why lastSeenAgent/lastSeenWorkdir (not one-shot
    // "armed" booleans) are the safe way to distinguish restore-settling from a genuine later
    // change. launcherModels mirrors LauncherPrefs.models (the per-agent memory) so a pick can
    // reconstruct the whole prefs blob to persist.
    var launcherRestoring by remember { mutableStateOf(true) }
    var lastSeenAgent by remember { mutableStateOf<String?>(null) }
    var lastSeenWorkdir by remember { mutableStateOf<String?>(null) }
    var launcherModels by remember { mutableStateOf(emptyMap<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val agents = listOf("claude", "codex", "cursor", "opencode")

    // Model picker (mirrors iOS: refetch on agent change, reset selection to Default/null).
    var models by remember { mutableStateOf(emptyList<ModelInfo>()) }
    var showModelSheet by remember { mutableStateOf(false) }
    LaunchedEffect(agent, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        models = loadModels(agent)
        // Reset only when the live agent genuinely differs from what this effect last recorded
        // — safe against any number of duplicate invocations for the same agent, unlike a
        // one-shot "armed" boolean (see this task's header note for why that broke on iOS).
        // lastSeenAgent == null means "never recorded yet" and must never count as a difference.
        if (lastSeenAgent != null && lastSeenAgent != agent) {
            model = null
        }
        lastSeenAgent = agent
    }

    // Worktree picker (iOS: useWorktree defaults on; gated on repoInfo.eligible).
    var repoInfo by remember { mutableStateOf<RepoInfo?>(null) }
    var useWorktree by remember { mutableStateOf(true) }
    var baseBranch by remember { mutableStateOf("") }
    var showWorktreeSheet by remember { mutableStateOf(false) }
    LaunchedEffect(workdir, launcherRestoring) {
        if (launcherRestoring) { repoInfo = null; return@LaunchedEffect }
        val info = if (workdir.isBlank()) null else loadRepoInfo(workdir)
        repoInfo = info
        if (lastSeenWorkdir != null && lastSeenWorkdir != workdir) {
            baseBranch = info?.currentBranch ?: ""
        } else if (baseBranch.isBlank()) {
            baseBranch = info?.currentBranch ?: ""
        }
        lastSeenWorkdir = workdir
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
        // was written can't leave `agent` holding a value the SegmentedButtonRow has no
        // matching button for.
        agent = if (agents.contains(prefs.agent)) prefs.agent else "claude"
        launcherModels = prefs.models
        model = prefs.models[agent]
        val draft = loadLauncherDraft()
        if (draft.workdir != null) {
            workdir = draft.workdir
            workdirTouched = true
        }
        useWorktree = draft.useWorktree
        baseBranch = draft.baseBranch
        message = draft.text
        launcherRestoring = false
    }

    // Persist the in-progress draft, debounced (~400ms) — mirrors ChatScreen.kt's per-session
    // draft save. launcherRestoring gates it so the restore's own assignments (above) don't
    // immediately re-save right back over themselves before they've even settled.
    LaunchedEffect(workdir, workdirTouched, useWorktree, baseBranch, message, launcherRestoring) {
        if (launcherRestoring) return@LaunchedEffect
        delay(400)
        onLauncherDraftChange(
            LauncherDraft(
                workdir = if (workdirTouched) workdir else null,
                useWorktree = useWorktree,
                baseBranch = baseBranch,
                text = message,
            )
        )
    }

    LaunchedEffect(Unit) { projects = loadProjects() }

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
        onAppend = { message = if (message.isBlank()) it else message.trimEnd() + " " + it; error = null },
    )

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = cs.onSurface,
        unfocusedTextColor = cs.onSurface,
        focusedBorderColor = cs.primary,
        unfocusedBorderColor = cs.outline,
        focusedLabelColor = cs.primary,
        unfocusedLabelColor = cs.onSurfaceVariant,
    )

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
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Let's build", color = cs.onSurface, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Space.sm))
                Text(
                    formatWorkdir(workdir, home),
                    color = cs.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("Project", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                ProjectField(
                    workdir = workdir,
                    home = home,
                    onClick = { showProjectSheet = true },
                )
            }

            // Message field — while dictating, the RecordingBar takes over (parity with chat).
            if (voice.active) {
                RecordingBar(
                    seconds = voice.recordingSeconds,
                    liveTranscript = voice.liveTranscript,
                    onStop = { voice.stopMic() },
                    onCancel = { voice.cancelMic() },
                )
            } else {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it; error = null },
                    placeholder = { Text("What should the agent do?", color = cs.onSurfaceVariant) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    colors = fieldColors,
                )
            }
            if (voice.transcribing) TranscribingIndicator()
            voice.banner?.let { msg ->
                Text(msg, color = cs.onSurfaceVariant, fontSize = 12.sp)
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                agents.forEachIndexed { i, a ->
                    SegmentedButton(
                        selected = agent == a,
                        onClick = {
                            agent = a
                            onLauncherPrefsChange(LauncherPrefs(agent = a, models = launcherModels))
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, agents.size),
                        // Ignore taps until the restore has landed — otherwise a fast tap could
                        // persist prefs built from the not-yet-restored launcherModels (still
                        // emptyMap()), wiping every previously-remembered agent→model pick.
                        enabled = !launcherRestoring,
                        modifier = Modifier.testTag("agent_$a"),
                    ) {
                        Text(a)
                    }
                }
            }

            // ── Model + worktree pills (iOS: model Menu + worktreePill gated on eligible) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                // Model pill → opens the picker; label is the model's displayName or "Default"
                // (iOS modelLabel: nil model → "Default", else the model's displayName).
                Box(Modifier.testTag("launcher_model_picker")) {
                    val modelLabel = model?.let { id -> models.firstOrNull { it.id == id }?.displayName ?: id }
                        ?: "Default"
                    ModelPill(
                        current = modelLabel,
                        // Same restore-window guard as the agent SegmentedButton above —
                        // ModelPill has no `enabled` param to hook into, so gate the callback
                        // itself: the sheet must not even open while state is still restoring.
                        onClick = { if (!launcherRestoring) showModelSheet = true },
                    )
                }

                // Worktree pill — only when the workdir is an eligible git repo.
                if (repoInfo?.eligible == true) {
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
                Spacer(Modifier.weight(1f))
                // Mic — dictate the first message (RecordingBar takes over the field while active).
                MicButton(
                    onClick = { voice.onMicClick() },
                    enabled = !voice.transcribing && !voice.active,
                    modifier = Modifier.testTag("launcher_mic"),
                )
            }

            error?.let { Text(it, color = cs.error, fontSize = 12.sp) }

            Button(
                onClick = {
                    val text = message.trim()
                    if (text.isEmpty()) {
                        error = "Enter a message"
                        return@Button
                    }
                    submitting = true
                    error = null
                    // Only honor worktree/baseBranch when the repo is eligible (iOS parity);
                    // baseBranch is null unless eligible + toggle on + a branch is chosen.
                    val eligible = repoInfo?.eligible == true
                    val wantsWorktree = eligible && useWorktree
                    val base = if (wantsWorktree && baseBranch.isNotEmpty()) baseBranch else null
                    scope.launch {
                        try {
                            val sessionId = onSubmit(
                                workdir.trim(),
                                agent,
                                model,
                                text,
                                wantsWorktree,
                                base,
                            )
                            onLauncherDraftChange(LauncherDraft())
                            onOpenSession(sessionId)
                        } catch (e: Exception) {
                            error = e.message ?: "Failed to create session"
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !submitting && workdir.isNotBlank(),
                modifier = Modifier
                    .testTag("launcher_submit")
                    .fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary,
                ),
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = cs.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (submitting) "Creating…" else "Start session")
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
                onLauncherPrefsChange(LauncherPrefs(agent = agent, models = launcherModels))
            },
            onDismiss = { showModelSheet = false },
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
