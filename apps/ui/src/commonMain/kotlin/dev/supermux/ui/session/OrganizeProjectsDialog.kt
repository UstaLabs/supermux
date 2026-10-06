// "Organize projects" (the list ⋮): every project of every shown host in one drag-to-reorder
// list, saved per host on Done through `PATCH /project-catalog/reorder`. Projects only reorder
// within their own host — ids and sort orders are per-broker.
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.host.HostView
import dev.supermux.ui.theme.Space
import dev.supermux.workspace.ProjectRef
import dev.supermux.workspace.projectGroupKey

object OrganizeProjectsTestIds {
    const val SHEET = "organize_projects"
    const val DONE = "organize_projects_done"
    const val CANCEL = "organize_projects_cancel"
    fun row(hostId: String, projectId: String) = "organize_projects_row_${projectGroupKey(hostId, projectId)}"
}

/**
 * [projects] grouped by host in first-seen host order, each host's projects in the order the
 * sidebar paints them (sortOrder, name, id).
 */
fun projectOrderByHost(projects: List<ProjectRef>): Map<String, List<ProjectRef>> =
    projects
        .distinctBy { it.hostId to it.project.id }
        .groupBy { it.hostId }
        .mapValues { (_, refs) ->
            refs.sortedWith(compareBy({ it.project.sortOrder }, { it.project.name }, { it.project.id }))
        }

/** The hosts whose order differs between [before] and [after], each with its new full id order. */
fun changedProjectOrders(
    before: Map<String, List<ProjectRef>>,
    after: Map<String, List<ProjectRef>>,
): Map<String, List<String>> =
    after.mapNotNull { (hostId, refs) ->
        val ids = refs.map { it.project.id }
        if (before[hostId]?.map { it.project.id } == ids) null else hostId to ids
    }.toMap()

@Composable
fun OrganizeProjectsDialog(
    projects: List<ProjectRef>,
    hosts: List<HostView>,
    loadImage: suspend (ProjectRef) -> ByteArray?,
    onSave: (hostId: String, orderedIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val initial = remember(projects) { projectOrderByHost(projects) }
    val orders = remember(initial) { mutableStateMapOf<String, List<ProjectRef>>().apply { putAll(initial) } }
    // A host header only earns its row when there is more than one host to tell apart.
    val showHostHeaders = initial.size > 1
    val hostNames = remember(hosts) { hosts.associate { it.recordId to it.displayName } }
    val byKey = remember(initial) {
        initial.values.flatten().associateBy { projectGroupKey(it.hostId, it.project.id) }
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableListState(listState) { from, to ->
        val a = byKey[from.key] ?: return@rememberReorderableListState false
        val b = byKey[to.key] ?: return@rememberReorderableListState false
        if (a.hostId != b.hostId) return@rememberReorderableListState false
        val rows = orders[a.hostId] ?: return@rememberReorderableListState false
        val fromIdx = rows.indexOfFirst { it.project.id == a.project.id }
        val toIdx = rows.indexOfFirst { it.project.id == b.project.id }
        if (fromIdx < 0 || toIdx < 0) return@rememberReorderableListState false
        // The mutation must land before onMove returns so neighbours can animate.
        orders[a.hostId] = rows.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
        true
    }

    AdaptiveProjectContainer(tag = OrganizeProjectsTestIds.SHEET, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.md)) {
            Text(
                "Organize projects",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Space.lg),
            )
            Text(
                "Drag to change the order projects appear in the sidebar.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = Space.sm),
            ) {
                initial.keys.forEach { hostId ->
                    if (showHostHeaders) {
                        item(key = "host:$hostId") {
                            Text(
                                hostNames[hostId] ?: hostId,
                                color = cs.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = Space.lg, top = Space.sm, bottom = Space.xs),
                            )
                        }
                    }
                    items(orders[hostId].orEmpty(), key = { projectGroupKey(it.hostId, it.project.id) }) { ref ->
                        ReorderableItem(reorderState, projectGroupKey(ref.hostId, ref.project.id)) { dragging ->
                            OrganizeProjectRow(
                                ref = ref,
                                loadImage = loadImage,
                                dragging = dragging,
                                modifier = Modifier.reorderDragHandle(),
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.xs),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(OrganizeProjectsTestIds.CANCEL)) {
                    Text("Cancel")
                }
                TextButton(
                    onClick = {
                        changedProjectOrders(initial, orders).forEach { (hostId, ids) -> onSave(hostId, ids) }
                        onDismiss()
                    },
                    modifier = Modifier.testTag(OrganizeProjectsTestIds.DONE),
                ) { Text("Done") }
            }
        }
    }
}

@Composable
private fun OrganizeProjectRow(
    ref: ProjectRef,
    loadImage: suspend (ProjectRef) -> ByteArray?,
    dragging: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.sm, vertical = 2.dp)
            .then(if (dragging) Modifier.shadow(6.dp, RoundedCornerShape(8.dp)) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(if (dragging) cs.surfaceContainerHighest else cs.surfaceContainerLow)
            .padding(horizontal = Space.sm, vertical = Space.sm)
            .testTag(OrganizeProjectsTestIds.row(ref.hostId, ref.project.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(
            Icons.Filled.DragIndicator,
            contentDescription = "Drag to reorder",
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        ProjectImage(ref, loadImage, size = 20.dp) { PathGroupTile(ref.project.name, fullLabel = true) }
        Text(
            ref.project.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
