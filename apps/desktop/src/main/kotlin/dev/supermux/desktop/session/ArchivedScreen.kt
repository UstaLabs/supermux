// The desktop Archived-sessions screen — a faithful port of
// apps/android/.../settings/MoreScreens.kt's `ArchivedScreen` + its internal `ArchivedChatScreen`
// (the read-only transcript view). A full-pane overlay (mirroring the New-Session launcher, see
// WorkspaceRoot): a project-filtered, searchable, flat list of archived sessions (most-recently-
// killed first, in the order the broker returns them), each row → a read-only chat view of that
// session's transcript + a Resume button. Desktop deltas from Android:
//   - ModalBottomSheet / TopAppBar → a plain Column header + a Compose DropdownMenu (project filter).
//   - Route navigation → internal `openedId` state (list ⇄ chat), the same way Android's ArchivedScreen
//     keeps the chat view internal.
//   - Read-only transcript reuses the desktop chat Timeline (mergeTimeline over the archived logs +
//     TimelineItemRow), with NO composer and onOpenFile wired to a no-op (nothing to edit here).
// Resume → onResume(id) → the whole archived overlay closes (WorkspaceRoot's onBack); the resumed
// session arrives live via a session_added/snapshot WS frame, not from this screen.
package dev.supermux.desktop.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import dev.supermux.desktop.chat.TimelineItemRow
import dev.supermux.desktop.chat.mergeTimeline
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.net.ArchivedDto
import dev.supermux.proto.LogEntry
import dev.supermux.session.ArchivedProject
import dev.supermux.session.archivedProjects
import dev.supermux.session.filterArchivedByProject
import dev.supermux.session.formatWorkdir

/**
 * Pure client-side search predicate for the archived list: a session matches the (trimmed,
 * case-insensitive) [query] if it appears in the session name, its workdir, or its repo root. A
 * blank query matches everything. Extracted (and unit-tested) so the filtering rule is verifiable
 * without spinning up the composable — mirrors the launcher's extracted pure decisions.
 */
fun archivedMatchesQuery(dto: ArchivedDto, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    if (dto.name.lowercase().contains(q)) return true
    if (dto.workdir.lowercase().contains(q)) return true
    if ((dto.repo_root ?: "").lowercase().contains(q)) return true
    return false
}

/**
 * The archived-sessions overlay. [archived] is loaded by the caller (WorkspaceRoot loads
 * `app.archived()` when the overlay opens) and passed in whole; the screen applies the project
 * filter ([filterArchivedByProject] over [archivedProjects]) + the client-side search
 * ([archivedMatchesQuery]) on top. Tapping a row opens [ArchivedChatView] (internal nav via
 * [openedId], list ⇄ chat). [onResume] resumes an archived session and the caller closes the whole
 * overlay; [loadLogs] fetches a session's read-only transcript.
 *
 * Escape mirrors Android's back stack: from the chat view it returns to the list; from the list it
 * calls [onBack] (closing the overlay). Handled on a focusable root so it works without a focused
 * text field, and via onPreviewKeyEvent so it wins even while the search field is focused.
 */
@Composable
fun ArchivedScreen(
    archived: List<ArchivedDto>,
    home: String,
    onBack: () -> Unit,
    onResume: (String) -> Unit,
    loadLogs: suspend (String) -> List<LogEntry>,
    // True while the caller's `app.archived()` fetch is still in flight — the list shows a spinner
    // (not the "No archived sessions." empty text) until it resolves, so a slow fetch never flashes
    // an empty state (mirrors Android's `loading` flag + ArchivedChatView's own spinner).
    loading: Boolean = false,
    // One-shot "open this archived session's read-only transcript" request (an id from [archived]),
    // set by the off-by-default `SM_ARCHIVED_OPEN` headless hook (via WorkspaceUiState.forceArchivedOpenFor,
    // M4e-T3 live verification) so the chat view renders without a click. Consumed (→ [onForceOpenConsumed])
    // the same run it's applied, so it never re-fires on an unrelated recomposition. Null/no-op in
    // normal operation.
    forceOpenId: String? = null,
    onForceOpenConsumed: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    // Internal nav: tapping a row opens a read-only chat view of that session.
    var openedId by remember { mutableStateOf<String?>(null) }
    var selectedProject by remember { mutableStateOf<String?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(forceOpenId) {
        if (forceOpenId != null) {
            openedId = forceOpenId
            onForceOpenConsumed()
        }
    }

    val projects = remember(archived, home) { archivedProjects(archived, home) }
    // Clear the filter if the selected project no longer has any archived sessions.
    LaunchedEffect(projects) {
        if (selectedProject != null && projects.none { it.key == selectedProject }) {
            selectedProject = null
        }
    }

    val opened = openedId?.let { id -> archived.firstOrNull { it.id == id } }

    // Keep focus on the root across list ⇄ chat navigation (a row click moves focus to the row),
    // so Escape is always caught by the onPreviewKeyEvent below — even with no field focused.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(openedId) { runCatching { focusRequester.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(cs.surfaceContainerHigh)
            .testTag("archived_root")
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    if (openedId != null) openedId = null else onBack()
                    true
                } else {
                    false
                }
            },
    ) {
        if (opened != null) {
            ArchivedChatView(
                sessionId = opened.id,
                name = opened.name,
                loadLogs = loadLogs,
                onResume = { onResume(opened.id) },
                onBack = { openedId = null },
            )
        } else {
            ArchivedList(
                archived = archived,
                loading = loading,
                projects = projects,
                home = home,
                selectedProject = selectedProject,
                onSelectProject = { selectedProject = it },
                filterOpen = filterOpen,
                onFilterOpenChange = { filterOpen = it },
                query = query,
                onQueryChange = { query = it },
                onOpen = { openedId = it },
            )
        }
    }
}

@Composable
private fun ArchivedList(
    archived: List<ArchivedDto>,
    loading: Boolean,
    projects: List<ArchivedProject>,
    home: String,
    selectedProject: String?,
    onSelectProject: (String?) -> Unit,
    filterOpen: Boolean,
    onFilterOpenChange: (Boolean) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val visible = remember(archived, selectedProject, query) {
        filterArchivedByProject(archived, selectedProject).filter { archivedMatchesQuery(it, query) }
    }

    Column(Modifier.fillMaxSize().testTag("archived_screen")) {
        // ── Header: back + title + project filter ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Archived", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (archived.isNotEmpty()) {
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.sm))
                            .clickable { onFilterOpenChange(true) }
                            .padding(horizontal = Space.sm, vertical = Space.xs)
                            .testTag("archived_filter"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        Icon(
                            Icons.Filled.FilterList,
                            contentDescription = "Filter by project",
                            tint = if (selectedProject != null) cs.primary else cs.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            selectedProject?.let { key -> projects.firstOrNull { it.key == key }?.label } ?: "All projects",
                            color = if (selectedProject != null) cs.primary else cs.onSurfaceVariant,
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    DropdownMenu(expanded = filterOpen, onDismissRequest = { onFilterOpenChange(false) }) {
                        DropdownMenuItem(
                            text = { Text("All projects") },
                            onClick = { onSelectProject(null); onFilterOpenChange(false) },
                            trailingIcon = if (selectedProject == null) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null,
                        )
                        projects.forEach { p ->
                            DropdownMenuItem(
                                text = { Text("${p.label}  (${p.count})") },
                                onClick = { onSelectProject(p.key); onFilterOpenChange(false) },
                                modifier = Modifier.testTag("archived_project_${p.key}"),
                                trailingIcon = if (selectedProject == p.key) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else null,
                            )
                        }
                    }
                }
            }
        }

        // ── Search field (client-side name/path filter) ──
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Search archived…", color = cs.onSurfaceVariant) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg)
                .testTag("archived_search"),
        )

        Spacer(Modifier.size(Space.sm))

        // ── The list ──
        Box(Modifier.fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
                archived.isEmpty() -> Text(
                    "No archived sessions.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                visible.isEmpty() -> Text(
                    "No matches.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = Space.sm)) {
                    items(visible, key = { it.id }) { session ->
                        ArchivedRow(
                            session = session,
                            home = home,
                            onOpen = { onOpen(session.id) },
                        )
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivedRow(session: ArchivedDto, home: String, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("archived_row_${session.id}")
            .padding(horizontal = Space.sm, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(session.name, color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                formatWorkdir(session.repo_root ?: session.workdir, home),
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        val killed = relTime(session.killed_at)
        if (killed.isNotEmpty()) {
            Text(killed, color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

// ─── ArchivedChatView (read-only transcript of an archived session) ────────────

/**
 * The read-only transcript view: loads [loadLogs] on open and renders the desktop chat Timeline
 * (mergeTimeline over the logs + TimelineItemRow, onOpenFile a no-op — nothing to edit) with NO
 * composer. The header carries the session name + a Resume button; Resume calls [onResume] (the
 * caller resumes then closes the whole overlay).
 */
@Composable
internal fun ArchivedChatView(
    sessionId: String,
    name: String,
    loadLogs: suspend (String) -> List<LogEntry>,
    onResume: () -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var messages by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(sessionId) {
        messages = loadLogs(sessionId)
        loading = false
    }

    Column(Modifier.fillMaxSize().testTag("archived_chat")) {
        // ── Header: back + name/"archived" + Resume ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("archived_chat_back")) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = cs.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(Space.sm))
            Column(Modifier.weight(1f)) {
                Text(name, color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("archived", color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
            TextButton(onClick = onResume, modifier = Modifier.testTag("archived_resume")) {
                Text("Resume", color = cs.primary, fontSize = 13.sp)
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
                messages.isEmpty() -> Text(
                    "No messages.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    // Read-only: reuse the chat timeline composables; no composer.
                    val timelineItems = remember(messages) { mergeTimeline(messages, emptyList()) }
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = Space.lg, vertical = Space.md),
                        verticalArrangement = Arrangement.spacedBy(Space.lg),
                    ) {
                        items(timelineItems) { item ->
                            TimelineItemRow(item, onOpenFile = {})
                        }
                    }
                }
            }
        }
    }
}
