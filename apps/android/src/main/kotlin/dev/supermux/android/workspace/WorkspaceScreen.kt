package dev.supermux.android.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import dev.supermux.android.AppViewModel
import dev.supermux.net.AddViewBody
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.panes.DefaultTabChip
import dev.supermux.ui.panes.PaneHost
import dev.supermux.ui.workspace.WorkspaceSession
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.NewViewKind
import dev.supermux.workspace.openSingletonView
import dev.supermux.workspace.setActiveViewInGroup
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.workspace.viewTitle
import kotlinx.coroutines.launch

/**
 * Broker-driven workspace body. Phone flattens every view to a tab row and never PATCHes
 * [layout] (D2/D3). Tablet renders the tree through [PaneHost] (D5).
 */
@Composable
fun WorkspaceScreen(
    workspace: WorkspaceDto,
    vm: AppViewModel,
    recordId: String,
    isWorkspaceWidth: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val session = rememberWorkspaceSession(workspace, vm, recordId, scope)
    if (isWorkspaceWidth) {
        TabletWorkspace(workspace, session, vm, modifier)
    } else {
        PhoneWorkspace(workspace, session, vm, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneWorkspace(
    workspace: WorkspaceDto,
    session: WorkspaceSession,
    vm: AppViewModel,
    modifier: Modifier,
) {
    val layout = workspace.layout.toDomainOrNull() ?: session.layoutSync.tree
    val tabs = phoneTabModel(layout, workspace.activeViewId)
    val viewsById = session.viewsById
    var showAdd by remember { mutableStateOf(false) }
    var closeCandidate by remember { mutableStateOf<ViewDto?>(null) }

    Column(modifier.fillMaxSize().testTag("phone_workspace_tabs")) {
        if (tabs.viewIds.isNotEmpty()) {
            val selectedIndex = tabs.viewIds.indexOf(tabs.selectedId).coerceAtLeast(0)
            ScrollableTabRow(selectedTabIndex = selectedIndex) {
                tabs.viewIds.forEach { id ->
                    val view = viewsById[id]
                    val title = view?.let { viewTitle(it) } ?: "view"
                    Tab(
                        selected = id == tabs.selectedId,
                        onClick = { vm.setActiveView(workspace.id, id) },
                        text = {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        icon = {
                            IconButton(onClick = {
                                val v = view ?: return@IconButton
                                if (closeNeedsConfirm(v)) closeCandidate = v
                                else vm.closeWorkspaceView(workspace.id, id)
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close $title")
                            }
                        },
                    )
                }
                Tab(
                    selected = false,
                    onClick = { showAdd = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = "Add view") },
                )
            }
        }
        val selected = tabs.selectedId?.let { viewsById[it] }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (selected != null) {
                AndroidViewHost(
                    workspace = workspace,
                    view = selected,
                    session = session,
                    vm = vm,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No views", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }) {
            phoneAddKinds().forEach { kind ->
                TextButton(
                    onClick = {
                        showAdd = false
                        addPhoneView(workspace, session, vm, kind)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("tab-add-view-${kind.tag}"),
                ) { Text(kind.label) }
            }
        }
    }
    closeCandidate?.let { view ->
        AlertDialog(
            onDismissRequest = { closeCandidate = null },
            title = { Text("Close ${viewTitle(view)}?") },
            text = { Text("This ends the work behind this view.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeWorkspaceView(workspace.id, view.id)
                    closeCandidate = null
                }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { closeCandidate = null }) { Text("Cancel") }
            },
        )
    }
}

private fun addPhoneView(
    workspace: WorkspaceDto,
    session: WorkspaceSession,
    vm: AppViewModel,
    kind: NewViewKind,
) {
    val tree = workspace.layout.toDomainOrNull() ?: session.layoutSync.tree
    val open = openSingletonView(tree, session.viewsById, kind)
    if (open != null) {
        vm.setActiveView(workspace.id, open.first)
        return
    }
    val id = java.util.UUID.randomUUID().toString()
    vm.addWorkspaceView(workspace.id, kind.wire, addViewState(kind, System.currentTimeMillis()), id)
}

@Composable
private fun TabletWorkspace(
    workspace: WorkspaceDto,
    session: WorkspaceSession,
    vm: AppViewModel,
    modifier: Modifier,
) {
    val layoutSync = session.layoutSync
    val viewsById = session.viewsById
    var closeCandidate by remember { mutableStateOf<ViewDto?>(null) }
    PaneHost(
        layout = layoutSync.tree,
        onEdit = layoutSync::edit,
        titleFor = { vid -> viewsById[vid]?.let { viewTitle(it) } ?: "view" },
        onCloseView = { id ->
            val v = viewsById[id] ?: return@PaneHost
            if (closeNeedsConfirm(v)) closeCandidate = v
            else vm.closeWorkspaceView(workspace.id, id)
        },
        tabSlot = { itemId, state ->
            DefaultTabChip(
                itemId = itemId,
                title = viewsById[itemId]?.let { viewTitle(it) } ?: "view",
                state = state,
                labelFont = androidx.compose.ui.text.font.FontFamily.Monospace,
                onClose = { id ->
                    val v = viewsById[id] ?: return@DefaultTabChip
                    if (closeNeedsConfirm(v)) closeCandidate = v
                    else vm.closeWorkspaceView(workspace.id, id)
                },
            )
        },
        addSlot = { groupId ->
            PhoneAddChip(
                onPick = { kind ->
                    val open = openSingletonView(layoutSync.tree, viewsById, kind)
                    if (open != null) {
                        layoutSync.edit { setActiveViewInGroup(it, open.second, open.first) }
                    } else {
                        vm.addWorkspaceView(workspace.id, kind.wire, addViewState(kind, System.currentTimeMillis()))
                    }
                },
            )
        },
        emptyGroupSlot = {
            Text("No open views", color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = modifier.fillMaxSize().testTag("tablet_pane_host"),
        content = { viewId ->
            val view = viewsById[viewId]
            if (view == null) UnknownViewHint("view")
            else AndroidViewHost(workspace, view, session, vm, Modifier.fillMaxSize())
        },
    )
    closeCandidate?.let { view ->
        AlertDialog(
            onDismissRequest = { closeCandidate = null },
            title = { Text("Close ${viewTitle(view)}?") },
            text = { Text("This ends the work behind this view.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeWorkspaceView(workspace.id, view.id)
                    closeCandidate = null
                }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { closeCandidate = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneAddChip(onPick: (NewViewKind) -> Unit) {
    var show by remember { mutableStateOf(false) }
    IconButton(onClick = { show = true }, modifier = Modifier.testTag("tab-add")) {
        Icon(Icons.Filled.Add, contentDescription = "Add view")
    }
    if (show) {
        ModalBottomSheet(onDismissRequest = { show = false }) {
            phoneAddKinds().forEach { kind ->
                TextButton(
                    onClick = {
                        show = false
                        onPick(kind)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("tab-add-view-${kind.tag}"),
                ) { Text(kind.label) }
            }
        }
    }
}
