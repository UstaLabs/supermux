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
import dev.supermux.session.OmniOption
import dev.supermux.session.ProjectActivity
import dev.supermux.session.formatAgoShort
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.session.projectFolderName
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke

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
    activity: Map<String, ProjectActivity>,
    nowMs: Long,
    highlighted: (String) -> Boolean,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.xs),
    ) {
        row.forEach { o ->
            Box(Modifier.weight(1f)) {
                ProjectTile(o, homeRelativePath(o.path, home), activity[o.path], nowMs, selected = o.path == current, highlighted = highlighted(o.path), enabled = enabled) { onPick(o.path) }
            }
        }
        repeat(PROJECT_TILE_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
    }
}

@Composable
private fun ProjectTile(
    o: OmniOption.Local,
    location: String,
    activity: ProjectActivity?,
    nowMs: Long,
    selected: Boolean,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val name = projectFolderName(o.path)
    val hue = monogramHue(name)
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
            .testTag("project_row_${o.path}"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(Radii.md)).background(hue.copy(alpha = 0.22f)),
        ) {
            Text(
                name.trimStart('.', '~').firstOrNull()?.uppercase() ?: "~",
                color = hue,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
        Text(
            name,
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
