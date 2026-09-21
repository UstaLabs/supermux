// The launcher's persistent-project picker (Persistent Projects, Task 10): pick a PROJECT, then —
// only when it has several locations and none is remembered — one of its locations.
//
// Shown instead of the forge omnibox ([ProjectPicker]) whenever the target host's catalog is known
// and non-empty. The omnibox is still one row away ("Other folder…"), and it is also the path
// entry a location-less project uses, so typed-path validation, clone and create are never lost.
// Same containers as the omnibox: an anchored dropdown under a pointer, a bottom sheet under touch.
package dev.supermux.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermux.proto.ProjectDto
import dev.supermux.session.formatWorkdir
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.theme.Size
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.workspace.ProjectRef

object CatalogPickerTestIds {
    const val MENU = "launcher_catalog_menu"
    const val OTHER = "launcher_catalog_other"
    const val BACK = "launcher_catalog_back"
    fun project(id: String) = "launcher_catalog_project_$id"
    fun locations(id: String) = "launcher_catalog_locations_$id"
    fun location(path: String) = "launcher_catalog_location_$path"
}

/**
 * @param projects the host's catalog, already in display order.
 * @param locationsFor the project whose location list is showing, or null for the project list —
 *   hoisted so the launcher can open straight onto a project's locations (sidebar "+").
 * @param onProject a project row was tapped; the launcher applies `launchLocation`.
 * @param onShowLocations switch between the project list (null) and a project's locations.
 * @param onLocation a location was picked explicitly.
 * @param onOther "Other folder…" — hand over to the path omnibox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogProjectPicker(
    expanded: Boolean,
    projects: List<ProjectDto>,
    hostId: String,
    current: String,
    home: String,
    locationsFor: String?,
    loadImage: suspend (ProjectRef) -> ByteArray?,
    onProject: (ProjectDto) -> Unit,
    onShowLocations: (String?) -> Unit,
    onLocation: (ProjectDto, String) -> Unit,
    onOther: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val touch = !LocalPointerAvailable.current
    val shown = locationsFor?.let { id -> projects.firstOrNull { it.id == id } }

    @Composable
    fun ProjectRow(p: ProjectDto) {
        val selected = p.locations.any { it.path == current }
        val subtitle = when (p.locations.size) {
            0 -> "No locations yet"
            1 -> formatWorkdir(p.locations.single().path, home)
            else -> "${p.locations.size} locations"
        }
        DropdownMenuItem(
            text = {
                Column {
                    Text(
                        p.name,
                        color = if (selected) cs.primary else cs.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        color = cs.onSurfaceVariant,
                        fontFamily = if (p.locations.size == 1) FontFamily.Monospace else null,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            leadingIcon = {
                ProjectImage(ProjectRef(hostId, p), loadImage, size = 20.dp) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(Space.lg + Space.xs),
                    )
                }
            },
            trailingIcon = if (p.locations.size > 1) {
                {
                    // Always offers the full list, so a remembered location never locks the
                    // user out of the others (the row itself goes straight to the remembered one).
                    IconButton(
                        onClick = { onShowLocations(p.id) },
                        modifier = Modifier
                            .size(if (touch) 40.dp else 24.dp)
                            .testTag(CatalogPickerTestIds.locations(p.id)),
                    ) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = "Choose a location of ${p.name}",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(Space.lg),
                        )
                    }
                }
            } else if (selected) {
                { Icon(Icons.Filled.Check, null, Modifier.size(Space.lg), tint = cs.primary) }
            } else {
                null
            },
            modifier = Modifier.testTag(CatalogPickerTestIds.project(p.id)),
            onClick = { onProject(p) },
        )
    }

    @Composable
    fun Body() {
        Column(
            Modifier
                .then(if (touch) Modifier.fillMaxWidth() else Modifier.width(Size.omniboxWidth))
                .padding(bottom = if (touch) Space.xl else Space.xs),
        ) {
            if (shown == null) {
                Text(
                    "Projects",
                    color = cs.onSurfaceVariant,
                    style = if (touch) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
                    fontWeight = if (touch) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = Space.xl - Space.xs, vertical = Space.sm),
                )
                Column(
                    Modifier
                        .heightIn(max = if (touch) Size.omniboxSheetListMax else Size.omniboxListMax)
                        .verticalScroll(rememberScrollState()),
                ) {
                    projects.forEach { p -> ProjectRow(p) }
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            "Other folder…",
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(Space.lg + Space.xs),
                        )
                    },
                    modifier = Modifier.testTag(CatalogPickerTestIds.OTHER),
                    onClick = onOther,
                )
            } else {
                DropdownMenuItem(
                    text = {
                        Text(
                            shown.name,
                            color = cs.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "All projects",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(Space.lg),
                        )
                    },
                    modifier = Modifier.testTag(CatalogPickerTestIds.BACK),
                    onClick = { onShowLocations(null) },
                )
                Column(
                    Modifier
                        .heightIn(max = if (touch) Size.omniboxSheetListMax else Size.omniboxListMax)
                        .verticalScroll(rememberScrollState()),
                ) {
                    shown.locations.forEach { loc ->
                        val selected = loc.path == current
                        DropdownMenuItem(
                            text = {
                                Text(
                                    formatWorkdir(loc.path, home),
                                    color = if (selected) cs.primary else cs.onSurface,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(Space.lg + Space.xs),
                                )
                            },
                            trailingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, null, Modifier.size(Space.lg), tint = cs.primary) }
                            } else {
                                null
                            },
                            modifier = Modifier.testTag(CatalogPickerTestIds.location(loc.path)),
                            onClick = { onLocation(shown, loc.path) },
                        )
                    }
                }
            }
        }
    }

    if (touch) {
        if (expanded) {
            ModalBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = cs.surfaceContainerLow,
                contentColor = cs.onSurface,
                modifier = Modifier.testTag(CatalogPickerTestIds.MENU),
            ) { Body() }
        }
    } else {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag(CatalogPickerTestIds.MENU),
        ) { Body() }
    }
}
