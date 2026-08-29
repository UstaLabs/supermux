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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import dev.supermux.android.AppViewModel
import dev.supermux.android.ui.keepAlivePanel
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.panes.DefaultTabChip
import dev.supermux.ui.panes.PaneHost
import dev.supermux.ui.workspace.WorkspaceSession
import dev.supermux.workspace.NewViewKind
import dev.supermux.workspace.NewViewPlacement
import dev.supermux.workspace.addViewToGroup
import dev.supermux.workspace.firstGroupId
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.openSingletonView
import dev.supermux.workspace.setActiveViewInGroup
import dev.supermux.workspace.splitGroup
import dev.supermux.workspace.toDomainOrNull
import dev.supermux.workspace.viewTitle
import java.util.UUID

/**
 * Broker-driven workspace body. Phone flattens every view to a tab row and never PATCHes
 * [layout] (D2/D3). Tablet renders the tree through [PaneHost] (D5).
 */
@Composable
fun WorkspaceScreen(
    workspace: WorkspaceDto,
    vm: AppViewModel,
    isWorkspaceWidth: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val newId = remember { { UUID.randomUUID().toString() } }
    val session = rememberWorkspaceSession(workspace, vm, isWorkspaceWidth, scope, newId)
    if (isWorkspaceWidth) {
        TabletWorkspace(workspace, session, vm, newId, modifier)
    } else {
        PhoneWorkspace(workspace, session, vm, newId, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneWorkspace(
    workspace: WorkspaceDto,
    session: WorkspaceSession,
    vm: AppViewModel,
    newId: () -> String,
    modifier: Modifier,
) {
    val layout = workspace.layout.toDomainOrNull() ?: session.layoutSync.tree
    val tabs = phoneTabModel(layout, workspace.activeViewId)
    val viewsById = session.viewsById
    var showAdd by remember { mutableStateOf(false) }
    var closeCandidate by remember { mutableStateOf<ViewDto?>(null) }

    fun closeOrConfirm(view: ViewDto) {
        if (closeNeedsConfirm(view)) closeCandidate = view
        else vm.closeWorkspaceView(workspace.id, view.id)
    }

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
                                closeOrConfirm(v)
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
        val liveIds = tabs.viewIds.toSet()
        // Keep the last 3 visited views composed (hidden) so WebView/terminal PTY survive tab
        // switches. Evict LRU beyond 3 — more would pin too many WebViews on a phone.
        val retained = rememberVisitedWorkspaces(tabs.selectedId, liveIds, maxSize = 3)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (retained.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No views", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                retained.forEach { id ->
                    val view = viewsById[id] ?: return@forEach
                    key(view.id) {
                        Box(Modifier.keepAlivePanel(id == tabs.selectedId)) {
                            AndroidViewHost(
                                workspace = workspace,
                                view = view,
                                session = session,
                                vm = vm,
                                wide = false,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
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
                        addPhoneView(workspace, session, vm, kind, newId)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("tab-add-view-${kind.tag}"),
                ) { Text(kind.label) }
            }
        }
    }
    CloseViewDialog(
        view = closeCandidate,
        onDismiss = { closeCandidate = null },
        onConfirm = { view ->
            vm.closeWorkspaceView(workspace.id, view.id)
            closeCandidate = null
        },
    )
}

private fun addPhoneView(
    workspace: WorkspaceDto,
    session: WorkspaceSession,
    vm: AppViewModel,
    kind: NewViewKind,
    newId: () -> String,
) {
    val tree = workspace.layout.toDomainOrNull() ?: session.layoutSync.tree
    val open = openSingletonView(tree, session.viewsById, kind)
    if (open != null) {
        vm.setActiveView(workspace.id, open.first)
        return
    }
    val id = newId()
    session.provisionalViews[id] = ViewDto(
        id = id,
        workspaceId = workspace.id,
        kind = kind.wire,
        state = addViewState(kind, System.currentTimeMillis()),
    )
    vm.addWorkspaceView(workspace.id, kind.wire, addViewState(kind, System.currentTimeMillis()), id)
}

@Composable
private fun TabletWorkspace(
    workspace: WorkspaceDto,
    session: WorkspaceSession,
    vm: AppViewModel,
    newId: () -> String,
    modifier: Modifier,
) {
    val layoutSync = session.layoutSync
    val viewsById = session.viewsById
    var closeCandidate by remember { mutableStateOf<ViewDto?>(null) }

    fun closeOrConfirm(view: ViewDto) {
        if (closeNeedsConfirm(view)) closeCandidate = view
        else vm.closeWorkspaceView(workspace.id, view.id)
    }

    PaneHost(
        layout = layoutSync.tree,
        onEdit = layoutSync::edit,
        titleFor = { vid -> viewsById[vid]?.let { viewTitle(it) } ?: "view" },
        onCloseView = { id ->
            val v = viewsById[id] ?: return@PaneHost
            closeOrConfirm(v)
        },
        tabSlot = { itemId, state ->
            DefaultTabChip(
                itemId = itemId,
                title = viewsById[itemId]?.let { viewTitle(it) } ?: "view",
                state = state,
                labelFont = androidx.compose.ui.text.font.FontFamily.Monospace,
                onClose = { id ->
                    val v = viewsById[id] ?: return@DefaultTabChip
                    closeOrConfirm(v)
                },
            )
        },
        addSlot = { groupId ->
            AddViewChip(
                wide = true,
                onPick = { kind, placement ->
                    addTabletView(workspace, session, vm, kind, placement, groupId, newId)
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
            else key(view.id) {
                AndroidViewHost(workspace, view, session, vm, Modifier.fillMaxSize(), wide = true)
            }
        },
    )
    CloseViewDialog(
        view = closeCandidate,
        onDismiss = { closeCandidate = null },
        onConfirm = { view ->
            vm.closeWorkspaceView(workspace.id, view.id)
            closeCandidate = null
        },
    )
}

internal fun addTabletView(
    workspace: WorkspaceDto,
    session: WorkspaceSession,
    vm: AppViewModel,
    kind: NewViewKind,
    placement: NewViewPlacement,
    groupId: String,
    newId: () -> String,
) {
    val layoutSync = session.layoutSync
    val viewsById = session.viewsById
    val open = openSingletonView(layoutSync.tree, viewsById, kind)
    if (open != null) {
        layoutSync.edit { setActiveViewInGroup(it, open.second, open.first) }
        return
    }
    val id = newId()
    val state = addViewState(kind, System.currentTimeMillis())
    session.provisionalViews[id] = ViewDto(
        id = id,
        workspaceId = workspace.id,
        kind = kind.wire,
        state = state,
    )
    vm.addWorkspaceView(workspace.id, kind.wire, state, id, groupId)
    if (placement != NewViewPlacement.HERE) {
        val dir = if (placement == NewViewPlacement.SPLIT_RIGHT) "row" else "column"
        val newGroupId = newId()
        layoutSync.edit { tree ->
            val ownerId = groupIdOf(tree, id)
            val owner = ownerId ?: firstGroupId(tree) ?: return@edit tree
            val withView = if (ownerId == null) addViewToGroup(tree, owner, id) else tree
            splitGroup(withView, owner, id, dir, newGroupId)
        }
    }
}

@Composable
private fun CloseViewDialog(
    view: ViewDto?,
    onDismiss: () -> Unit,
    onConfirm: (ViewDto) -> Unit,
) {
    view ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Close ${viewTitle(view)}?") },
        text = { Text("This ends the work behind this view.") },
        confirmButton = {
            TextButton(onClick = { onConfirm(view) }) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddViewChip(
    wide: Boolean = false,
    onPick: (NewViewKind, NewViewPlacement) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    var pendingKind by remember { mutableStateOf<NewViewKind?>(null) }
    IconButton(onClick = { show = true }, modifier = Modifier.testTag("tab-add")) {
        Icon(Icons.Filled.Add, contentDescription = "Add view")
    }
    if (show) {
        ModalBottomSheet(onDismissRequest = { show = false; pendingKind = null }) {
            val kind = pendingKind
            if (wide && kind != null) {
                NewViewPlacement.entries.forEach { placement ->
                    TextButton(
                        onClick = {
                            show = false
                            pendingKind = null
                            onPick(kind, placement)
                        },
                        modifier = Modifier.fillMaxWidth().testTag(
                            "tab-add-view-${kind.tag}-${placement.name.lowercase()}",
                        ),
                    ) { Text(placement.label) }
                }
            } else {
                phoneAddKinds().forEach { k ->
                    TextButton(
                        onClick = {
                            if (wide) {
                                pendingKind = k
                            } else {
                                show = false
                                onPick(k, NewViewPlacement.HERE)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("tab-add-view-${k.tag}"),
                    ) { Text(k.label) }
                }
            }
        }
    }
}
