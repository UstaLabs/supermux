// The recent-project tiles the project picker opens on (empty search): the projects you are most
// likely to want, as big click targets that still say WHERE each one lives.
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import dev.supermux.proto.ProjectDto
import dev.supermux.session.OmniOption
import dev.supermux.workspace.ProjectRef
import dev.supermux.session.ProjectActivity
import dev.supermux.session.formatAgoShort
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.session.projectFolderName
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke

/** Test tags of the picker's catalog parts (a project, its location list, the way back). */
object CatalogPickerTestIds {
    /** Present while the picker is showing a host's project catalog. */
    const val MENU = "launcher_catalog_menu"
    const val BACK = "launcher_catalog_back"
    fun project(id: String) = "launcher_catalog_project_$id"
    fun location(path: String) = "launcher_catalog_location_$path"
}

/** A catalog project's option key — the picker's rows are keyed by path, a project has several. */
internal const val CATALOG_KEY_PREFIX = "catalog:"

/** What the picker knows about the host's catalog, for drawing a catalog option. */
internal class PickerCatalog(
    val byId: Map<String, ProjectDto>,
    val hostKey: String,
    val loadImage: suspend (ProjectRef) -> ByteArray?,
) {
    fun project(o: OmniOption.Local): ProjectDto? = o.projectId?.let(byId::get)
}

/** The option's display name: a catalog project's own name, else its folder. */
internal fun OmniOption.Local.displayName(): String = name ?: projectFolderName(path)

/** The paths an option stands for — every location of a catalog project, else its own path. */
internal fun OmniOption.Local.paths(catalog: PickerCatalog): List<String> =
    catalog.project(this)?.locations?.map { it.path } ?: listOf(path)

/** Sessions across all of an option's paths, and the latest time one of them spoke. */
internal fun OmniOption.Local.activity(catalog: PickerCatalog, activity: Map<String, ProjectActivity>): ProjectActivity? {
    val xs = paths(catalog).mapNotNull { activity[it] }
    if (xs.isEmpty()) return null
    return ProjectActivity(xs.sumOf { it.sessions }, xs.mapNotNull { it.lastActiveMs }.maxOrNull())
}

/** Where the option lives: "~/work/app", "~/work/app +1" for more locations, or "No folder yet". */
internal fun OmniOption.Local.locationText(catalog: PickerCatalog, home: String): String {
    val p = catalog.project(this) ?: return homeRelativePath(path, home)
    val first = p.locations.firstOrNull()?.path ?: return "No folder yet"
    val more = p.locations.size - 1
    return homeRelativePath(first, home) + if (more > 0) " +$more" else ""
}

internal fun OmniOption.Local.testTag(): String =
    projectId?.let { CatalogPickerTestIds.project(it) } ?: "project_row_$path"

/** The project's image when it has one, else its monogram. */
@Composable
internal fun ProjectMonogram(o: OmniOption.Local, catalog: PickerCatalog, size: androidx.compose.ui.unit.Dp) {
    val name = o.displayName()
    val hue = monogramHue(name)
    @Composable
    fun Letter() {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).clip(RoundedCornerShape(Radii.md)).background(hue.copy(alpha = 0.22f)),
        ) {
            Text(
                name.trimStart('.', '~').firstOrNull()?.uppercase() ?: "~",
                color = hue,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.44f).sp,
            )
        }
    }
    val project = catalog.project(o)
    if (project == null) Letter() else ProjectImage(ProjectRef(catalog.hostKey, project), catalog.loadImage, size = size) { Letter() }
}

/** How many recent projects the empty picker shows as tiles; the rest stay rows. */
const val PROJECT_TILE_COUNT = 6

/** Tiles per row. */
const val PROJECT_TILE_COLUMNS = 3

/** [path] with the [home] prefix shown as "~" — the full location, as short as it honestly gets. */
internal fun homeRelativePath(path: String, home: String): String = when {
    home.isEmpty() -> path
    path == home -> "~"
    path.startsWith("$home/") -> "~" + path.removePrefix(home)
    else -> path
}

/** The same name-hashed hue the sidebar's project group tile uses, so a project keeps its colour. */
internal fun monogramHue(name: String): Color = Color.hsl(((name.hashCode() ushr 1) % 360).toFloat(), 0.45f, 0.42f)

/** [text] with the characters at [hits] drawn bold in [color] — the fuzzy match, made visible. */
internal fun highlightHits(text: String, hits: List<Int>, color: Color): AnnotatedString {
    if (hits.isEmpty()) return AnnotatedString(text)
    val set = hits.toSet()
    return buildAnnotatedString {
        text.forEachIndexed { i, c ->
            if (i in set) withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(c) }
            else append(c)
        }
    }
}

/**
 * One row of up to [PROJECT_TILE_COLUMNS] tiles. A short last row keeps full-row tile widths.
 * Each tile carries `project_row_<path>` like a list row, so a test (or a user) picks it the same way.
 */
@Composable
internal fun ProjectTileRow(
    row: List<OmniOption.Local>,
    current: String,
    home: String,
    catalog: PickerCatalog,
    activity: Map<String, ProjectActivity>,
    nowMs: Long,
    highlighted: (String) -> Boolean,
    enabled: Boolean,
    onPick: (OmniOption.Local) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.xs),
    ) {
        row.forEach { o ->
            Box(Modifier.weight(1f)) {
                ProjectTile(
                    o,
                    catalog,
                    location = o.locationText(catalog, home),
                    activity = o.activity(catalog, activity),
                    nowMs = nowMs,
                    selected = current in o.paths(catalog),
                    highlighted = highlighted(o.path),
                    enabled = enabled,
                ) { onPick(o) }
            }
        }
        repeat(PROJECT_TILE_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
    }
}

@Composable
private fun ProjectTile(
    o: OmniOption.Local,
    catalog: PickerCatalog,
    location: String,
    activity: ProjectActivity?,
    nowMs: Long,
    selected: Boolean,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.md)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlighted) cs.onSurface.copy(alpha = 0.06f) else Color.Transparent)
            .border(Stroke.hairline, if (selected) cs.primary else cs.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.sm, vertical = Space.md)
            .testTag(o.testTag()),
    ) {
        ProjectMonogram(o, catalog, 36.dp)
        Text(
            o.displayName(),
            color = cs.onSurface,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StartEllipsizedText(
            location,
            style = TextStyle(color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 10.sp),
        )
        if (activity != null) ActivityLine(activity, nowMs)
    }
}

/** "● 2m": a green dot when sessions live in the project, then how long since one last spoke. */
@Composable
internal fun ActivityLine(activity: ProjectActivity, nowMs: Long) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (activity.sessions > 0) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(LocalSemantics.current.success))
        }
        activity.lastActiveMs?.let { Text(formatAgoShort(nowMs, it), fontSize = 10.sp, color = cs.onSurfaceVariant) }
    }
}

/** The picker's section labels ("JUMP BACK IN", "EVERYTHING ELSE"). */
@Composable
internal fun PickerSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = modifier.padding(start = Space.md, end = Space.md, top = Space.md, bottom = Space.xs),
    )
}

/**
 * One line of [text]; when it is too wide, characters are dropped from the START and replaced by
 * "…" so the end stays readable. Hand-rolled because `TextOverflow.StartEllipsis` is not honoured
 * by Compose Desktop's text layout (it ellipsizes the end). A plain [Layout] — not
 * BoxWithConstraints — because the dropdown menu asks its rows for intrinsic sizes, which a
 * SubcomposeLayout cannot answer.
 */
@Composable
internal fun StartEllipsizedText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val laidOut = remember { mutableStateOf<TextLayoutResult?>(null) }
    Layout(
        content = {},
        modifier = modifier
            .semantics { this.text = AnnotatedString(text) }
            .drawBehind { laidOut.value?.let { drawText(it) } },
    ) { _, constraints ->
        fun measure(s: String) = measurer.measure(s, style, maxLines = 1, softWrap = false)
        val full = measure(text)
        val max = constraints.maxWidth
        val result = if (!constraints.hasBoundedWidth || full.size.width <= max) {
            full
        } else {
            // Longest suffix that still fits behind the ellipsis — binary search on its length.
            var lo = 0
            var hi = text.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (measure("…" + text.takeLast(mid)).size.width <= max) lo = mid else hi = mid - 1
            }
            measure("…" + text.takeLast(lo))
        }
        laidOut.value = result
        layout(
            result.size.width.coerceIn(constraints.minWidth, constraints.maxWidth),
            result.size.height.coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {}
    }
}

/**
 * The picker's one list-row look: a single line, the same 12dp text inset as the tiles above it, a
 * soft wash on hover or keyboard highlight. Not the menu's [dev.supermux.ui.widgets.DropdownMenuItem],
 * whose solid accent hover would sit under this row's own coloured text.
 */
@Composable
internal fun PickerRow(
    onClick: () -> Unit,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val touch = !LocalPointerAvailable.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.sm)
            .clip(RoundedCornerShape(Radii.sm))
            .background(if ((highlighted || hovered) && enabled) cs.onSurface.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .heightIn(min = if (touch) 44.dp else 32.dp)
            .padding(horizontal = Space.xs, vertical = 6.dp),
    ) { content() }
}

/**
 * A catalog project's locations, in place of the search: "← Name", then one row per folder.
 * Escape or the arrow goes back to the projects.
 */
@Composable
internal fun CatalogLocations(
    project: ProjectDto,
    catalog: PickerCatalog,
    current: String,
    home: String,
    activity: Map<String, ProjectActivity>,
    nowMs: Long,
    onBack: () -> Unit,
    onLocation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val option = OmniOption.Local(label = "", path = CATALOG_KEY_PREFIX + project.id, name = project.name, projectId = project.id)
    Column(
        modifier
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onBack(); true } else false
            }
            .padding(bottom = Space.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            modifier = Modifier.padding(start = Space.xs, end = Space.md, top = Space.xs),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp).testTag(CatalogPickerTestIds.BACK)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to projects", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            ProjectMonogram(option, catalog, 22.dp)
            Text(project.name, color = cs.onSurface, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        PickerSectionLabel("Choose a folder")
        project.locations.forEach { loc ->
            PickerRow(
                onClick = { onLocation(loc.path) },
                highlighted = false,
                modifier = Modifier.testTag(CatalogPickerTestIds.location(loc.path)),
            ) {
                Icon(Icons.Filled.FolderOpen, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                StartEllipsizedText(
                    homeRelativePath(loc.path, home),
                    style = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.weight(1f),
                )
                activity[loc.path]?.let { ActivityLine(it, nowMs) }
                if (loc.path == current) Icon(Icons.Filled.Check, "Current folder", Modifier.size(16.dp), tint = cs.primary)
            }
        }
    }
}
