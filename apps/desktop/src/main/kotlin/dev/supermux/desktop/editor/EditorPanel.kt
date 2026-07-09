// M3 editor: the desktop EditorPanel — a port of apps/android/.../editor/EditorScreen.kt trimmed to
// the DESKTOP TABLET arrangement (inline 192dp file-tree sidebar; no phone slide-over drawer).
//
// Desktop adaptations / deferrals vs. the Android source:
//   - Diff view + inline code-review and the markdown-preview toggle are OMITTED — TODO(M4). The
//     header therefore carries only { tree-toggle, search field, save } (Android also has the
//     preview + diff buttons). EditorState mirrors this (no showDiff/previewMode on desktop).
//   - LSP is OMITTED (M4). No AndroidLspBridge / lsp* wiring; the engine's lspOut is log-and-dropped.
//   - The WebView surface is a KCEF engine ([EditorSurface] in WebCodeEditor.kt) with a native
//     BasicTextField fallback. The engine is built ONLY once KCEF is Ready and is NEVER created
//     optimistically; it is disposed when the surface leaves the composition.
//   - Prefs (lineWrap/fontSize) arrive as a plain [prefs] value + [onFontSize] writeback (the caller,
//     SessionDetail, owns the EditorPrefsStore) — the panel stays disk-free so it's runComposeUiTest-
//     able. The persisted lineWrap/fontSize are pushed into cm6 at engine init (cmInit args).
//
// TESTABILITY: [kcefState] + [onEnsureInit] are injectable so the panel's UI tests drive the whole
// engine state machine (cover / downloading / native fallback) WITHOUT booting Chromium — the engine
// is only built when Ready, which tests never pass. SessionDetail additionally hides the whole panel
// behind an `editorPanelContent` seam so ITS tests never touch KCEF at all.
package dev.supermux.desktop.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import dev.supermux.desktop.theme.LocalPanes
import dev.supermux.desktop.theme.Space
import dev.supermux.net.FsEntry
import dev.supermux.net.FsSearchResult
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A chat-initiated request to open a workdir-relative [path] at an optional [line] (Android parity).
 *  The full chat-tap → pendingOpen wiring lands in T5; T4 accepts it as an optional param. */
data class PendingEditorOpen(val path: String, val line: Int?, val endLine: Int?)

/**
 * Code editor panel: inline lazy file tree, multi-tab editing, filename search, save + stale banner,
 * and the KCEF-backed editing surface with a native fallback.
 *
 * @param workdir the session's absolute workdir. RESERVED — currently unused (kept for signature
 *   parity with the Android EditorScreen port); M4 LSP will use it to build the root/file `file://`
 *   URIs, exactly as Android's EditorScreen does (dirUri/pathToUri).
 */
@Composable
fun EditorPanel(
    sessionId: String,
    @Suppress("unused") workdir: String,
    fsList: suspend (String) -> List<FsEntry>,
    fsRead: suspend (String) -> Result<String>,
    fsWrite: suspend (String, String) -> Boolean,
    fsSearch: suspend (String) -> List<FsSearchResult>,
    fsChanges: SharedFlow<ServerFrame.FsChanged> = MutableSharedFlow(),
    editorOpen: (String) -> Unit = {},
    editorClose: (String) -> Unit = {},
    pendingOpen: PendingEditorOpen? = null,
    onPendingOpenConsumed: () -> Unit = {},
    prefs: EditorPrefs = EditorPrefs(),
    onFontSize: (Int) -> Unit = {},
    kcefStateFlow: StateFlow<KcefState> = KcefRuntime.state,
    onEnsureInit: (CoroutineScope) -> Unit = { KcefRuntime.ensureInit(it) },
    // Test seam: inject a fake reader to drive/observe the tab-switch scroll capture; defaults to a
    // panel-owned reader that EditorSurface wires to the live engine's getScrollTop.
    scrollReader: EditorScrollReader? = null,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val c = LocalPanes.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // Own the editor state for the LIFETIME OF THE SESSION — keyed only on sessionId (NOT the fs*
    // lambdas, which capture the whole session and re-instance on every background pulse; keying on
    // them would wipe open tabs + unsaved edits on each pulse — Android EditorScreen:118-126).
    val editor = remember(sessionId) { EditorState(fsRead, fsWrite, scope) }

    // The scroll-capture seam: EditorSurface installs the live engine reader into this; the panel
    // reads it right before a tab switch/reveal (Android EditorScreen:406-408/:216 parity).
    val reader = scrollReader ?: remember { EditorScrollReader() }

    val kcefState by kcefStateFlow.collectAsState()

    // Desktop is always the tablet arrangement — the inline tree defaults visible.
    val treeVisible = editor.treeVisible ?: true
    val searchResults = remember { mutableStateListOf<FsSearchResult>() }

    LaunchedEffect(editor.searchQuery) {
        delay(200)
        val q = editor.searchQuery.trim()
        if (q.isEmpty()) {
            searchResults.clear()
            return@LaunchedEffect
        }
        searchResults.clear()
        searchResults.addAll(fsSearch(q))
    }

    // Editor lifecycle: start/stop the broker fs-watcher for this session. This is ALSO what makes
    // fs_changed fire — the stale banner is dead without it (Android EditorScreen:182-185).
    DisposableEffect(sessionId) {
        editorOpen(sessionId)
        onDispose { editorClose(sessionId) }
    }

    // Live file-watch: fold fs_changed pulses for THIS session into the stale set (EditorScreen:188-190).
    LaunchedEffect(sessionId, fsChanges) {
        fsChanges.collect { f -> if (f.session == sessionId) editor.markChanged(f.paths) }
    }

    fun revealFile(path: String, line: Int? = null, endLine: Int? = null) {
        focusManager.clearFocus()
        // Capture the outgoing tab's scroll before the switch (Android EditorScreen:216 parity).
        captureOutgoingScroll(editor, reader)
        editor.openFileAtLine(path, line, endLine)
        editor.searchQuery = ""
        searchResults.clear()
    }

    LaunchedEffect(pendingOpen) {
        pendingOpen?.let {
            revealFile(it.path, it.line, it.endLine)
            onPendingOpenConsumed()
        }
    }

    val activeTab = editor.activeTab
    val loadingNew = editor.loadingPath?.let { path -> editor.tabs.none { it.path == path } } == true

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ── Header: tree toggle · search · save (diff/preview OMITTED — TODO(M4)) ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(cs.surfaceContainerLow)
                    .padding(horizontal = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        searchResults.clear()
                        editor.treeVisible = !treeVisible
                    },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag("editor_tree_toggle"),
                ) {
                    Icon(
                        imageVector = if (treeVisible) Icons.Filled.KeyboardArrowDown else Icons.Filled.FolderOpen,
                        contentDescription = if (treeVisible) "Hide file tree" else "Show file tree",
                        tint = cs.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                EditorSearchField(
                    query = editor.searchQuery,
                    onQueryChange = { editor.searchQuery = it },
                    modifier = Modifier.weight(1f).padding(horizontal = Space.xs),
                )
                // TODO(M4): markdown-preview toggle + "View changes" diff button (Android has both).
                if (editor.saving) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.primary)
                    }
                } else {
                    val dirty = activeTab?.let { editor.isDirty(it.path) } == true
                    IconButton(
                        onClick = { editor.saveActive() },
                        enabled = dirty,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag("editor_save"),
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Save",
                            tint = if (dirty) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    if (treeVisible) {
                        Box(
                            Modifier
                                .width(192.dp)
                                .fillMaxHeight()
                                .background(cs.surfaceContainerHigh)
                                .testTag("editor_tree"),
                        ) {
                            FileTree(fsList = fsList, editor = editor, onOpenFile = { revealFile(it) })
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(cs.outlineVariant))
                    }

                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        EditorTabs(
                            tabs = editor.tabs,
                            activeTabPath = editor.activeTabPath,
                            loadingPath = if (loadingNew) editor.loadingPath else null,
                            isDirty = editor::isDirty,
                            onSelect = { path ->
                                // Capture the outgoing tab's scroll before switching (Android
                                // EditorScreen:406-408 parity, via the EditorScrollReader seam).
                                captureOutgoingScroll(editor, reader)
                                editor.selectTab(path)
                            },
                            onClose = editor::closeTab,
                        )
                        HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)

                        // Stale-on-disk banner for the active tab (Android EditorScreen:416-443).
                        if (activeTab != null && editor.isStale(activeTab.path)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(cs.errorContainer.copy(alpha = 0.5f))
                                    .padding(horizontal = Space.md, vertical = Space.xs)
                                    .testTag("editor_stale_banner"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = cs.error, modifier = Modifier.size(16.dp))
                                Text(
                                    "File changed on disk",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onErrorContainer,
                                    modifier = Modifier.weight(1f).padding(start = Space.sm),
                                )
                                FilledTonalButton(
                                    onClick = { scope.launch { editor.reload(activeTab.path, fsRead) } },
                                    modifier = Modifier.heightIn(min = 36.dp).pointerHoverIcon(PointerIcon.Hand).testTag("editor_reload"),
                                ) {
                                    Text("Reload", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }

                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // The engine surface stays composed (pre-warmed) for the session; keyed on
                            // sessionId so a session switch rebuilds the engine rather than binding the
                            // wrong session's browser into this slot (obligation: key the unkeyed
                            // engine remember).
                            key(sessionId) {
                                EditorSurface(
                                    kcefState = kcefState,
                                    content = activeTab?.content ?: "",
                                    filename = activeTab?.path ?: "",
                                    lineWrap = prefs.lineWrap,
                                    fontSize = prefs.fontSize,
                                    scrollTop = activeTab?.scrollTop ?: 0,
                                    revealLine = activeTab?.revealLine,
                                    onChange = { content -> activeTab?.path?.let { editor.updateContent(it, content) } },
                                    onSave = { editor.saveActive() },
                                    onRevealConsumed = { activeTab?.revealLine = null },
                                    onFontSize = onFontSize,
                                    onEnsureInit = onEnsureInit,
                                    scrollReader = reader,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            // Empty-state prompt over the (warm) surface until a file is opened.
                            if (editor.tabs.isEmpty() && editor.loadingPath == null && editor.loadError == null) {
                                Box(
                                    Modifier.fillMaxSize().background(Color(c.code).copy(alpha = 0.92f)).testTag("editor_empty"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Open a file from the tree or search", color = cs.onSurfaceVariant, fontSize = 13.sp)
                                }
                            }

                            editor.loadError?.takeIf { editor.tabs.isEmpty() }?.let { err ->
                                Box(
                                    Modifier.fillMaxSize().background(Color(c.code).copy(alpha = 0.92f)).padding(Space.xl).testTag("editor_load_error"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(err, color = cs.onSurfaceVariant, fontSize = 13.sp)
                                }
                            }

                            // Full-area spinner only while waiting for the very first file.
                            if (editor.tabs.isEmpty() && editor.loadingPath != null) {
                                Box(
                                    Modifier.fillMaxSize().background(Color(c.code).copy(alpha = 0.72f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = cs.primary)
                                        Text(
                                            editor.loadingPath!!.substringAfterLast('/'),
                                            color = cs.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = Space.sm),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (searchResults.isNotEmpty()) {
            EditorSearchOverlay(
                results = searchResults,
                onSelect = { revealFile(it) },
                onDismiss = {
                    focusManager.clearFocus()
                    editor.searchQuery = ""
                    searchResults.clear()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
