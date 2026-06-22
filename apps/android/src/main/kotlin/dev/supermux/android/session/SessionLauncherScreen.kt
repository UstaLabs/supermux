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
import dev.supermux.android.chat.ModelPill
import dev.supermux.android.chat.PickerSheet
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.net.ModelInfo
import dev.supermux.net.RepoInfo
import dev.supermux.proto.SessionInfo
import dev.supermux.session.formatWorkdir
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
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val agents = listOf("claude", "codex", "cursor", "opencode")

    // Model picker (mirrors iOS: refetch on agent change, reset selection to Default/null).
    var models by remember { mutableStateOf(emptyList<ModelInfo>()) }
    var showModelSheet by remember { mutableStateOf(false) }
    LaunchedEffect(agent) {
        model = null
        models = loadModels(agent)
    }

    // Worktree picker (iOS: useWorktree defaults on; gated on repoInfo.eligible).
    var repoInfo by remember { mutableStateOf<RepoInfo?>(null) }
    var useWorktree by remember { mutableStateOf(true) }
    var baseBranch by remember { mutableStateOf("") }
    var showWorktreeSheet by remember { mutableStateOf(false) }
    LaunchedEffect(workdir) {
        val info = if (workdir.isBlank()) null else loadRepoInfo(workdir)
        repoInfo = info
        baseBranch = info?.currentBranch ?: ""
    }

    LaunchedEffect(Unit) { projects = loadProjects() }

    // Default workdir from most recent session, like web chooseDefaultProject.
    LaunchedEffect(sessions) {
        if (!workdirTouched && sessions.isNotEmpty()) {
            workdir = sessions.first().workdir.ifBlank { "~" }
        }
    }

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
                ProjectPathPicker(
                    value = workdir,
                    onValueChange = { workdir = it; workdirTouched = true; error = null },
                    projects = projects,
                    home = home,
                    fieldColors = fieldColors,
                )
            }

            OutlinedTextField(
                value = message,
                onValueChange = { message = it; error = null },
                placeholder = { Text("What should the agent do?", color = cs.onSurfaceVariant) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                colors = fieldColors,
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                agents.forEachIndexed { i, a ->
                    SegmentedButton(
                        selected = agent == a,
                        onClick = { agent = a },
                        shape = SegmentedButtonDefaults.itemShape(i, agents.size),
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
                        onClick = { showModelSheet = true },
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
            onPick = { picked -> model = if (picked == DEFAULT_MODEL_ID) null else picked },
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
