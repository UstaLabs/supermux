package dev.supermux.android.workspace

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.supermux.android.R
import dev.supermux.android.chat.ChatDetailPrefs
import dev.supermux.android.chat.ChatPanel
import dev.supermux.util.proxyDisplayUrl
import dev.supermux.util.proxyUrl
import dev.supermux.android.chat.FinishButton
import dev.supermux.net.ChunkSource
import dev.supermux.android.chat.FinishSheet
import dev.supermux.android.chat.SessionPanel
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.android.display.DisplayPanel
import dev.supermux.android.editor.EditorPanel
import dev.supermux.android.editor.PendingEditorOpen
import dev.supermux.android.session.SessionAvatar
import dev.supermux.android.session.SessionStatusRail
import dev.supermux.android.terminal.TerminalPanel
import dev.supermux.android.terminal.ScratchTerminalPanel
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.android.ui.keepAlivePanel
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ReasoningResponse
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.gitBadge
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.session.inferHomeDir
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.toWorkdirRelativePath

/**
 * Wide-screen (tablet / unfolded-foldable) detail for ONE session: a minimal header + a nested,
 * drag-resizable split tree of live panes driven by [layout].panesFor([session].id). Mirrors the
 * iOS multi-pane workspace. All the data + callbacks are the same ones [dev.supermux.android.chat.ChatScreen]
 * receives (threaded from `SessionChatLayer`), plus the shared [WorkspaceLayout].
 *
 * Split structure (invariant guarantees at least one pane is visible):
 * ```
 *   chat + work → [ Chat|Native | RightArea ]        (horizontal, chatFraction)
 *   RightArea:  display + work → [ WorkColumn | Display ]   (horizontal, workDisplayFraction)
 *   WorkColumn: editor + terminal → [ Editor / Terminal ]   (vertical, editorTermFraction)
 * ```
 * The split panes (editor/terminal/display) are all visible at once, so they are NOT wrapped in
 * `keepAlivePanel`. The Chat⇄Native pair is the exception: Chat stays kept-alive under the Native
 * overlay so its staged attachments + unsaved draft survive the flip.
 */
@Composable
fun SessionWorkspaceDetail(
    session: SessionInfo,
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    agent: AgentStatus?,
    bgTasks: List<ServerFrame.BgTask> = emptyList(),
    sending: Boolean,
    layout: WorkspaceLayout,
    onSendWith: (text: String, attachments: List<String>) -> Unit,
    onInterrupt: () -> Unit,
    commands: List<SlashCommand>,
    commandsResolved: Boolean,
    onUpload: suspend (source: ChunkSource, name: String, mime: String, kind: String?, onProgress: (Long, Long) -> Unit) -> String?,
    loadBytes: suspend (String) -> ByteArray?,
    transcribeAudio: suspend (bytes: ByteArray, filename: String) -> String?,
    transcribeDraft: suspend (draft: String) -> String?,
    loadGlossary: suspend () -> List<String>,
    vmModels: suspend (String) -> ModelsResponse?,
    vmReasoning: suspend (String) -> ReasoningResponse?,
    onPickModel: (String) -> Unit,
    onPickEffort: (String) -> Unit,
    loadDraft: suspend (String) -> String,
    saveDraft: (String, String) -> Unit,
    consumePendingFirst: (String) -> dev.supermux.android.AppViewModel.PendingFirstMessage?,
    onRename: (String) -> Unit,
    onMute: (Boolean) -> Unit,
    onKill: () -> Unit,
    // Management-screen navigation (Settings/Usage/…) via the shared route strings MainActivity's
    // navTo handles — surfaced through the header overflow (iOS parity; sidebar-only otherwise).
    onNavigate: (String) -> Unit,
    // Workspace ⋮ git ops (op = fetch|push|pull|publish); the caller runs it + surfaces the result.
    onGitOp: (String) -> Unit = {},
    // Exposed proxy links for this session (iOS sessionLinksMenu parity).
    sessionLinks: List<dev.supermux.net.ProxyDto> = emptyList(),
    // Finish flow — threaded from SessionChatLayer exactly like ChatScreen (same VM-backed lambdas).
    finishJob: dev.supermux.proto.FinishJobDto? = null,
    onFinishReadiness: suspend () -> dev.supermux.net.FinishReadiness? = { null },
    onFinish: (action: String, skipVerify: Boolean?, commitFirst: Boolean?, commitMessage: String?, onKickoff: (Boolean) -> Unit) -> Unit = { _, _, _, _, cb -> cb(false) },
    onClearFinishJob: () -> Unit = {},
    onVerifySuggest: suspend () -> dev.supermux.net.VerifySuggestResult? = { null },
    onVerifySave: suspend (String) -> dev.supermux.net.VerifySaveResult? = { null },
    onSendToAgent: (String) -> Unit = {},
    fsList: suspend (String) -> List<dev.supermux.net.FsEntry>,
    fsRead: suspend (String) -> Result<String>,
    fsWrite: suspend (String, String) -> Boolean,
    fsSearch: suspend (String) -> List<dev.supermux.net.FsSearchResult>,
    fsDiff: suspend (String) -> dev.supermux.net.FsDiffResult?,
    fsRefs: suspend () -> dev.supermux.net.FsRefsResult?,
    reviewAddComment: suspend (dev.supermux.net.AddCommentBody) -> dev.supermux.net.ReviewComment?,
    reviewResolve: suspend (String) -> Boolean,
    reviewSubmit: suspend () -> dev.supermux.net.ReviewSubmitResult?,
    fsChanges: kotlinx.coroutines.flow.SharedFlow<dev.supermux.proto.ServerFrame.FsChanged>,
    lspStatus: kotlinx.coroutines.flow.StateFlow<Map<String, dev.supermux.proto.ServerFrame.LspStatus>>,
    lspRpc: kotlinx.coroutines.flow.SharedFlow<dev.supermux.proto.ServerFrame.LspRpcIn>,
    editorOpen: (String) -> Unit,
    editorClose: (String) -> Unit,
    lspStatusQuery: (String, String) -> Unit,
    lspOpen: (String, String) -> Unit,
    lspRpcOut: (String, String, String) -> Unit,
    lspClose: (String, String) -> Unit,
    onEditorConsumesBackChange: (Boolean) -> Unit,
    connectTerminal: ((String) -> dev.supermux.net.TerminalClient)? = null,
    listTerminals: suspend () -> List<dev.supermux.net.TerminalSummary> = { emptyList() },
    closeTerminal: suspend (String) -> Unit = {},
    connectAgentTerminal: (() -> dev.supermux.net.TerminalClient)? = null,
    listDisplays: (suspend () -> List<dev.supermux.net.DisplayStream>)? = null,
    connectScrcpy: ((String) -> dev.supermux.net.ScrcpyClient)? = null,
    connectVnc: ((String) -> dev.supermux.net.VncClient)? = null,
    displays: kotlinx.coroutines.flow.StateFlow<List<dev.supermux.net.DisplayStream>>,
    onStartDisplay: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(session.name) }
    var showKillDialog by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    // Deep-linking a file into the editor pane (from a transcript ref). Opens the editor pane and
    // hands EditorPanel a pending target — mirrors ChatScreen's onOpenFile, but flips the pane bit
    // through [layout] instead of switching a tab.
    var pendingEditorOpen by remember(session.id) { mutableStateOf<PendingEditorOpen?>(null) }
    val onOpenFile: (FilePathRef) -> Unit = remember(session.id) {
        { ref ->
            val rel = toWorkdirRelativePath(ref.path, session.workdir, inferHomeDir(session.workdir))
            if (rel == null) {
                Toast.makeText(context, "File is outside this session's project", Toast.LENGTH_SHORT).show()
            } else {
                pendingEditorOpen = PendingEditorOpen(rel, ref.line, ref.endLine)
                layout.setPanes(session.id, layout.panesFor(session.id).copy(editor = true))
            }
        }
    }

    // Auto-open the Display pane the moment a stream for this session goes live. Mirrors
    // DisplayPanel's resolution (newest "running" stream for session.name). We track the previously
    // seen stream id and fire only on the nil→non-nil edge, so a display the user manually closed
    // is NOT re-opened by the same still-running stream (its id is unchanged → effect won't re-run).
    val liveDisplays by displays.collectAsStateWithLifecycle()
    val runningDisplayId = remember(liveDisplays, session.name) {
        liveDisplays.filter { it.sessionName == session.name && it.status == "running" }
            .maxByOrNull { it.createdAt ?: "" }
            ?.id
    }
    // Seed with the current id so an already-live display at open isn't force-opened — only a stream
    // that *becomes* live while the session is open trips the pane.
    var lastRunningDisplayId by remember(session.id) { mutableStateOf(runningDisplayId) }
    LaunchedEffect(runningDisplayId) {
        val prev = lastRunningDisplayId
        lastRunningDisplayId = runningDisplayId
        if (prev == null && runningDisplayId != null) {
            layout.setPanes(session.id, layout.panesFor(session.id).copy(display = true))
        }
    }

    // ── individual panes (each fills its split slot); mirror ChatScreen's arg values ──
    val editorPane: @Composable () -> Unit = {
        EditorPanel(
            sessionId = session.id,
            workdir = session.workdir,
            fsList = fsList,
            fsRead = fsRead,
            fsWrite = fsWrite,
            fsSearch = fsSearch,
            fsDiff = fsDiff,
            fsRefs = fsRefs,
            reviewAddComment = reviewAddComment,
            reviewResolve = reviewResolve,
            reviewSubmit = reviewSubmit,
            fsChanges = fsChanges,
            lspStatus = lspStatus,
            lspRpc = lspRpc,
            editorOpen = editorOpen,
            editorClose = editorClose,
            lspStatusQuery = lspStatusQuery,
            lspOpen = lspOpen,
            lspRpcOut = lspRpcOut,
            lspClose = lspClose,
            onConsumesBackChange = onEditorConsumesBackChange,
            pendingOpen = pendingEditorOpen,
            onPendingOpenConsumed = { pendingEditorOpen = null },
            modifier = Modifier.fillMaxSize().testTag("pane_editor"),
        )
    }
    val terminalPane: @Composable () -> Unit = {
        val ct = connectTerminal
        Box(Modifier.fillMaxSize().testTag("pane_terminal")) {
            if (ct != null) {
                ScratchTerminalPanel(
                    sessionId = session.id,
                    connect = ct,
                    listTerminals = listTerminals,
                    closeTerminal = closeTerminal,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Terminal unavailable", color = cs.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
    val displayPane: @Composable () -> Unit = {
        val ld = listDisplays
        val cScrcpy = connectScrcpy
        val cVnc = connectVnc
        Box(Modifier.fillMaxSize().testTag("pane_display")) {
            if (ld != null && cScrcpy != null && cVnc != null) {
                DisplayPanel(
                    sessionName = session.name,
                    displays = displays,
                    listDisplays = ld,
                    connectScrcpy = cScrcpy,
                    connectVnc = cVnc,
                    onStartDisplay = onStartDisplay,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Display unavailable", color = cs.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
    // Chat column, or the raw agent-PTY ("Native") when toggled on for a claude session (iOS parity).
    // Native is an OVERLAY, not a replacement: ChatPanel stays composed underneath via keepAlivePanel
    // so its staged attachments + unsaved draft survive the Chat⇄Native flip (web-style v-show). Chat
    // is the default state, so ChatPanel mounts on first composition; the agent PTY is only mounted
    // once the user actually flips to Native.
    val chatOrNative: @Composable () -> Unit = {
        val native = layout.nativeView(session.id) && session.agent == "claude"
        Box(Modifier.fillMaxSize()) {
            ChatPanel(
                session = session,
                messages = messages,
                activity = activity,
                agent = agent,
                bgTasks = bgTasks,
                sending = sending,
                activePanel = SessionPanel.Chat,
                onSendWith = onSendWith,
                onInterrupt = onInterrupt,
                commands = commands,
                commandsResolved = commandsResolved,
                onUpload = onUpload,
                loadBytes = loadBytes,
                transcribeAudio = transcribeAudio,
                transcribeDraft = transcribeDraft,
                loadGlossary = loadGlossary,
                vmModels = vmModels,
                vmReasoning = vmReasoning,
                onPickModel = onPickModel,
                onPickEffort = onPickEffort,
                loadDraft = loadDraft,
                saveDraft = saveDraft,
                consumePendingFirst = consumePendingFirst,
                onOpenFile = onOpenFile,
                onRequestRename = {
                    renameText = session.name
                    showRenameDialog = true
                },
                onRequestMute = { onMute(!(session.mute ?: false)) },
                onRequestKill = { showKillDialog = true },
                modifier = Modifier.keepAlivePanel(visible = !native).testTag("pane_chat"),
            )
            if (native) {
                val cat = connectAgentTerminal
                Box(Modifier.keepAlivePanel(visible = true).testTag("pane_native")) {
                    if (cat != null) {
                        TerminalPanel(
                            connect = cat,
                            modifier = Modifier.fillMaxSize(),
                            // Agent PTY exited → drop back to the chat column (iOS onExit parity).
                            onExit = { layout.setNativeView(session.id, false) },
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Native terminal unavailable", color = cs.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // Editor and/or Terminal stacked vertically (the "work" column).
    val workColumn: @Composable () -> Unit = {
        val p = layout.panesFor(session.id)
        when {
            // Editor stays in the same split slot so it doesn't remount/flash when the terminal toggles.
            p.editor -> ResizableSplit(
                axis = SplitAxis.Vertical,
                fraction = layout.editorTermFraction,
                onFractionChange = layout::setEditorTermFraction,
                range = WorkspaceLayout.EDITORTERM_MIN..WorkspaceLayout.EDITORTERM_MAX,
                testTag = "divider_editor_terminal",
                first = editorPane,
                second = if (p.terminal) terminalPane else null,
            )
            p.terminal -> terminalPane()
        }
    }
    // The work column and/or the display, side by side.
    val rightArea: @Composable () -> Unit = {
        val p = layout.panesFor(session.id)
        when {
            // Work column stays in the same split slot so it doesn't remount/flash when Display toggles.
            p.editor || p.terminal -> ResizableSplit(
                axis = SplitAxis.Horizontal,
                fraction = layout.workDisplayFraction,
                onFractionChange = layout::setWorkDisplayFraction,
                range = WorkspaceLayout.WORKDISP_MIN..WorkspaceLayout.WORKDISP_MAX,
                testTag = "divider_work_display",
                first = workColumn,
                second = if (p.display) displayPane else null,
            )
            p.display -> displayPane()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(cs.surfaceContainerLow)
            .statusBarsPadding(),
    ) {
        // Header: identity + status, Chat/Native + Finish, and the pane toggles.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(cs.surfaceContainerLow)
                .padding(horizontal = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionAvatar(
                name = session.name,
                agent = session.agent,
                modifier = Modifier.size(30.dp),
                sessionId = session.id,
            )
            Spacer(Modifier.width(Space.sm))
            // git/sync status + working spinner (mirrors ChatScreen; git comes off SessionInfo).
            SessionStatusRail(git = session.git, working = agent?.working == true)
            Spacer(Modifier.width(Space.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Git counts live in the session view (list is icon-only): compareRef + ahead/behind/dirty.
                gitBadge(session.git)?.let { badge ->
                    val label = if (badge.kind == GitBadgeKind.BASE && badge.compareRef.isNotEmpty())
                        "${badge.compareRef} ${badge.text}" else badge.text
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(Space.sm))

            // Exposed proxy links (iOS sessionLinksMenu parity): a link icon dropping a menu of the
            // session's public URLs, each opening in the browser.
            if (sessionLinks.isNotEmpty()) {
                var showLinks by remember { mutableStateOf(false) }
                val uriHandler = LocalUriHandler.current
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
                                        tint = cs.onSurface,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = { showLinks = false; uriHandler.openUri(proxyUrl(p)) },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(Space.xs))
            }

            // Chat ⇄ Native (raw agent PTY) toggle — claude only, and only while the Chat pane is
            // visible (it flips ChatPanel ⇄ agent-PTY inside that pane; see chatOrNative above).
            if (session.agent == "claude" && layout.panesFor(session.id).chat) {
                AgentViewToggle(
                    nativeView = layout.nativeView(session.id),
                    onSetNative = { layout.setNativeView(session.id, it) },
                    modifier = Modifier.testTag("toggle_native"),
                )
                Spacer(Modifier.width(Space.xs))
            }

            // Finish — worktree-backed sessions only (same gate/badge as ChatScreen's header).
            if (session.session_branch != null) {
                var showFinishSheet by remember(session.id) { mutableStateOf(false) }
                // Acked startedAt survives rotation/process-death so a result stays "seen".
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

            Spacer(Modifier.width(Space.xs))
            PaneToggleCluster(
                layout = layout,
                sessionId = session.id,
            )

            // Overflow (⋮): Detail + management screens (Settings/Usage/…). These otherwise live
            // only on the sidebar; surfacing them here matches the iOS workspace header. Each item
            // routes via the same string dests MainActivity's navTo(when(dest)) handles.
            Box {
                val overflowContext = LocalContext.current
                ChatDetailPrefs.ensureLoaded(overflowContext)
                val chatDetailLevel by ChatDetailPrefs.level.collectAsState()
                var detailSubmenu by remember { mutableStateOf(false) }
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
                    // Git ops — parity with iOS's overflow "Git" section; shown when the session
                    // is a repo. Publish replaces Push when the branch has no upstream yet.
                    if (session.git != null) {
                        DropdownMenuItem(
                            text = { Text("Fetch") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp)) },
                            onClick = { showOverflow = false; onGitOp("fetch") },
                        )
                        DropdownMenuItem(
                            text = { Text("Pull") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_git_pull_request), contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp)) },
                            onClick = { showOverflow = false; onGitOp("pull") },
                        )
                        if (session.git?.unpublished == true) {
                            DropdownMenuItem(
                                text = { Text("Publish") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_cloud_off), contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp)) },
                                onClick = { showOverflow = false; onGitOp("publish") },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Push") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_git_merge), contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp)) },
                                onClick = { showOverflow = false; onGitOp("push") },
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = null,
                                tint = cs.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { showOverflow = false; onNavigate("settings") },
                    )
                    DropdownMenuItem(
                        text = { Text("Usage") },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_bar_chart),
                                contentDescription = null,
                                tint = cs.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { showOverflow = false; onNavigate("usage") },
                    )
                    DropdownMenuItem(
                        text = { Text("Devices") },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_smartphone),
                                contentDescription = null,
                                tint = cs.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { showOverflow = false; onNavigate("devices") },
                    )
                    DropdownMenuItem(
                        text = { Text("Proxies") },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_network),
                                contentDescription = null,
                                tint = cs.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { showOverflow = false; onNavigate("proxies") },
                    )
                    DropdownMenuItem(
                        text = { Text("Appearance") },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_sun),
                                contentDescription = null,
                                tint = cs.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { showOverflow = false; onNavigate("appearance") },
                    )
                    DropdownMenuItem(
                        text = { Text("Archived") },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_archive),
                                contentDescription = null,
                                tint = cs.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { showOverflow = false; onNavigate("archived") },
                    )
                }
                DropdownMenu(
                    expanded = detailSubmenu,
                    onDismissRequest = { detailSubmenu = false },
                ) {
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
                            enabled = true,
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
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(cs.outlineVariant),
        )

        // Content: the nested split tree, driven by layout.panesFor(session.id).
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val p = layout.panesFor(session.id)
            when {
                // Chat always renders through the SAME split, so it never remounts (and the whole
                // page never blinks) when a work pane toggles — the work area is just the split's
                // second slot, present only when there's work to show.
                p.chat -> ResizableSplit(
                    axis = SplitAxis.Horizontal,
                    fraction = layout.chatFraction,
                    onFractionChange = layout::setChatFraction,
                    range = WorkspaceLayout.CHAT_MIN..WorkspaceLayout.CHAT_MAX,
                    testTag = "divider_chat_work",
                    first = chatOrNative,
                    second = if (p.hasWork) rightArea else null,
                )
                else -> rightArea() // invariant guarantees a non-empty pane set
            }
        }
    }

    // ── rename dialog (hosted locally; copies ChatScreen's pattern) ───────────
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(renameText)
                        showRenameDialog = false
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }

    // ── kill confirm dialog (copies ChatScreen's) ────────────────────────────
    if (showKillDialog) {
        AlertDialog(
            onDismissRequest = { showKillDialog = false },
            title = { Text("Kill session?") },
            text = { Text("This will terminate \"${session.name}\" immediately.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic(HapticKind.Heavy)
                        showKillDialog = false
                        onKill()
                    },
                ) { Text("Kill") }
            },
            dismissButton = {
                TextButton(onClick = { showKillDialog = false }) { Text("Cancel") }
            },
        )
    }
}
