package dev.supermux.android.workspace

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.chat.ChatPanel
import dev.supermux.android.chat.SessionPanel
import dev.supermux.android.display.DisplayPanel
import dev.supermux.android.editor.EditorPanel
import dev.supermux.android.editor.PendingEditorOpen
import dev.supermux.android.session.SessionAvatar
import dev.supermux.android.terminal.TerminalPanel
import dev.supermux.android.theme.Space
import dev.supermux.net.ModelsResponse
import dev.supermux.net.ReasoningResponse
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
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
 * Panes are laid out in splits (all visible at once) so they are NOT wrapped in `keepAlivePanel`.
 */
@Composable
fun SessionWorkspaceDetail(
    session: SessionInfo,
    messages: List<LogEntry>,
    activity: List<ActivityEvent>,
    agent: AgentStatus?,
    sending: Boolean,
    layout: WorkspaceLayout,
    onSendWith: (text: String, attachments: List<String>) -> Unit,
    onInterrupt: () -> Unit,
    commands: List<SlashCommand>,
    commandsResolved: Boolean,
    onUpload: suspend (bytes: ByteArray, name: String, mime: String, kind: String?) -> String?,
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
    fsList: suspend (String) -> List<dev.supermux.net.FsEntry>,
    fsRead: suspend (String) -> Result<String>,
    fsWrite: suspend (String, String) -> Boolean,
    fsSearch: suspend (String) -> List<dev.supermux.net.FsSearchResult>,
    fsDiff: suspend () -> dev.supermux.net.FsDiffResult?,
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
    connectTerminal: (() -> dev.supermux.net.TerminalClient)? = null,
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

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(session.name) }

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
                TerminalPanel(connect = ct, modifier = Modifier.fillMaxSize())
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
    val chatOrNative: @Composable () -> Unit = {
        if (layout.nativeView(session.id) && session.agent == "claude") {
            val cat = connectAgentTerminal
            Box(Modifier.fillMaxSize().testTag("pane_native")) {
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
        } else {
            ChatPanel(
                session = session,
                messages = messages,
                activity = activity,
                agent = agent,
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
                onRequestKill = onKill,
                modifier = Modifier.fillMaxSize().testTag("pane_chat"),
            )
        }
    }

    // Editor and/or Terminal stacked vertically (the "work" column).
    val workColumn: @Composable () -> Unit = {
        val p = layout.panesFor(session.id)
        when {
            p.editor && p.terminal -> ResizableSplit(
                axis = SplitAxis.Vertical,
                fraction = layout.editorTermFraction,
                onFractionChange = layout::setEditorTermFraction,
                range = WorkspaceLayout.EDITORTERM_MIN..WorkspaceLayout.EDITORTERM_MAX,
                testTag = "divider_editor_terminal",
                first = editorPane,
                second = terminalPane,
            )
            p.editor -> editorPane()
            p.terminal -> terminalPane()
        }
    }
    // The work column and/or the display, side by side.
    val rightArea: @Composable () -> Unit = {
        val p = layout.panesFor(session.id)
        when {
            p.display && (p.editor || p.terminal) -> ResizableSplit(
                axis = SplitAxis.Horizontal,
                fraction = layout.workDisplayFraction,
                onFractionChange = layout::setWorkDisplayFraction,
                range = WorkspaceLayout.WORKDISP_MIN..WorkspaceLayout.WORKDISP_MAX,
                testTag = "divider_work_display",
                first = workColumn,
                second = displayPane,
            )
            p.display -> displayPane()
            else -> workColumn()
        }
    }

    Column(modifier.fillMaxSize()) {
        // Minimal header (full chrome comes in a later task).
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
            Text(
                text = session.name,
                style = MaterialTheme.typography.titleLarge,
                color = cs.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.sm))
            PaneToggleCluster(
                layout = layout,
                sessionId = session.id,
                agentIsClaude = session.agent == "claude",
            )
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
                p.chat && p.hasWork -> ResizableSplit(
                    axis = SplitAxis.Horizontal,
                    fraction = layout.chatFraction,
                    onFractionChange = layout::setChatFraction,
                    range = WorkspaceLayout.CHAT_MIN..WorkspaceLayout.CHAT_MAX,
                    testTag = "divider_chat_work",
                    first = chatOrNative,
                    second = rightArea,
                )
                p.chat -> chatOrNative()
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
}
