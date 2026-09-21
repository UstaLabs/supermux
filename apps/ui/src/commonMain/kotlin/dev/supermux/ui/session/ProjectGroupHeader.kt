// Persistent-project chrome shared by the sidebar and the Archived screen (Task 9): the project's
// image (authenticated bytes → Coil, the chat attachment path), the header overflow menu (settings,
// move up/down) and the muted "No workspaces" row an empty project shows.
package dev.supermux.ui.session

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.chat.DecodedImage
import dev.supermux.ui.chat.rememberDecodedImage
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.workspace.ProjectRef
import dev.supermux.workspace.WorkspaceGroup
import dev.supermux.workspace.projectGroupKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ProjectTestIds {
    fun image(key: String) = "project_image_$key"
    fun menu(key: String) = "project_menu_$key"
    fun empty(key: String) = "project_empty_$key"
    fun emptyNew(key: String) = "project_empty_new_$key"
    const val SETTINGS = "project_menu_settings"
    const val MOVE_UP = "project_menu_move_up"
    const val MOVE_DOWN = "project_menu_move_down"
}

/** The [ProjectRef] a persistent-project group was built from, or null for a path/PA group. */
fun WorkspaceGroup.projectRef(): ProjectRef? = project?.let { ProjectRef(hostId.orEmpty(), it) }

/**
 * The full new order of [target]'s host's projects after moving it [delta] places (−1 up, +1 down),
 * or null when it cannot move that way. Only that host's projects take part — ids are per-broker
 * and `PATCH /project-catalog/reorder` wants that broker's whole catalog. The starting order is the
 * one the sidebar paints (sortOrder, name, id).
 *
 * [visibleIds] are the project ids the caller actually renders (null → all). A move only steps
 * over VISIBLE neighbours — a hidden (archived-only) project in between is skipped — and is a swap
 * of the two visible positions, so every hidden project keeps its place in the full order. A
 * hidden [target] never moves.
 */
fun reorderedProjectIds(
    projects: List<ProjectRef>,
    target: ProjectRef,
    delta: Int,
    visibleIds: Set<String>? = null,
): List<String>? {
    val ids = projects
        .filter { it.hostId == target.hostId }
        .distinctBy { it.project.id }
        .sortedWith(compareBy({ it.project.sortOrder }, { it.project.name }, { it.project.id }))
        .map { it.project.id }
        .toMutableList()
    if (delta == 0) return null
    val slots = ids.indices.filter { visibleIds == null || ids[it] in visibleIds }
    val from = slots.indexOfFirst { ids[it] == target.project.id }
    val to = from + delta
    if (from < 0 || to !in slots.indices) return null
    // Shift the visible subsequence (a plain swap for |delta| == 1); hidden slots are untouched.
    val visible = slots.map { ids[it] }.toMutableList()
    visible.add(to, visible.removeAt(from))
    slots.forEachIndexed { i, slot -> ids[slot] = visible[i] }
    return ids
}

/**
 * The app-wide project image bytes cache: ONE per shell (hoisted in `SupermuxApp`), shared by the
 * sidebar, the Archived screen and the launcher's picker. Holds only the LATEST imageId per
 * (host, project) — a new image replaces, not accumulates — and shares an in-flight fetch, so
 * several headers asking at once make one request. A failed/empty fetch is dropped so it retries.
 */
class ProjectImageCache(private val scope: CoroutineScope) {
    private class Entry(val imageId: String, val bytes: Deferred<ByteArray?>)

    private val mutex = Mutex()
    private val entries = HashMap<String, Entry>()

    suspend fun get(ref: ProjectRef, load: suspend (ProjectRef) -> ByteArray?): ByteArray? {
        val imageId = ref.project.imageId ?: return null
        val key = projectGroupKey(ref.hostId, ref.project.id)
        val pending = mutex.withLock {
            entries[key]?.takeIf { it.imageId == imageId }?.bytes
                ?: scope.async { runCatching { load(ref) }.getOrNull() }
                    .also { entries[key] = Entry(imageId, it) }
        }
        val bytes = pending.await()
        if (bytes == null) mutex.withLock { if (entries[key]?.bytes === pending) entries.remove(key) }
        return bytes
    }
}

/**
 * [load] through [shared] (the app's one [ProjectImageCache]), or through a screen-local cache when
 * none is passed (previews, tests): a lazy header scrolled out and back in must not refetch.
 */
@Composable
internal fun rememberCachedProjectImageLoader(
    load: suspend (ProjectRef) -> ByteArray?,
    shared: ProjectImageCache? = null,
): suspend (ProjectRef) -> ByteArray? {
    val scope = rememberCoroutineScope()
    val local = remember(scope) { ProjectImageCache(scope) }
    val cache = shared ?: local
    return remember(cache, load) { { ref -> cache.get(ref, load) } }
}

/**
 * A project's image at [size], rounded — or [fallback] while it loads, when it has none, or when
 * the fetch/decode fails. Bytes come from [loadImage] (the broker's authenticated GET) and decode
 * through Coil, exactly like an inline chat attachment.
 */
@Composable
fun ProjectImage(
    ref: ProjectRef,
    loadImage: suspend (ProjectRef) -> ByteArray?,
    size: Dp = 20.dp,
    fallback: @Composable () -> Unit,
) {
    val imageId = ref.project.imageId
    if (imageId == null) {
        fallback()
        return
    }
    val key = projectGroupKey(ref.hostId, ref.project.id)
    var bytes by remember(key, imageId) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(key, imageId) {
        bytes = runCatching { loadImage(ref) }.getOrNull()
    }
    val data = bytes
    if (data == null) {
        fallback()
        return
    }
    // Decode at the painted size, not the upload's (up to 5 MB → a ~48 MB bitmap for a 20dp
    // icon), and keyed so a header re-entering composition gets Coil's memory-cached bitmap.
    val px = with(LocalDensity.current) { size.roundToPx() }
    val cacheKey = "project:${ref.hostId}:${ref.project.id}:$imageId"
    when (val decoded = rememberDecodedImage(data, size = px, memoryCacheKey = cacheKey)) {
        is DecodedImage.Ready -> Image(
            painter = decoded.painter,
            contentDescription = ref.project.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(4.dp))
                .testTag(ProjectTestIds.image(key)),
        )
        else -> fallback()
    }
}

/** The ⋮ on a project header: settings, and move up/down within its host's catalog. */
@Composable
internal fun ProjectHeaderMenu(
    groupKey: String,
    label: String,
    onSettings: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val hit = if (LocalPointerAvailable.current) 20.dp else 40.dp
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(hit).testTag(ProjectTestIds.menu(groupKey)),
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "$label options",
                tint = cs.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Project settings…") },
                onClick = { open = false; onSettings() },
                modifier = Modifier.testTag(ProjectTestIds.SETTINGS),
            )
            if (onMoveUp != null) {
                DropdownMenuItem(
                    text = { Text("Move up") },
                    onClick = { open = false; onMoveUp() },
                    modifier = Modifier.testTag(ProjectTestIds.MOVE_UP),
                )
            }
            if (onMoveDown != null) {
                DropdownMenuItem(
                    text = { Text("Move down") },
                    onClick = { open = false; onMoveDown() },
                    modifier = Modifier.testTag(ProjectTestIds.MOVE_DOWN),
                )
            }
        }
    }
}

/** An empty project's single muted row; its "+" starts a workspace in that project. */
@Composable
internal fun EmptyProjectRow(groupKey: String, onNew: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val hit = if (LocalPointerAvailable.current) 24.dp else 40.dp
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 38.dp, end = 12.dp)
            .testTag(ProjectTestIds.empty(groupKey)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "No workspaces",
            color = cs.onSurfaceVariant.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onNew,
            modifier = Modifier.size(hit).testTag(ProjectTestIds.emptyNew(groupKey)),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "New workspace",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
