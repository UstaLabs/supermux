// A persistent project's settings: name, image and locations (Persistent Projects, Task 11).
//
// One body, two containers — the cluster-E add-forge pattern: a `ModalBottomSheet` on a phone
// (Compact, or no pointer attached), a centred dialog everywhere else. The body renders from the
// LIVE catalog ([ProjectRef] list), never from a mutation's response: HostStore only inserts NEW
// projects from a response, and every update to an existing one (rename, image, a location moved
// out) lands through the broker's `projects_changed` broadcast. There is no delete (spec).
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermux.host.HostView
import dev.supermux.net.PathValidation
import dev.supermux.proto.ProjectDto
import dev.supermux.session.formatWorkdir
import dev.supermux.state.ProjectLocationResult
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.PickKind
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.Dialog
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.workspace.ProjectRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags of the project settings sheet and the "New project" prompt. */
object ProjectSettingsTestIds {
    const val SHEET = "project_settings"
    const val NAME = "project_settings_name"
    const val SAVE_NAME = "project_settings_save_name"
    const val PICK_IMAGE = "project_settings_pick_image"
    const val REMOVE_IMAGE = "project_settings_remove_image"
    const val MESSAGE = "project_settings_message"
    const val ADD_PATH = "project_settings_add_path"
    const val ADD_LOCATION = "project_settings_add_location"
    const val CONFLICT = "project_settings_conflict"
    const val MOVE_HERE = "project_settings_move_here"
    fun location(id: String) = "project_settings_location_$id"
    fun moveTo(locationId: String) = "project_settings_move_to_$locationId"
    fun moveTarget(projectId: String) = "project_settings_move_target_$projectId"

    const val NEW_PROJECT = "new_project_dialog"
    const val NEW_PROJECT_NAME = "new_project_name"
    const val NEW_PROJECT_CREATE = "new_project_create"
    fun newProjectHost(recordId: String) = "new_project_host_$recordId"
}

/** Identifies this sheet to `Platform.pickFiles` (see the chat composer's twin). */
const val PROJECT_SETTINGS_PICK_REQUESTER = "project-settings"

/** The broker's project-image cap (`PUT /project-catalog/:id/image` answers 413 above it). */
const val PROJECT_IMAGE_MAX_BYTES: Long = 5L * 1024 * 1024

private val PROJECT_IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

/**
 * The upload mime for a picked project image, or null when it is not one the broker takes
 * (png / jpeg / webp / gif). A picker that could not tell (`application/octet-stream`, blank)
 * falls back to the file extension.
 */
fun projectImageMime(file: PickedFile): String? {
    val mime = file.mime.lowercase().substringBefore(';').trim()
    if (mime == "image/jpg") return "image/jpeg"
    if (mime in PROJECT_IMAGE_MIMES) return mime
    return when (file.name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> null
    }
}

/**
 * The id of [owner]'s location at [path] — how "Move here" finds what to move after a 409, which
 * names only the owning project. Compared without trailing slashes (the broker normalizes them).
 */
fun locationIdForPath(owner: ProjectDto, path: String): String? {
    val want = path.trimEnd('/').ifEmpty { "/" }
    return owner.locations.firstOrNull { it.path.trimEnd('/').ifEmpty { "/" } == want }?.id
}

/**
 * The sheet's broker seam — every call is routed to the project's OWN host by recordId.
 * Results are for error reporting only; the sheet re-renders from the live catalog.
 */
data class ProjectSettingsActions(
    val rename: suspend (hostId: String, projectId: String, name: String) -> ProjectDto? = { _, _, _ -> null },
    val setImage: suspend (hostId: String, projectId: String, bytes: ByteArray, mime: String) -> ProjectDto? =
        { _, _, _, _ -> null },
    val clearImage: suspend (hostId: String, projectId: String) -> ProjectDto? = { _, _ -> null },
    val addLocation: suspend (hostId: String, projectId: String, path: String) -> ProjectLocationResult =
        { _, _, _ -> ProjectLocationResult.Failed },
    val moveLocation: suspend (hostId: String, locationId: String, projectId: String) -> ProjectDto? =
        { _, _, _ -> null },
    /** `POST /paths/validate` on the project's host — the launcher's typed-path check. */
    val validatePath: suspend (hostId: String, path: String) -> PathValidation? = { _, _ -> null },
)

/** A 409 on "Add location": [path] (normalized) already belongs to [owner]. */
private data class LocationConflict(val path: String, val owner: ProjectRef)

/**
 * Settings for the project `(hostId, projectId)`, looked up in the live [projects] catalog on
 * every frame. Dismisses itself if the project disappears (host forgotten, catalog reset).
 */
@Composable
fun ProjectSettingsSheet(
    hostId: String,
    projectId: String,
    projects: List<ProjectRef>,
    home: String,
    actions: ProjectSettingsActions,
    onDismiss: () -> Unit,
    loadImage: suspend (ProjectRef) -> ByteArray? = { null },
) {
    val ref = projects.firstOrNull { it.hostId == hostId && it.project.id == projectId }
    // A just-created project may reach the merged fleet catalog a frame after the sheet opens:
    // wait for it, and only dismiss once a project we HAVE shown is gone.
    var seen by remember(hostId, projectId) { mutableStateOf(false) }
    if (ref == null) {
        if (seen) LaunchedEffect(Unit) { onDismiss() }
        return
    }
    SideEffect { seen = true }
    AdaptiveProjectContainer(tag = ProjectSettingsTestIds.SHEET, onDismiss = onDismiss) {
        ProjectSettingsBody(ref, projects, home, actions, loadImage, onDismiss)
    }
}

@Composable
private fun ProjectSettingsBody(
    ref: ProjectRef,
    projects: List<ProjectRef>,
    home: String,
    actions: ProjectSettingsActions,
    loadImage: suspend (ProjectRef) -> ByteArray?,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val platform = LocalPlatform.current
    val project = ref.project
    val hostId = ref.hostId
    val latestRef by rememberUpdatedState(ref)

    var name by remember(hostId, project.id) { mutableStateOf(project.name) }
    // True once the user typed a name that differs from the live one; until then the field
    // follows the catalog, so a rename from another client never leaves a stale, saveable name.
    var nameEdited by remember(hostId, project.id) { mutableStateOf(false) }
    LaunchedEffect(project.name) { if (!nameEdited) name = project.name }
    var busy by remember(hostId, project.id) { mutableStateOf(false) }
    var message by remember(hostId, project.id) { mutableStateOf<String?>(null) }
    var newPath by remember(hostId, project.id) { mutableStateOf("") }
    var conflict by remember(hostId, project.id) { mutableStateOf<LocationConflict?>(null) }

    val trimmedName = name.trim()
    val canSaveName = !busy && trimmedName.isNotEmpty() && trimmedName != project.name
    // Same host only: a location id means nothing on another broker.
    val moveTargets = remember(projects, hostId, project.id) {
        projects.filter { it.hostId == hostId && it.project.id != project.id }
            .sortedWith(compareBy({ it.project.sortOrder }, { it.project.name }, { it.project.id }))
    }

    fun launchOp(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { block() } finally { busy = false }
        }
    }

    fun saveName() {
        if (!canSaveName) return
        launchOp {
            if (actions.rename(hostId, project.id, trimmedName) == null) message = "Couldn't rename the project."
            else nameEdited = false
        }
    }

    fun uploadImage(file: PickedFile) {
        val mime = projectImageMime(file)
        when {
            mime == null -> message = "Pick a PNG, JPEG, WebP or GIF image."
            file.source.size > PROJECT_IMAGE_MAX_BYTES -> message = "That image is over 5 MB."
            else -> launchOp {
                // Off the main thread: an Android ContentResolver read is blocking I/O.
                val bytes = withContext(Dispatchers.Default) { file.source.read(0, file.source.size.toInt()) }
                if (actions.setImage(latestRef.hostId, latestRef.project.id, bytes, mime) == null) {
                    message = "Couldn't upload the image."
                }
            }
        }
    }

    // A pick that outlived its caller (Android recreation) lands here, exactly like the composer.
    LaunchedEffect(platform) {
        platform.pendingPicks(PROJECT_SETTINGS_PICK_REQUESTER).collect { uploadImage(it) }
    }

    fun addLocation() {
        val typed = newPath.trim()
        if (typed.isEmpty() || busy) return
        conflict = null
        launchOp {
            val v = actions.validatePath(hostId, typed)
            val resolved = v?.path
            if (v == null || !v.ok || resolved.isNullOrBlank()) {
                message = v?.error ?: "Invalid path"
                return@launchOp
            }
            when (val r = actions.addLocation(hostId, project.id, resolved)) {
                is ProjectLocationResult.Added -> newPath = ""
                is ProjectLocationResult.Conflict -> {
                    val owner = projects.firstOrNull { it.hostId == hostId && it.project.id == r.projectId }
                    if (owner == null || owner.project.id == project.id) {
                        message = if (owner == null) "That folder already belongs to another project."
                                  else "That folder is already a location of this project."
                    } else {
                        conflict = LocationConflict(resolved, owner)
                    }
                }
                ProjectLocationResult.Failed -> message = "Couldn't add the location."
            }
        }
    }

    fun moveHere(c: LocationConflict) {
        // Read the owner from the LIVE catalog: its locations may have changed since the 409.
        val owner = projects.firstOrNull { it.hostId == hostId && it.project.id == c.owner.project.id }?.project
        val locationId = owner?.let { locationIdForPath(it, c.path) }
        if (locationId == null) {
            message = "Couldn't find that location in ${c.owner.project.name}."
            conflict = null
            return
        }
        launchOp {
            if (actions.moveLocation(hostId, locationId, project.id) != null) {
                conflict = null
                newPath = ""
            } else {
                message = "Couldn't move the location."
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.xl, vertical = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text("Project settings", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)

        // ── Name ──
        SectionLabel("Name")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; nameEdited = it != latestRef.project.name },
                singleLine = true,
                isError = trimmedName.isEmpty(),
                modifier = Modifier.weight(1f).testTag(ProjectSettingsTestIds.NAME),
            )
            Spacer(Modifier.width(Space.sm))
            TextButton(
                onClick = { saveName() },
                enabled = canSaveName,
                modifier = Modifier.testTag(ProjectSettingsTestIds.SAVE_NAME),
            ) { Text("Save") }
        }

        // ── Image ──
        SectionLabel("Image")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                ProjectImage(ref, loadImage, size = 40.dp) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(Space.md))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        platform.pickFiles(PickKind.Images, PROJECT_SETTINGS_PICK_REQUESTER)
                            .firstOrNull()?.let { uploadImage(it) }
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag(ProjectSettingsTestIds.PICK_IMAGE),
            ) { Text(if (project.imageId == null) "Choose image…" else "Change…") }
            if (project.imageId != null) {
                Spacer(Modifier.width(Space.sm))
                TextButton(
                    onClick = {
                        launchOp {
                            if (actions.clearImage(hostId, project.id) == null) message = "Couldn't remove the image."
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag(ProjectSettingsTestIds.REMOVE_IMAGE),
                ) { Text("Remove") }
            }
        }

        // ── Locations ──
        SectionLabel("Locations")
        if (project.locations.isEmpty()) {
            Text("No locations yet.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        project.locations.forEach { loc ->
            LocationRow(
                label = formatWorkdir(loc.path, home),
                locationId = loc.id,
                targets = moveTargets,
                enabled = !busy,
                onMove = { target ->
                    launchOp {
                        if (actions.moveLocation(hostId, loc.id, target.project.id) == null) {
                            message = "Couldn't move the location."
                        }
                    }
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newPath,
                onValueChange = { newPath = it; conflict = null },
                singleLine = true,
                placeholder = { Text("~/projects/app") },
                modifier = Modifier.weight(1f).testTag(ProjectSettingsTestIds.ADD_PATH),
            )
            Spacer(Modifier.width(Space.sm))
            TextButton(
                onClick = { addLocation() },
                enabled = !busy && newPath.isNotBlank(),
                modifier = Modifier.testTag(ProjectSettingsTestIds.ADD_LOCATION),
            ) { Text("Add location") }
        }
        conflict?.let { c ->
            Row(
                Modifier.fillMaxWidth().testTag(ProjectSettingsTestIds.CONFLICT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Already in ${c.owner.project.name}",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { moveHere(c) },
                    enabled = !busy,
                    modifier = Modifier.testTag(ProjectSettingsTestIds.MOVE_HERE),
                ) { Text("Move here") }
            }
        }

        message?.let {
            Text(
                it,
                color = cs.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(ProjectSettingsTestIds.MESSAGE),
            )
        }

        HorizontalDivider(color = cs.outlineVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = Space.xs),
    )
}

/** One location: its [formatWorkdir] label and a "Move to…" menu of the host's other projects. */
@Composable
private fun LocationRow(
    label: String,
    locationId: String,
    targets: List<ProjectRef>,
    enabled: Boolean,
    onMove: (ProjectRef) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().testTag(ProjectSettingsTestIds.location(locationId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = cs.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (targets.isNotEmpty()) {
            Box {
                TextButton(
                    onClick = { menu = true },
                    enabled = enabled,
                    modifier = Modifier.testTag(ProjectSettingsTestIds.moveTo(locationId)),
                ) { Text("Move to…") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    targets.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t.project.name) },
                            onClick = { menu = false; onMove(t) },
                            modifier = Modifier.testTag(ProjectSettingsTestIds.moveTarget(t.project.id)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sheet on a phone (Compact, or touch-only), centred dialog elsewhere — the body is identical.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdaptiveProjectContainer(
    tag: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    val touch = !LocalPointerAvailable.current
    if (compact || touch) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = cs.surfaceContainerLow,
            contentColor = cs.onSurface,
            modifier = Modifier.testTag(tag),
        ) { content() }
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = cs.surfaceContainerLow,
                contentColor = cs.onSurface,
                modifier = Modifier.widthIn(min = 360.dp, max = 520.dp).testTag(tag),
            ) { content() }
        }
    }
}

/**
 * The hosts "New project" may create on: those whose broker serves a project catalog
 * ([catalogHosts], `FleetStore.projectCatalogHosts` — an old broker has none and would 404 the
 * create), narrowed to [hostFilter] when it names one of several hosts. Empty → hide the entry.
 */
fun newProjectHosts(hosts: List<HostView>, catalogHosts: Set<String>, hostFilter: String?): List<HostView> {
    val filter = hostFilter?.takeIf { f -> hosts.size >= 2 && hosts.any { it.recordId == f } }
    return hosts.filter { it.recordId in catalogHosts && (filter == null || it.recordId == filter) }
}

/**
 * "New project": a name, and — when [hosts] offers more than one and no [initialHost] is given —
 * which host to create it on. [onCreate] returns the new project (null on failure); on success
 * [onCreated] gets `(hostId, projectId)` so the caller can open its settings.
 */
@Composable
fun NewProjectDialog(
    hosts: List<HostView>,
    initialHost: String?,
    onCreate: suspend (hostId: String, name: String) -> ProjectDto?,
    onCreated: (hostId: String, projectId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(initialHost ?: hosts.singleOrNull()?.recordId) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val askHost = initialHost == null && hosts.size > 1
    val canCreate = !busy && name.isNotBlank() && host != null

    fun create() {
        val h = host ?: return
        if (!canCreate) return
        busy = true
        error = null
        scope.launch {
            val created = try { onCreate(h, name.trim()) } finally { busy = false }
            if (created != null) onCreated(h, created.id) else error = "Couldn't create the project."
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag(ProjectSettingsTestIds.NEW_PROJECT),
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth().testTag(ProjectSettingsTestIds.NEW_PROJECT_NAME),
                )
                if (askHost) {
                    Text("Host", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    hosts.forEach { h ->
                        val selected = h.recordId == host
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) cs.secondaryContainer else cs.surfaceContainerLow)
                                .testTag(ProjectSettingsTestIds.newProjectHost(h.recordId)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { host = h.recordId }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    h.displayName + if (h.online) "" else " (offline)",
                                    color = if (selected) cs.onSecondaryContainer else cs.onSurface,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(Space.xs))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { create() },
                enabled = canCreate,
                modifier = Modifier.testTag(ProjectSettingsTestIds.NEW_PROJECT_CREATE),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}
